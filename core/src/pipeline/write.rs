use super::{Job, Worker};
use crate::engine::{EngineError, TextEngine};
use crate::library::lock;
use crate::meeting::{Change, MeetingId, MinutesDraft};
use crate::minutes::{self, Step};

impl Worker {
    // One call of the minutes, its draft kept until the next, then the minutes settled once written.
    pub(super) fn write(&mut self, id: &MeetingId, offers: u64, engine: &dyn TextEngine) {
        let Ok(meeting) = self.library.meeting(id) else { return self.set_aside(id) };
        // Settled, or to be heard again, since it was listed.
        if !matches!(Job::owed(&meeting), Some(Job::Write(_))) {
            return;
        }
        if lock(&self.shared.forgotten).contains(id) {
            return self.set_aside(id);
        }
        // Heard from its start first: minutes of no line at all would say nothing of the audio. A meeting heard whole has
        // a line, or no minutes owed. Its audio erased, nothing is left to hear: its minutes are written from no line,
        // and so say nothing.
        if meeting.never_heard() {
            if !self.library.change(Change::Rehear { meeting: id.clone() }) {
                self.set_aside(id);
            }
            return;
        }
        let mut draft = self.drafts.remove(id).unwrap_or_default();
        let stop = self.call(id);
        let written = draft.step(&meeting.transcript, minutes::day(&meeting), engine, &stop);
        *lock(&self.shared.call) = None;
        let minutes = match written {
            Ok(Step::Done(minutes)) => minutes,
            Ok(Step::More) => {
                self.drafts.insert(id.clone(), draft);
                return;
            }
            // Cut short by a delete or a close: whatever the engine says of it, the call is made again.
            Err(_) if stop.stopped() => {
                self.drafts.insert(id.clone(), draft);
                return;
            }
            Err(EngineError::Unavailable) => {
                self.text_resting = Some(offers);
                self.drafts.insert(id.clone(), draft);
                return;
            }
            // Stopped when nobody stopped it: asking again would ask for ever. Asked again by the user instead.
            Err(EngineError::Failed | EngineError::Stopped) => MinutesDraft::Failed,
        };
        // Refused only if the meeting changed under the call: deleted, or its file unreadable.
        if !self.library.change(Change::Drafted { meeting: id.clone(), minutes }) {
            self.set_aside(id);
        }
    }
}

#[cfg(test)]
mod tests {
    use std::collections::VecDeque;
    use std::fs;
    use std::sync::{Arc, Mutex, mpsc};

    use super::super::tests::{Answer, Ear, NOW, begin, open, recorded, recording, sentences, settles, transcript};
    use super::super::{Able, Job, Pipeline, next};
    use crate::engine::{About, Catalogue, Engine, EngineError, Engines, Stop, TextEngine, Window};
    use crate::format;
    use crate::library::{Library, LibraryError, lock};
    use crate::meeting::testing::{id, line, meeting};
    use crate::meeting::{Change, Meeting, MeetingId, MinutesState, TranscriptLine};
    use crate::speech::testing::frames;

    // What one call of the minutes does.
    enum Reply {
        Write,
        Fail,
        Unavailable,
        Panic,
        // Says it was stopped when it was not.
        Stopped,
        // Says it was called, then waits for the test: a call that takes its time. Once stopped, says so.
        Hold { called: mpsc::Sender<()>, go: mpsc::Receiver<()> },
    }

    // Who was called, in order: an engine of each kind writes here.
    type Log = Arc<Mutex<Vec<&'static str>>>;

    // Writes minutes from any part it is given: its summary names the first time it read, so that a merge says which
    // parts it merged.
    struct Scribe {
        about: About,
        window: Window,
        replies: Mutex<VecDeque<Reply>>,
        prompts: Mutex<Vec<String>>,
        log: Log,
    }

    impl Scribe {
        fn new(replies: Vec<Reply>, log: &Log) -> Arc<Self> {
            Self::sized(4_096, replies, log)
        }

        // A small window takes an hour in many slices.
        fn sized(context: usize, replies: Vec<Reply>, log: &Log) -> Arc<Self> {
            Arc::new(Scribe {
                about: About { name: "scribe".into(), remote: false },
                window: Window { context, reply: 1_024 },
                replies: Mutex::new(replies.into()),
                prompts: Mutex::default(),
                log: log.clone(),
            })
        }

        fn prompts(&self) -> Vec<String> {
            self.prompts.lock().unwrap().clone()
        }
    }

    impl Engine for Scribe {
        fn about(&self) -> &About {
            &self.about
        }
    }

    impl TextEngine for Scribe {
        fn window(&self) -> Window {
            self.window
        }

        fn tokens(&self, text: &str) -> usize {
            text.len().div_ceil(3)
        }

        fn complete(&self, _: &str, prompt: &str, stop: &Stop) -> Result<String, EngineError> {
            let reply = self.replies.lock().unwrap().pop_front().unwrap_or(Reply::Write);
            if let Reply::Hold { called, go } = &reply {
                called.send(()).unwrap();
                let _ = go.recv();
            }
            self.log.lock().unwrap().push("write");
            self.prompts.lock().unwrap().push(prompt.into());
            match reply {
                Reply::Write | Reply::Hold { .. } if stop.stopped() => Err(EngineError::Stopped),
                Reply::Write | Reply::Hold { .. } => {
                    let first =
                        prompt.lines().find_map(|line| line.strip_prefix('[')?.split_once(']')).map(|(time, _)| time);
                    Ok(format!("SUMMARY: Point de {}.\nDECISION: -\nACTION: -", first.unwrap_or("synthèse")))
                }
                Reply::Fail => Err(EngineError::Failed),
                Reply::Unavailable => Err(EngineError::Unavailable),
                Reply::Panic => panic!("a faulty model"),
                Reply::Stopped => Err(EngineError::Stopped),
            }
        }
    }

    // An ear that writes in the same log as the scribe.
    struct Logged {
        ear: Arc<Ear>,
        log: Log,
    }

    impl Engine for Logged {
        fn about(&self) -> &About {
            self.ear.about()
        }
    }

    impl crate::engine::SpeechEngine for Logged {
        fn longest_ms(&self) -> u32 {
            self.ear.longest_ms()
        }

        fn transcribe(
            &self,
            sound: &[i16],
            hint: &crate::engine::Hint,
            stop: &Stop,
        ) -> Result<Vec<crate::engine::Segment>, EngineError> {
            self.log.lock().unwrap().push("hear");
            self.ear.transcribe(sound, hint, stop)
        }
    }

    fn both(ear: Option<&Arc<Ear>>, scribe: Option<&Arc<Scribe>>, log: &Log) -> Arc<Catalogue> {
        let ear: Vec<Arc<dyn crate::engine::SpeechEngine>> =
            ear.map(|ear| Arc::new(Logged { ear: ear.clone(), log: log.clone() }) as _).into_iter().collect();
        let scribe: Vec<Arc<dyn TextEngine>> = scribe.map(|scribe| scribe.clone() as _).into_iter().collect();
        Arc::new(Catalogue::new(ear, scribe, vec![]))
    }

    fn engines(ear: &Arc<Ear>, scribe: &Arc<Scribe>, log: &Log) -> Engines {
        Arc::new(Mutex::new(both(Some(ear), Some(scribe), log)))
    }

    fn summary(library: &Library, meeting: &MeetingId) -> Option<String> {
        match library.meeting(meeting).unwrap().minutes {
            MinutesState::Written { minutes } => Some(minutes.summary),
            _ => None,
        }
    }

    fn minutes(library: &Library, meeting: &MeetingId) -> MinutesState {
        library.meeting(meeting).unwrap().minutes
    }

    // Heard whole long before, its minutes owed: a meeting a killed app left, laid on the disk before the launch.
    fn owed(folder: &tempfile::TempDir, name: &str, minutes_long: u64) -> MeetingId {
        let transcript: Vec<TranscriptLine> = (0..minutes_long)
            .map(|minute| {
                line(minute * 60_000, &["On fait le point sur le budget et sur les recrutements."; 12].join(" "))
            })
            .collect();
        let meeting = Meeting {
            minutes: MinutesState::Writing,
            transcribed_ms: None,
            offset_minutes: Some(60),
            transcript,
            ..meeting(name)
        };
        fs::write(folder.path().join(format!("{name}.notitia")), format::encode(&meeting).unwrap()).unwrap();
        meeting.id
    }

    #[test]
    fn the_work_comes_in_order_and_minutes_follow_their_transcript() {
        let (one, two, three) = (id("recorded-1"), id("recorded-2"), id("recorded-3"));
        let owed = [Job::Write(three.clone()), Job::Transcribe(two.clone()), Job::Write(one.clone())];
        let none = |_: &MeetingId| false;
        let all = Able { hear: true, write: true };
        assert_eq!(next(&owed, None, none, all), Some(Job::Write(three.clone())), "the latest first, whatever it owes");
        assert_eq!(next(&owed, Some(&two), none, all), Some(Job::Transcribe(two.clone())), "the one recorded first");
        assert_eq!(next(&owed, Some(&three), none, all), Some(Job::Write(three.clone())), "held but heard whole");
        let deaf = Able { hear: false, write: true };
        assert_eq!(next(&owed, Some(&two), none, deaf), Some(Job::Write(three.clone())), "no engine to hear with");
        let mute = Able { hear: true, write: false };
        assert_eq!(next(&owed, None, none, mute), Some(Job::Transcribe(two.clone())), "no engine to write with");
        assert_eq!(next(&owed, None, |meeting| *meeting == three, all), Some(Job::Transcribe(two)), "at rest");
        assert_eq!(next(&owed, None, none, Able { hear: false, write: false }), None);
        assert_eq!(next(&[Job::Write(one)], None, none, mute), None);
    }

    #[test]
    fn a_recorded_meeting_ends_with_its_minutes_written() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sentences(&[3_000, 2_000]));
        let log = Log::default();
        let (ear, scribe) = (Ear::new(30_000, vec![]), Scribe::new(vec![], &log));
        Pipeline::spawn(library.clone(), engines(&ear, &scribe, &log)).unwrap().settled();
        let written = library.meeting(&meeting).unwrap();
        assert_eq!(written.transcribed_ms, None);
        assert_eq!(summary(&library, &meeting).as_deref(), Some("Point de 00:00."));
        // Heard window by window, then written once, from the whole transcript and the day where it was recorded.
        assert_eq!(*log.lock().unwrap(), ["hear", "hear", "write"]);
        let prompt = &scribe.prompts()[0];
        assert!(prompt.starts_with("Meeting of 2026-11-24 (tuesday).\n\n"), "{prompt}");
        assert_eq!(prompt.lines().filter(|line| line.starts_with('[')).count(), written.transcript.len());
    }

    #[test]
    fn minutes_wait_for_an_engine_to_write_them_and_come_with_its_offer() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sentences(&[3_000]));
        let log = Log::default();
        let ear = Ear::new(30_000, vec![]);
        let engines: Engines = Arc::new(Mutex::new(both(Some(&ear), None, &log)));
        let pipeline = Arc::new(Pipeline::spawn(library.clone(), engines.clone()).unwrap());
        settles(&pipeline);
        let heard = library.meeting(&meeting).unwrap();
        assert_eq!((heard.transcribed_ms, heard.minutes), (None, MinutesState::Writing));
        let scribe = Scribe::new(vec![], &log);
        *lock(&engines) = both(Some(&ear), Some(&scribe), &log);
        pipeline.offered();
        settles(&pipeline);
        assert!(summary(&library, &meeting).is_some());
    }

    #[test]
    fn the_meeting_recorded_comes_between_two_calls_of_long_minutes_which_go_on_where_they_were() {
        let folder = tempfile::tempdir().unwrap();
        let backlog = owed(&folder, "recorded-1", 60);
        let (_, library) = open(&folder);
        let log = Log::default();
        let (hold, calling, go) = held_call_of_scribe();
        let scribe = Scribe::sized(2_048, vec![hold], &log);
        let ear = Ear::new(30_000, vec![]);
        let pipeline = Pipeline::spawn(library.clone(), engines(&ear, &scribe, &log)).unwrap();
        calling.recv().unwrap();
        // A meeting begins while the first slice is written: its first sentence is heard before the second slice.
        let waker = pipeline.waker();
        waker.recorded(&recording(&id("recorded-9"), 0));
        let live = begin(&library, "recorded-9", 120);
        let mut writer = library.record(&live, 1).unwrap();
        frames(&sentences(&[3_000])).iter().for_each(|frame| writer.write(&[*frame]).unwrap());
        waker.recorded(&recording(&live, 5_500));
        go.send(()).unwrap();
        pipeline.settled();
        let calls = log.lock().unwrap().clone();
        assert_eq!(calls[..3], ["write", "hear", "write"], "{calls:?}");
        assert_eq!(transcript(&library, &live).len(), 1);
        // Written to its end meanwhile, no slice asked twice, the first one included.
        let prompts = scribe.prompts();
        let slices: Vec<&String> = prompts.iter().filter(|prompt| prompt.contains(" Part ")).collect();
        assert!(slices.len() > 3);
        let mut asked = slices.clone();
        asked.dedup();
        assert_eq!(asked.len(), slices.len());
        assert!(slices[0].contains("Part 1 of") && slices[1].contains("Part 2 of"));
        assert!(summary(&library, &backlog).is_some());
    }

    fn held_call_of_scribe() -> (Reply, mpsc::Receiver<()>, mpsc::Sender<()>) {
        let ((called, calling), (go, going)) = (mpsc::channel(), mpsc::channel());
        (Reply::Hold { called, go: going }, calling, go)
    }

    #[test]
    fn a_writer_away_marks_nothing_lets_the_transcripts_go_on_and_takes_up_where_it_was() {
        let folder = tempfile::tempdir().unwrap();
        let long = owed(&folder, "recorded-1", 60);
        let (_, library) = open(&folder);
        let first = recorded(&library, "recorded-2", 60, &sentences(&[3_000]));
        let log = Log::default();
        // The minutes of the latest, then the first slice of the hour, which finds the writer away.
        let scribe = Scribe::sized(2_048, vec![Reply::Write, Reply::Unavailable], &log);
        let ear = Ear::new(30_000, vec![]);
        let pipeline = Arc::new(Pipeline::spawn(library.clone(), engines(&ear, &scribe, &log)).unwrap());
        settles(&pipeline);
        assert!(summary(&library, &first).is_some());
        assert_eq!(scribe.prompts().len(), 2);
        // Recorded meanwhile: heard, its minutes waiting with the hour's.
        let next = recorded(&library, "recorded-3", 90, &sentences(&[3_000]));
        pipeline.changed();
        settles(&pipeline);
        settles(&pipeline);
        assert_eq!(scribe.prompts().len(), 2, "never asked again in a loop");
        let heard = library.meeting(&next).unwrap();
        assert_eq!((heard.transcribed_ms, heard.minutes), (None, MinutesState::Writing));
        assert_eq!(minutes(&library, &long), MinutesState::Writing);
        pipeline.offered();
        settles(&pipeline);
        let prompts = scribe.prompts();
        // The latest first, then the slice the writer was away for, asked again, and each other slice once.
        assert!(summary(&library, &next).is_some() && summary(&library, &long).is_some());
        assert_eq!(prompts[1], prompts[3]);
        let mut slices: Vec<&String> = prompts[3..].iter().filter(|prompt| prompt.contains(" Part ")).collect();
        let asked = slices.len();
        slices.dedup();
        assert!(asked > 3 && slices.len() == asked);
    }

    #[test]
    fn closing_during_a_call_of_the_minutes_writes_nothing_and_the_next_launch_writes_them() {
        let folder = tempfile::tempdir().unwrap();
        let meeting = owed(&folder, "recorded-1", 3);
        let (_, library) = open(&folder);
        let log = Log::default();
        let (hold, calling, go) = held_call_of_scribe();
        let scribe = Scribe::new(vec![hold], &log);
        let pipeline = Pipeline::spawn(library.clone(), engines(&Ear::new(30_000, vec![]), &scribe, &log)).unwrap();
        calling.recv().unwrap();
        let shared = pipeline.shared.clone();
        let closing = std::thread::spawn(move || drop(pipeline));
        while !lock(&shared.call).as_ref().is_some_and(|(_, stop)| stop.stopped()) {
            std::thread::yield_now();
        }
        go.send(()).unwrap();
        closing.join().unwrap();
        assert_eq!(minutes(&library, &meeting), MinutesState::Writing);
        let scribe = Scribe::new(vec![], &log);
        Pipeline::spawn(library.clone(), engines(&Ear::new(30_000, vec![]), &scribe, &log)).unwrap().settled();
        assert!(summary(&library, &meeting).is_some());
    }

    #[test]
    fn a_meeting_deleted_during_a_call_of_its_minutes_is_stopped_and_does_not_come_back() {
        let folder = tempfile::tempdir().unwrap();
        let meeting = owed(&folder, "recorded-1", 3);
        let (_, library) = open(&folder);
        let log = Log::default();
        let (hold, calling, go) = held_call_of_scribe();
        let scribe = Scribe::new(vec![hold], &log);
        let pipeline = Pipeline::spawn(library.clone(), engines(&Ear::new(30_000, vec![]), &scribe, &log)).unwrap();
        calling.recv().unwrap();
        pipeline.forget(std::slice::from_ref(&meeting));
        library.apply(Change::Delete { meetings: vec![meeting.clone()] });
        go.send(()).unwrap();
        pipeline.settled();
        assert_eq!(scribe.prompts().len(), 1);
        assert_eq!(library.meeting(&meeting).err(), Some(LibraryError::NotFound));
        assert!(!folder.path().join("recorded-1.notitia").exists());
    }

    #[test]
    fn a_writer_that_panics_sets_its_meeting_aside_and_the_others_are_written() {
        let folder = tempfile::tempdir().unwrap();
        let earlier = owed(&folder, "recorded-1", 1);
        let latest = owed(&folder, "recorded-2", 1);
        let (_, library) = open(&folder);
        // The same start: the latest first by its name.
        let log = Log::default();
        let scribe = Scribe::new(vec![Reply::Panic], &log);
        let pipeline = Pipeline::spawn(library.clone(), engines(&Ear::new(30_000, vec![]), &scribe, &log)).unwrap();
        pipeline.settled();
        pipeline.offered();
        pipeline.settled();
        let written: Vec<bool> =
            [&earlier, &latest].iter().map(|meeting| summary(&library, meeting).is_some()).collect();
        assert_eq!(written.iter().filter(|written| **written).count(), 1, "{written:?}");
        assert_eq!(scribe.prompts().len(), 2);
    }

    // Recorded and kept by an app before the core's, which had no engine: its audio beside it, not a line, its minutes
    // as that app left them.
    fn unheard(folder: &tempfile::TempDir, name: &str, minutes: MinutesState) -> MeetingId {
        let (_, library) = open(folder);
        let meeting = recorded(&library, name, 0, &sentences(&[3_000]));
        let kept = library.meeting(&meeting).unwrap();
        let left = Meeting { duration_ms: 5_500, transcribed_ms: None, minutes, transcript: vec![], ..kept };
        drop(library);
        fs::write(folder.path().join(format!("{name}.notitia")), format::encode(&left).unwrap()).unwrap();
        let _ = fs::remove_file(folder.path().join("index"));
        meeting
    }

    #[test]
    fn a_meeting_an_app_without_an_engine_left_is_heard_then_written_once_asked() {
        let folder = tempfile::tempdir().unwrap();
        let meeting = unheard(&folder, "recorded-1", MinutesState::Unavailable);
        let (_, library) = open(&folder);
        let log = Log::default();
        let (ear, scribe) = (Ear::new(30_000, vec![]), Scribe::new(vec![], &log));
        let pipeline = Arc::new(Pipeline::spawn(library.clone(), engines(&ear, &scribe, &log)).unwrap());
        settles(&pipeline);
        assert!(log.lock().unwrap().is_empty(), "left as it was until the user asks");
        assert!(library.change(Change::Rewrite { meeting: meeting.clone() }));
        pipeline.changed();
        settles(&pipeline);
        assert_eq!(*log.lock().unwrap(), ["hear", "write"]);
        let written = library.meeting(&meeting).unwrap();
        assert_eq!((written.transcribed_ms, written.transcript.len()), (None, 1));
        assert!(summary(&library, &meeting).is_some());
        let prompt = &scribe.prompts()[0];
        assert_eq!(prompt.lines().filter(|line| line.starts_with('[')).count(), 1, "written from what was heard");
    }

    #[test]
    fn minutes_owed_of_a_meeting_never_heard_are_written_once_it_is_heard_and_of_no_sound_without_a_call() {
        let folder = tempfile::tempdir().unwrap();
        // A transcription of the app killed before its first line.
        let cut = unheard(&folder, "recorded-1", MinutesState::Writing);
        let silent = Meeting { duration_ms: 0, minutes: MinutesState::Writing, ..meeting("recorded-2") };
        fs::write(folder.path().join("recorded-2.notitia"), format::encode(&silent).unwrap()).unwrap();
        let (_, library) = open(&folder);
        let log = Log::default();
        let (ear, scribe) = (Ear::new(30_000, vec![]), Scribe::new(vec![], &log));
        Pipeline::spawn(library.clone(), engines(&ear, &scribe, &log)).unwrap().settled();
        assert_eq!(*log.lock().unwrap(), ["hear", "write"]);
        assert!(summary(&library, &cut).is_some());
        assert_eq!(summary(&library, &silent.id).as_deref(), Some(""));
        assert!(library.meeting(&silent.id).unwrap().transcript.is_empty());
    }

    #[test]
    fn minutes_that_failed_are_written_again_when_the_user_asks() {
        let folder = tempfile::tempdir().unwrap();
        let meeting = owed(&folder, "recorded-1", 1);
        let (_, library) = open(&folder);
        let log = Log::default();
        let scribe = Scribe::new(vec![Reply::Fail], &log);
        let pipeline =
            Arc::new(Pipeline::spawn(library.clone(), engines(&Ear::new(30_000, vec![]), &scribe, &log)).unwrap());
        settles(&pipeline);
        assert_eq!(minutes(&library, &meeting), MinutesState::Failed);
        assert_eq!(scribe.prompts().len(), 1, "a failure is not asked again by itself");
        // As the session does with a change an app asks.
        assert!(library.change(Change::Rewrite { meeting: meeting.clone() }));
        pipeline.changed();
        settles(&pipeline);
        assert!(summary(&library, &meeting).is_some());
        let written = library.meeting(&meeting).unwrap();
        assert_eq!(written.modified_at, NOW);
    }

    #[test]
    fn a_window_heard_while_its_audio_is_erased_leaves_its_minutes_to_be_written() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        // Begun 31 days before: its audio is past the days it is kept, and it was never heard.
        let meeting = recorded(&library, "recorded-1", -31 * 24 * 60, &sentences(&[3_000, 3_000]));
        let log = Log::default();
        let ((called, calling), (go, going)) = (mpsc::channel(), mpsc::channel());
        let ear = Ear::new(5_000, vec![Answer::Heard, Answer::Hold { called, go: going }]);
        let scribe = Scribe::new(vec![], &log);
        let pipeline = Arc::new(Pipeline::spawn(library.clone(), engines(&ear, &scribe, &log)).unwrap());
        calling.recv_timeout(std::time::Duration::from_secs(10)).expect("a second window is heard");
        // As the session tidies up.
        assert!(library.erase_audio(None, || pipeline.let_go()));
        pipeline.changed();
        go.send(()).unwrap();
        settles(&pipeline);
        let erased = library.meeting(&meeting).unwrap();
        assert_eq!((erased.transcribed_ms, erased.transcript.len()), (None, 1), "what was heard before it");
        assert_eq!(summary(&library, &meeting).as_deref(), Some("Point de 00:00."));
        assert_eq!(*log.lock().unwrap(), ["hear", "hear", "write"]);
    }

    #[test]
    fn minutes_asked_again_of_a_meeting_whose_audio_is_erased_are_written_from_no_line_without_a_call() {
        let folder = tempfile::tempdir().unwrap();
        // Left with no line by an app before the core's, its audio erased since.
        let meeting = unheard(&folder, "recorded-1", MinutesState::Failed);
        let path = folder.path().join("recorded-1.notitia");
        let kept = format::decode(&fs::read(&path).unwrap()).unwrap();
        fs::write(&path, format::encode(&Meeting { audio_erased_at: Some(NOW), ..kept }).unwrap()).unwrap();
        fs::remove_file(folder.path().join("recorded-1.audio")).unwrap();
        let (_, library) = open(&folder);
        let log = Log::default();
        let (ear, scribe) = (Ear::new(30_000, vec![]), Scribe::new(vec![], &log));
        let pipeline = Arc::new(Pipeline::spawn(library.clone(), engines(&ear, &scribe, &log)).unwrap());
        assert!(library.change(Change::Rewrite { meeting: meeting.clone() }));
        pipeline.changed();
        settles(&pipeline);
        assert_eq!(summary(&library, &meeting).as_deref(), Some(""));
        assert!(log.lock().unwrap().is_empty(), "nothing left to hear, and nothing said to write");
    }

    #[test]
    fn a_writer_that_says_it_was_stopped_when_it_was_not_failed() {
        let folder = tempfile::tempdir().unwrap();
        let meeting = owed(&folder, "recorded-1", 1);
        let (_, library) = open(&folder);
        let log = Log::default();
        let scribe = Scribe::new(vec![Reply::Stopped], &log);
        let pipeline =
            Arc::new(Pipeline::spawn(library.clone(), engines(&Ear::new(30_000, vec![]), &scribe, &log)).unwrap());
        settles(&pipeline);
        assert_eq!(minutes(&library, &meeting), MinutesState::Failed);
        assert_eq!(scribe.prompts().len(), 1);
    }
}
