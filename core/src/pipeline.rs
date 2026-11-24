use std::collections::{HashMap, HashSet};
use std::io;
use std::panic::{self, AssertUnwindSafe};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, PoisonError};
use std::thread::{self, JoinHandle};

use crate::audio::AudioError;
use crate::engine::{Catalogue, EngineError, Engines, Hint, SpeechEngine, Stop};
use crate::library::{Library, lock};
use crate::meeting::{Change, MeetingId};
use crate::recorder::State;
use crate::speech::{self, Next, Windows};

// What the core does with a meeting once it is heard: transcribe it, then (G, I, J) write its minutes, index it and
// answer about it. One thread, so one model in memory at a time. docs/adr/0012-traitement.md.

// The meetings are said in French until the app speaks another language: it will then give it with `start`.
const LANGUAGE: &str = "fr";

// The work left, found from the state of the meetings alone: a launch finds what a killed app left, and no queue is
// kept on the disk.
#[cfg_attr(test, derive(Debug, PartialEq))]
enum Job {
    Transcribe(MeetingId),
}

// The most awaited first. `unheard`: the meetings the core still hears, the latest first. The one being recorded
// comes before them, its text awaited as it is said (US-26), then the latest, the one just stopped (US-39).
fn next(
    unheard: &[MeetingId],
    held: Option<&MeetingId>,
    resting: impl Fn(&MeetingId) -> bool,
    can_hear: bool,
) -> Option<Job> {
    if !can_hear {
        return None;
    }
    let live = held.filter(|meeting| unheard.contains(meeting));
    live.into_iter().chain(unheard).find(|meeting| !resting(meeting)).cloned().map(Job::Transcribe)
}

// What wakes the thread. Each wake moves the generation on: what rested at one is tried again at the next, so a wake
// that comes during a step is never lost, and nothing is tried twice in a row for nothing.
#[derive(Default)]
struct Signal {
    generation: u64,
    // Moved on by each offer of engines alone: an engine that said it was not available waits for the next.
    offers: u64,
    // The meeting the recorder holds, and the seconds it has recorded: its audio grows by whole seconds.
    held: Option<MeetingId>,
    seconds: u64,
    closing: bool,
    // The generation at which the thread found nothing to do.
    idle: Option<u64>,
}

#[derive(Default)]
struct Shared {
    signal: Mutex<Signal>,
    woken: Condvar,
    // Deleted, for as long as the app runs. Held while an audio is open, so that whoever deletes waits for it.
    forgotten: Mutex<HashSet<MeetingId>>,
    // The engine call under way, to stop it.
    call: Mutex<Option<(MeetingId, Arc<Stop>)>>,
}

impl Shared {
    // `change` says whether the thread has something new to look at.
    fn wake(&self, change: impl FnOnce(&mut Signal) -> bool) {
        let mut signal = lock(&self.signal);
        if change(&mut signal) {
            signal.generation += 1;
            self.woken.notify_all();
        }
    }

    fn wait<'a>(&self, signal: MutexGuard<'a, Signal>) -> MutexGuard<'a, Signal> {
        self.woken.wait(signal).unwrap_or_else(PoisonError::into_inner)
    }
}

// Tells the pipeline what the recorder holds, from the recorder's thread. Never waits for the pipeline.
#[derive(Clone)]
pub struct Waker(Arc<Shared>);

impl Waker {
    pub fn recorded(&self, state: &State) {
        let progress = state.progress();
        let held = progress.and_then(|progress| progress.meeting.as_ref());
        let seconds = progress.map_or(0, |progress| progress.recorded_ms / 1_000);
        self.0.wake(|signal| {
            let changed = signal.held.as_ref() != held || signal.seconds != seconds;
            if changed {
                (signal.held, signal.seconds) = (held.cloned(), seconds);
            }
            changed
        });
    }
}

// The one owner of the work on the meetings. Its state changes on its thread alone. The others only wake it.
pub struct Pipeline {
    shared: Arc<Shared>,
    thread: Option<JoinHandle<()>>,
}

impl Pipeline {
    pub fn spawn(library: Arc<Library>, engines: Engines) -> io::Result<Self> {
        let shared = Arc::new(Shared::default());
        let worker = Worker {
            library,
            engines,
            shared: shared.clone(),
            windows: None,
            resting: HashMap::new(),
            engine_resting: None,
            aside: HashSet::new(),
        };
        let thread = thread::Builder::new().name("notitia-pipeline".into()).spawn(move || worker.run())?;
        Ok(Self { shared, thread: Some(thread) })
    }

    pub fn waker(&self) -> Waker {
        Waker(self.shared.clone())
    }

    // Other engines, or the same back: what waited for one goes on.
    pub fn offered(&self) {
        self.shared.wake(|signal| {
            signal.offers += 1;
            true
        });
    }

    // Returns once no audio of these meetings is open, and stops the engine call under way on one of them: a system
    // that refuses to delete an open file (Windows) can then delete it, and nothing heard of it is kept.
    pub fn forget(&self, meetings: &[MeetingId]) {
        lock(&self.shared.forgotten).extend(meetings.iter().cloned());
        if let Some((meeting, stop)) = &*lock(&self.shared.call)
            && meetings.contains(meeting)
        {
            stop.stop();
        }
    }

    // Once the thread has done all it can for now.
    #[cfg(any(test, feature = "testing"))]
    pub fn settled(&self) {
        let mut signal = lock(&self.shared.signal);
        while !signal.closing && signal.idle != Some(signal.generation) {
            signal = self.shared.wait(signal);
        }
    }
}

impl Drop for Pipeline {
    // Waits for the engine call under way, stopped: an engine of the app can only be told once it returns. Nothing
    // is written after that, so another session may open the same folder.
    fn drop(&mut self) {
        self.shared.wake(|signal| {
            signal.closing = true;
            true
        });
        if let Some((_, stop)) = &*lock(&self.shared.call) {
            stop.stop();
        }
        // Dropped by its own listener, the thread cannot wait for itself: it ends once the listener returns.
        if let Some(thread) = self.thread.take().filter(|thread| thread.thread().id() != thread::current().id()) {
            let _ = thread.join();
        }
    }
}

// The windows of the meeting heard last, kept from one step to the next so that what was read is not read again:
// while it is recorded, a Wait leaves frames read that the next window needs.
struct Held {
    meeting: MeetingId,
    engine: String,
    longest_ms: u32,
    edge: u64,
    windows: Windows,
}

struct Worker {
    library: Arc<Library>,
    engines: Engines,
    shared: Arc<Shared>,
    // Taken out for a step, and put back only by a step that leaves them true to the meeting: a window taken from
    // them and not heard is never skipped.
    windows: Option<Held>,
    // At the end of what is recorded, until the next generation.
    resting: HashMap<MeetingId, u64>,
    // The offer whose engine said it was not available: asked again once the app offers again, not at each second
    // recorded, which would be a call a second to a gateway out of reach.
    engine_resting: Option<u64>,
    // Its file or its audio cannot be read, or the work on it failed: left until the next launch, nothing marked, as
    // the fault may pass.
    aside: HashSet<MeetingId>,
}

impl Worker {
    fn run(mut self) {
        loop {
            // Listed before the recorder's state is read: the recorder tells which meeting it holds before it begins
            // it, so a meeting listed and still recorded is never taken for one that ended.
            let unheard = self.library.kept(|meeting| meeting.transcribed_ms.is_some());
            let (generation, offers, held) = {
                let signal = lock(&self.shared.signal);
                if signal.closing {
                    return;
                }
                (signal.generation, signal.offers, signal.held.clone())
            };
            self.resting.retain(|_, at| *at == generation);
            let catalogue = lock(&self.engines).clone();
            let can_hear = catalogue.speech().is_some() && self.engine_resting != Some(offers);
            let resting = |meeting: &MeetingId| self.aside.contains(meeting) || self.resting.contains_key(meeting);
            let Some(job) = next(&unheard, held.as_ref(), resting, can_hear) else {
                self.rest(generation);
                continue;
            };
            let step = AssertUnwindSafe(|| self.step(&job, (generation, offers), held.as_ref(), &catalogue));
            if panic::catch_unwind(step).is_err() {
                // A fault of the core, of an engine or of the app's listener: this meeting waits for the next launch,
                // and the others go on.
                *lock(&self.shared.call) = None;
                self.windows = None;
                let Job::Transcribe(meeting) = job;
                self.aside.insert(meeting);
            }
        }
    }

    fn rest(&self, generation: u64) {
        let mut signal = lock(&self.shared.signal);
        signal.idle = Some(generation);
        self.shared.woken.notify_all();
        while signal.generation == generation && !signal.closing {
            signal = self.shared.wait(signal);
        }
    }

    // `at`: the generation and the offers the step began with.
    fn step(&mut self, job: &Job, at: (u64, u64), held: Option<&MeetingId>, catalogue: &Catalogue) {
        match job {
            Job::Transcribe(meeting) => {
                if let Some(engine) = catalogue.speech() {
                    self.transcribe(meeting, at, held != Some(meeting), engine);
                }
            }
        }
    }

    // One window: read, heard, then added to the meeting with the edge it reaches.
    fn transcribe(&mut self, id: &MeetingId, (generation, offers): (u64, u64), ended: bool, engine: &dyn SpeechEngine) {
        let Ok(meeting) = self.library.meeting(id) else { return self.set_aside(id) };
        // The app took it, or it ended, since it was listed.
        let Some(from_ms) = meeting.transcribed_ms else { return };
        let (name, longest_ms) = (&engine.about().name, engine.longest_ms());
        let same = |held: &Held| {
            held.meeting == *id && held.engine == *name && held.longest_ms == longest_ms && held.edge == from_ms
        };
        let mut held = self.windows.take().filter(same).unwrap_or_else(|| Held {
            meeting: id.clone(),
            engine: name.clone(),
            longest_ms,
            edge: from_ms,
            windows: Windows::at(from_ms, longest_ms),
        });
        let found = {
            let forgotten = lock(&self.shared.forgotten);
            if forgotten.contains(id) {
                drop(forgotten);
                return self.set_aside(id);
            }
            // Closed before the engine is called, which may take seconds: a delete waits for no engine.
            self.library.audio(id).and_then(|mut audio| held.windows.next(&mut audio, ended))
        };
        let (lines, until_ms) = match found {
            Ok(Next::Window(window)) => {
                let stop = self.call(id);
                let hint = Hint { language: LANGUAGE, previous: &speech::previous(&meeting.transcript) };
                let heard = engine.transcribe(&window.sound, &hint, &stop);
                *lock(&self.shared.call) = None;
                let lines = match heard {
                    Ok(segments) => {
                        let last = meeting.transcript.last().and_then(|line| line.text.as_deref());
                        speech::lines(&window, segments, last)
                    }
                    // Cut short by a delete or a close: whatever the engine says of it, the window is heard again.
                    Err(_) if stop.stopped() => return,
                    Err(EngineError::Unavailable) => {
                        self.engine_resting = Some(offers);
                        return;
                    }
                    // Stopped when nobody stopped it: asking again would ask for ever.
                    Err(EngineError::Failed | EngineError::Stopped) => vec![speech::unheard(&window)],
                };
                (lines, Some(window.until_ms))
            }
            // Nothing left to hear: the end of the audio, a recording that failed before its first second, or an
            // audio deleted since (D).
            Ok(Next::End) | Err(AudioError::Absent) if ended => (Vec::new(), None),
            Err(_) if ended => return self.set_aside(id),
            // While it is recorded: more once there is more sound. An audio not made yet, or read as a second was
            // being written, is read again with the next second. An audio that ended never waits: should it, it is
            // not marked either.
            Ok(Next::Wait | Next::End) | Err(_) => {
                self.resting.insert(id.clone(), generation);
                self.windows = Some(held);
                return;
            }
        };
        let heard = Change::Heard { meeting: id.clone(), lines, from_ms, until_ms };
        // Refused only if the meeting changed under the step: deleted, taken by the app, or its file unreadable.
        // Trying again could call the engine on the same window for ever.
        if !self.library.change(heard) {
            return self.set_aside(id);
        }
        if let Some(edge) = until_ms {
            held.edge = edge;
            self.windows = Some(held);
        }
    }

    // The stop of a call, known to whoever deletes or closes before the call starts.
    fn call(&self, id: &MeetingId) -> Arc<Stop> {
        let stop = Arc::new(Stop::default());
        *lock(&self.shared.call) = Some((id.clone(), stop.clone()));
        // Read once the stop is known: a delete or a close that came just before is not missed.
        if lock(&self.shared.forgotten).contains(id) || lock(&self.shared.signal).closing {
            stop.stop();
        }
        stop
    }

    fn set_aside(&mut self, id: &MeetingId) {
        self.aside.insert(id.clone());
    }
}

#[cfg(test)]
mod tests {
    use std::collections::VecDeque;
    use std::fs;
    use std::sync::mpsc;

    use tempfile::TempDir;
    use time::macros::utc_datetime;
    use time::{Duration, UtcDateTime};

    use super::*;
    use crate::engine::{About, Engine, Segment};
    use crate::library::{Event, LibraryError, Listener};
    use crate::meeting::testing::id;
    use crate::meeting::{MinutesDraft, MinutesState, TranscriptLine};
    use crate::recorder::Progress;
    use crate::secret::testing::key;
    use crate::speech::testing::{frames, quiet, talk};

    const NOW: UtcDateTime = utc_datetime!(2026-11-24 8:00);

    // What one call of the engine does.
    enum Answer {
        Heard,
        Fail,
        Unavailable,
        Panic,
        // Says it was stopped when it was not.
        Stopped,
        // Says it was called, then waits for the test before hearing: a call that takes its time. Once stopped, says
        // `stopped` as an engine of the app does.
        Hold { called: mpsc::Sender<()>, go: mpsc::Receiver<()> },
        // As Hold, but once stopped it says it failed, as a model cut short may.
        HoldThenFail { called: mpsc::Sender<()>, go: mpsc::Receiver<()> },
    }

    #[derive(Clone, Debug, PartialEq)]
    struct Call {
        samples: usize,
        previous: String,
        stopped: bool,
    }

    // Says what it was given: the same sound gives the same text, wherever it is heard from.
    struct Ear {
        about: About,
        longest_ms: u32,
        // One a call, then Heard once they run out.
        answers: Mutex<VecDeque<Answer>>,
        calls: Mutex<Vec<Call>>,
    }

    impl Ear {
        fn new(longest_ms: u32, answers: Vec<Answer>) -> Arc<Self> {
            let about = About { name: format!("ear-{longest_ms}"), remote: false };
            Arc::new(Self { about, longest_ms, answers: Mutex::new(answers.into()), calls: Mutex::default() })
        }

        fn calls(&self) -> Vec<Call> {
            self.calls.lock().unwrap().clone()
        }
    }

    impl Engine for Ear {
        fn about(&self) -> &About {
            &self.about
        }
    }

    impl SpeechEngine for Ear {
        fn longest_ms(&self) -> u32 {
            self.longest_ms
        }

        fn transcribe(&self, sound: &[i16], hint: &Hint, stop: &Stop) -> Result<Vec<Segment>, EngineError> {
            let answer = self.answers.lock().unwrap().pop_front().unwrap_or(Answer::Heard);
            if let Answer::Hold { called, go } | Answer::HoldThenFail { called, go } = &answer {
                called.send(()).unwrap();
                let _ = go.recv();
            }
            let call = Call { samples: sound.len(), previous: hint.previous.into(), stopped: stop.stopped() };
            self.calls.lock().unwrap().push(call);
            match answer {
                Answer::Heard | Answer::Hold { .. } if stop.stopped() => Err(EngineError::Stopped),
                Answer::HoldThenFail { .. } if stop.stopped() => Err(EngineError::Failed),
                Answer::Heard | Answer::Hold { .. } | Answer::HoldThenFail { .. } => Ok(heard(sound)),
                Answer::Fail => Err(EngineError::Failed),
                Answer::Unavailable => Err(EngineError::Unavailable),
                Answer::Panic => panic!("a faulty engine"),
                Answer::Stopped => Err(EngineError::Stopped),
            }
        }
    }

    fn heard(sound: &[i16]) -> Vec<Segment> {
        let sum: i64 = sound.iter().map(|sample| i64::from(*sample)).sum();
        vec![Segment { from_ms: 0, to_ms: (sound.len() / 16) as u32, text: format!("{} {sum}.", sound.len()) }]
    }

    #[derive(Default)]
    struct Told(Mutex<Vec<Event>>);

    impl Listener for Told {
        fn on_event(&self, event: &Event) {
            self.0.lock().unwrap().push(event.clone());
        }
    }

    impl Told {
        // How far the meeting was heard, each time it changed.
        fn edges(&self, meeting: &MeetingId) -> Vec<Option<u64>> {
            let changed = |event: &Event| match event {
                Event::Changed { meeting: changed, .. } if changed.id == *meeting => Some(changed.transcribed_ms),
                _ => None,
            };
            self.0.lock().unwrap().iter().filter_map(changed).collect()
        }
    }

    fn open(folder: &TempDir) -> (Arc<Told>, Arc<Library>) {
        let told = Arc::new(Told::default());
        let library = Library::open_at(folder.path().into(), Some(key(1)), told.clone(), Box::new(|| NOW)).unwrap();
        (told, Arc::new(library))
    }

    fn engines(ear: &Arc<Ear>) -> Engines {
        Arc::new(Mutex::new(Arc::new(Catalogue::new(vec![ear.clone()], vec![], vec![]))))
    }

    fn begin(library: &Library, name: &str, minute: i64) -> MeetingId {
        let meeting = id(name);
        let started_at = utc_datetime!(2026-11-24 9:00) + Duration::minutes(minute);
        library.apply(Change::Begin { id: meeting.clone(), started_at, offset_minutes: 60 });
        meeting
    }

    // Begun `minute` after nine, the later the higher in the list, and recorded whole.
    fn recorded(library: &Library, name: &str, minute: i64, sound: &[i16]) -> MeetingId {
        let meeting = begin(library, name, minute);
        let mut writer = library.record(&meeting, 1).unwrap();
        frames(sound).iter().for_each(|frame| writer.write(&[*frame]).unwrap());
        writer.finish().unwrap();
        meeting
    }

    fn recording(meeting: &MeetingId, recorded_ms: u64) -> State {
        let progress = Progress { meeting: Some(meeting.clone()), started_at: NOW, recorded_ms };
        State::Recording { progress, level: 0.0 }
    }

    // Sentences, a pause after each.
    fn sentences(talks_ms: &[u64]) -> Vec<i16> {
        talks_ms.iter().flat_map(|ms| [talk(*ms), quiet(2_500)].concat()).collect()
    }

    // What an engine that hears every window adds to the meeting: the transcript the pipeline must end with.
    fn expected(library: &Library, meeting: &MeetingId, longest_ms: u32) -> Vec<TranscriptLine> {
        let mut windows = Windows::at(0, longest_ms);
        let mut lines: Vec<TranscriptLine> = Vec::new();
        while let Next::Window(window) = windows.next(&mut library.audio(meeting).unwrap(), true).unwrap() {
            let last = lines.last().and_then(|line| line.text.clone());
            lines.extend(speech::lines(&window, heard(&window.sound), last.as_deref()));
        }
        lines
    }

    fn transcript(library: &Library, meeting: &MeetingId) -> Vec<TranscriptLine> {
        library.meeting(meeting).unwrap().transcript
    }

    #[test]
    fn the_work_comes_in_order() {
        let (one, two, three) = (id("recorded-1"), id("recorded-2"), id("recorded-3"));
        let unheard = [three.clone(), two.clone(), one.clone()];
        let none = |_: &MeetingId| false;
        let transcribe = |meeting: &MeetingId| Some(Job::Transcribe(meeting.clone()));
        assert_eq!(next(&unheard, None, none, true), transcribe(&three), "the latest first");
        assert_eq!(next(&unheard, Some(&one), none, true), transcribe(&one), "the one being recorded first");
        assert_eq!(next(&unheard, None, |meeting| *meeting == three, true), transcribe(&two), "one at rest is skipped");
        let resting = |meeting: &MeetingId| *meeting == one;
        assert_eq!(next(&unheard, Some(&one), resting, true), transcribe(&three), "recorded, at rest");
        assert_eq!(next(&unheard, Some(&id("recorded-9")), none, true), transcribe(&three), "recorded, not heard");
        assert_eq!(next(&unheard, None, |_| true, true), None);
        assert_eq!(next(&unheard, Some(&one), none, false), None, "no engine to hear with");
        assert_eq!(next(&[], None, none, true), None);
    }

    #[test]
    fn a_recorded_meeting_is_heard_window_by_window_then_is_whole() {
        let folder = tempfile::tempdir().unwrap();
        let (told, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sentences(&[3_000, 2_000, 4_000]));
        let whole = expected(&library, &meeting, 30_000);
        assert_eq!(whole.len(), 3);
        let ear = Ear::new(30_000, vec![]);
        let pipeline = Pipeline::spawn(library.clone(), engines(&ear)).unwrap();
        pipeline.settled();
        let heard = library.meeting(&meeting).unwrap();
        assert_eq!(heard.transcript, whole);
        assert!(whole[1].at_ms > 5_000, "dated from the start of the meeting");
        assert_eq!((heard.transcribed_ms, heard.minutes), (None, MinutesState::Writing));
        // The edge moved on window by window, each time told.
        let edges = told.edges(&meeting);
        assert_eq!(edges.len(), 5, "{edges:?}");
        assert_eq!((edges[0], edges[4]), (Some(0), None));
        assert!(edges[1..4].windows(2).all(|pair| pair[0] < pair[1]) && edges[1] > Some(0));
        // Each window is told the end of what was said before it.
        let previous: Vec<String> = ear.calls().into_iter().map(|call| call.previous).collect();
        let said = |rank: usize| whole[rank].text.clone().unwrap();
        assert_eq!(previous, [String::new(), said(0), format!("{} {}", said(0), said(1))]);
    }

    #[test]
    fn taken_up_at_each_edge_the_text_is_the_one_heard_without_a_stop() {
        // Sentences, then someone who never stops: cuts in pauses and in the middle of speech.
        let sound = [sentences(&[4_000]), talk(70_000), sentences(&[3_000])].concat();
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sound);
        let whole = expected(&library, &meeting, 30_000);
        assert_eq!(whole.len(), 4);
        let mut windows = Windows::at(0, 30_000);
        let mut forced = 0;
        while let Next::Window(window) = windows.next(&mut library.audio(&meeting).unwrap(), true).unwrap() {
            forced += usize::from(window.overlap_ms > 0);
        }
        assert_eq!(forced, 2);
        drop(library);
        let mut launches = 0;
        loop {
            let (_, library) = open(&folder);
            if library.meeting(&meeting).unwrap().transcribed_ms.is_none() {
                assert_eq!(transcript(&library, &meeting), whole);
                break;
            }
            // One window a launch: the engine is gone after it.
            let ear = Ear::new(30_000, vec![Answer::Heard, Answer::Unavailable]);
            let pipeline = Pipeline::spawn(library.clone(), engines(&ear)).unwrap();
            pipeline.settled();
            launches += 1;
            assert!(launches <= whole.len());
        }
        assert_eq!(launches, whole.len());
    }

    #[test]
    fn the_text_comes_while_the_meeting_is_recorded_and_ends_with_it() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let ear = Ear::new(30_000, vec![]);
        let pipeline = Pipeline::spawn(library.clone(), engines(&ear)).unwrap();
        let waker = pipeline.waker();
        // As the recorder does: the meeting is told held before it is begun.
        waker.recorded(&recording(&id("recorded-1"), 0));
        let meeting = begin(&library, "recorded-1", 0);
        let mut writer = library.record(&meeting, 1).unwrap();
        let mut recorded_ms = 0;
        for (sound, lines) in [(sentences(&[3_000]), 1), (talk(3_000), 1)] {
            frames(&sound).iter().for_each(|frame| writer.write(&[*frame]).unwrap());
            recorded_ms += sound.len() as u64 / 16;
            waker.recorded(&recording(&meeting, recorded_ms));
            pipeline.settled();
            // Someone still speaking: nothing to cut yet.
            let heard = library.meeting(&meeting).unwrap();
            assert_eq!((heard.transcript.len(), heard.transcribed_ms.is_some()), (lines, true));
        }
        frames(&quiet(500)).iter().for_each(|frame| writer.write(&[*frame]).unwrap());
        writer.finish().unwrap();
        waker.recorded(&State::Idle);
        pipeline.settled();
        let heard = library.meeting(&meeting).unwrap();
        assert_eq!((heard.transcript, heard.transcribed_ms), (expected(&library, &meeting, 30_000), None));
        assert_eq!(ear.calls().len(), 2);
    }

    #[test]
    fn the_meeting_being_recorded_is_heard_before_the_others() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        // Begun earlier than the meeting left to hear, which the list puts first.
        let live = begin(&library, "recorded-1", 0);
        let mut writer = library.record(&live, 1).unwrap();
        frames(&sentences(&[5_000])).iter().for_each(|frame| writer.write(&[*frame]).unwrap());
        let backlog = recorded(&library, "recorded-2", 30, &sentences(&[2_000, 2_000]));
        // No engine until the pipeline knows what is recorded.
        let engines: Engines = Arc::default();
        let pipeline = Pipeline::spawn(library.clone(), engines.clone()).unwrap();
        pipeline.waker().recorded(&recording(&live, 7_500));
        let ear = Ear::new(30_000, vec![]);
        *lock(&engines) = Arc::new(Catalogue::new(vec![ear.clone()], vec![], vec![]));
        pipeline.offered();
        pipeline.settled();
        let lengths: Vec<bool> = ear.calls().iter().map(|call| call.samples > 16 * 4_000).collect();
        assert_eq!(lengths, [true, false, false]);
        assert_eq!(library.meeting(&backlog).unwrap().transcribed_ms, None);
        assert_eq!(transcript(&library, &live).len(), 1);
        assert!(library.meeting(&live).unwrap().transcribed_ms.is_some());
    }

    #[test]
    fn a_window_the_engine_fails_says_where_and_the_next_ones_are_heard() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sentences(&[3_000, 3_000]));
        let whole = expected(&library, &meeting, 30_000);
        let ear = Ear::new(30_000, vec![Answer::Fail]);
        Pipeline::spawn(library.clone(), engines(&ear)).unwrap().settled();
        let unheard = TranscriptLine { text: None, ..whole[0].clone() };
        assert_eq!(transcript(&library, &meeting), [unheard, whole[1].clone()]);
        assert_eq!(library.meeting(&meeting).unwrap().transcribed_ms, None);
    }

    #[test]
    fn an_engine_not_available_marks_nothing_and_waits_for_the_next_wake() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sentences(&[3_000, 3_000]));
        let ear = Ear::new(30_000, vec![Answer::Unavailable]);
        let pipeline = Pipeline::spawn(library.clone(), engines(&ear)).unwrap();
        pipeline.settled();
        pipeline.settled();
        let waiting = library.meeting(&meeting).unwrap();
        assert_eq!(
            (waiting.transcribed_ms, waiting.minutes, waiting.transcript),
            (Some(0), MinutesState::Writing, vec![])
        );
        assert_eq!(ear.calls().len(), 1, "never asked again in a loop");
        pipeline.offered();
        pipeline.settled();
        assert_eq!(transcript(&library, &meeting), expected(&library, &meeting, 30_000));
        assert_eq!(ear.calls().len(), 3);
    }

    fn held_call() -> (Answer, mpsc::Receiver<()>, mpsc::Sender<()>) {
        let ((called, calling), (go, going)) = (mpsc::channel(), mpsc::channel());
        (Answer::Hold { called, go: going }, calling, go)
    }

    #[test]
    fn a_call_stopped_by_a_close_never_marks_its_window_lost_whatever_the_engine_says() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sentences(&[3_000, 3_000]));
        let ((called, calling), (go, going)) = (mpsc::channel(), mpsc::channel());
        let ear = Ear::new(30_000, vec![Answer::HoldThenFail { called, go: going }]);
        let pipeline = Pipeline::spawn(library.clone(), engines(&ear)).unwrap();
        calling.recv().unwrap();
        let shared = pipeline.shared.clone();
        let closing = std::thread::spawn(move || drop(pipeline));
        while !lock(&shared.call).as_ref().is_some_and(|(_, stop)| stop.stopped()) {
            std::thread::yield_now();
        }
        go.send(()).unwrap();
        closing.join().unwrap();
        assert_eq!(library.meeting(&meeting).unwrap().transcribed_ms, Some(0));
        Pipeline::spawn(library.clone(), engines(&Ear::new(30_000, vec![]))).unwrap().settled();
        assert_eq!(transcript(&library, &meeting), expected(&library, &meeting, 30_000));
    }

    #[test]
    fn a_meeting_deleted_during_a_call_is_stopped_and_does_not_come_back() {
        let folder = tempfile::tempdir().unwrap();
        let (told, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sentences(&[3_000, 3_000]));
        let (hold, calling, go) = held_call();
        let ear = Ear::new(30_000, vec![hold]);
        let pipeline = Pipeline::spawn(library.clone(), engines(&ear)).unwrap();
        calling.recv().unwrap();
        pipeline.forget(std::slice::from_ref(&meeting));
        library.apply(Change::Delete { meetings: vec![meeting.clone()] });
        // Its audio is not held open by the call: every system deletes it.
        let files =
            fs::read_dir(folder.path()).unwrap().filter_map(|entry| entry.unwrap().file_name().into_string().ok());
        assert_eq!(files.filter(|name| name.starts_with("recorded-1")).count(), 0);
        let changes = told.edges(&meeting).len();
        go.send(()).unwrap();
        pipeline.settled();
        assert!(ear.calls()[0].stopped);
        assert_eq!((ear.calls().len(), told.edges(&meeting).len()), (1, changes));
        assert_eq!(library.meeting(&meeting).err(), Some(LibraryError::NotFound));
    }

    #[test]
    fn closing_waits_for_the_call_under_way_stopped_and_the_next_launch_resumes() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sentences(&[3_000, 3_000]));
        let (hold, calling, go) = held_call();
        let ear = Ear::new(30_000, vec![hold]);
        let pipeline = Pipeline::spawn(library.clone(), engines(&ear)).unwrap();
        calling.recv().unwrap();
        let shared = pipeline.shared.clone();
        let closing = std::thread::spawn(move || drop(pipeline));
        while !lock(&shared.call).as_ref().is_some_and(|(_, stop)| stop.stopped()) {
            std::thread::yield_now();
        }
        go.send(()).unwrap();
        closing.join().unwrap();
        assert!(ear.calls()[0].stopped);
        let left = library.meeting(&meeting).unwrap();
        assert_eq!((left.transcribed_ms, left.transcript), (Some(0), vec![]));
        let ear = Ear::new(30_000, vec![]);
        Pipeline::spawn(library.clone(), engines(&ear)).unwrap().settled();
        assert_eq!(transcript(&library, &meeting), expected(&library, &meeting, 30_000));
    }

    #[test]
    fn a_meeting_the_app_transcribes_itself_is_never_heard_by_the_core() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sentences(&[3_000]));
        library.apply(Change::Transcribed {
            meeting: meeting.clone(),
            transcript: vec![],
            minutes: MinutesDraft::Writing,
        });
        let ear = Ear::new(30_000, vec![]);
        Pipeline::spawn(library.clone(), engines(&ear)).unwrap().settled();
        assert_eq!(ear.calls(), []);
        assert_eq!(library.meeting(&meeting).unwrap().minutes, MinutesState::Writing);
    }

    #[test]
    fn an_engine_that_panics_sets_its_meeting_aside_and_the_others_are_heard() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let earlier = recorded(&library, "recorded-1", 0, &sentences(&[3_000]));
        let latest = recorded(&library, "recorded-2", 30, &sentences(&[3_000]));
        let ear = Ear::new(30_000, vec![Answer::Panic]);
        let pipeline = Pipeline::spawn(library.clone(), engines(&ear)).unwrap();
        pipeline.settled();
        assert_eq!(library.meeting(&latest).unwrap().transcribed_ms, Some(0));
        assert_eq!(transcript(&library, &earlier), expected(&library, &earlier, 30_000));
        pipeline.offered();
        pipeline.settled();
        assert_eq!(library.meeting(&latest).unwrap().transcribed_ms, Some(0), "aside until the next launch");
    }

    #[test]
    fn a_meeting_without_audio_is_whole_once_it_is_no_longer_recorded() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let failed = begin(&library, "recorded-1", 0);
        let starting = begin(&library, "recorded-2", 30);
        let ear = Ear::new(30_000, vec![]);
        let pipeline = Pipeline::spawn(library.clone(), engines(&ear)).unwrap();
        pipeline.waker().recorded(&recording(&starting, 0));
        pipeline.settled();
        let whole = library.meeting(&failed).unwrap();
        assert_eq!(
            (whole.transcribed_ms, whole.transcript, whole.minutes),
            (None, vec![], MinutesState::from(MinutesDraft::owed(&[])))
        );
        let waiting = library.meeting(&starting).unwrap();
        assert_eq!((waiting.transcribed_ms, waiting.minutes), (Some(0), MinutesState::Writing));
        assert_eq!(ear.calls(), []);
    }

    #[test]
    fn the_windows_follow_the_engine_offered() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &[talk(50_000), quiet(2_500)].concat());
        let long = Ear::new(30_000, vec![Answer::Heard, Answer::Unavailable]);
        let engines = engines(&long);
        let pipeline = Pipeline::spawn(library.clone(), engines.clone()).unwrap();
        pipeline.settled();
        let short = Ear::new(5_000, vec![]);
        *lock(&engines) = Arc::new(Catalogue::new(vec![short.clone()], vec![], vec![]));
        pipeline.offered();
        pipeline.settled();
        assert!(long.calls()[0].samples > 16 * 25_000);
        let shorts: Vec<usize> = short.calls().iter().map(|call| call.samples).collect();
        assert!(shorts.len() >= 4 && shorts.iter().all(|samples| *samples <= 16 * 5_000), "{shorts:?}");
        let heard = library.meeting(&meeting).unwrap();
        assert_eq!((heard.transcript.len(), heard.transcribed_ms), (1 + shorts.len(), None));
    }

    #[test]
    fn a_window_the_meetings_refuse_sets_its_meeting_aside_until_the_next_launch() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sentences(&[3_000, 3_000]));
        let path = folder.path().join("recorded-1.notitia");
        let file = fs::read(&path).unwrap();
        let (hold, calling, go) = held_call();
        let ear = Ear::new(30_000, vec![hold]);
        let pipeline = Pipeline::spawn(library.clone(), engines(&ear)).unwrap();
        calling.recv().unwrap();
        // Its lines cannot be read to add the window to them.
        fs::write(&path, "{\"version\":").unwrap();
        go.send(()).unwrap();
        pipeline.settled();
        fs::write(&path, &file).unwrap();
        pipeline.offered();
        pipeline.settled();
        assert_eq!(ear.calls().len(), 1, "never the same window again and again");
        assert_eq!(library.meeting(&meeting).unwrap().transcribed_ms, Some(0));
        drop((pipeline, library));
        let (_, library) = open(&folder);
        Pipeline::spawn(library.clone(), engines(&ear)).unwrap().settled();
        assert_eq!(transcript(&library, &meeting), expected(&library, &meeting, 30_000));
    }

    // Fails rather than hangs when the thread never rests.
    fn settles(pipeline: &Arc<Pipeline>) {
        let (rested, resting) = mpsc::channel();
        let waiting = pipeline.clone();
        std::thread::spawn(move || {
            waiting.settled();
            let _ = rested.send(());
        });
        resting.recv_timeout(std::time::Duration::from_secs(10)).expect("the thread works in a loop");
    }

    #[test]
    fn a_meeting_forgotten_is_never_worked_on_again() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sentences(&[3_000]));
        let engines: Engines = Arc::default();
        let pipeline = Arc::new(Pipeline::spawn(library.clone(), engines.clone()).unwrap());
        pipeline.forget(std::slice::from_ref(&meeting));
        let ear = Ear::new(30_000, vec![]);
        *lock(&engines) = Arc::new(Catalogue::new(vec![ear.clone()], vec![], vec![]));
        pipeline.offered();
        settles(&pipeline);
        assert_eq!(ear.calls(), []);
    }

    #[test]
    fn an_engine_that_says_it_was_stopped_when_it_was_not_failed_its_window() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let meeting = recorded(&library, "recorded-1", 0, &sentences(&[3_000, 3_000]));
        let whole = expected(&library, &meeting, 30_000);
        let ear = Ear::new(30_000, vec![Answer::Stopped]);
        settles(&Arc::new(Pipeline::spawn(library.clone(), engines(&ear)).unwrap()));
        let unheard = TranscriptLine { text: None, ..whole[0].clone() };
        assert_eq!(transcript(&library, &meeting), [unheard, whole[1].clone()]);
    }

    #[test]
    fn an_audio_that_cannot_be_read_while_it_is_recorded_is_read_again_with_the_next_second() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let ear = Ear::new(30_000, vec![]);
        let pipeline = Pipeline::spawn(library.clone(), engines(&ear)).unwrap();
        let waker = pipeline.waker();
        waker.recorded(&recording(&id("recorded-1"), 0));
        let meeting = begin(&library, "recorded-1", 0);
        let mut writer = library.record(&meeting, 1).unwrap();
        frames(&sentences(&[3_000])).iter().for_each(|frame| writer.write(&[*frame]).unwrap());
        // Its mark changed for a moment: the audio reads as a newer app's.
        let path = folder.path().join("recorded-1.audio");
        let sealed = fs::read(&path).unwrap();
        fs::write(&path, [b"XTTAUDIO".as_slice(), &sealed[8..]].concat()).unwrap();
        waker.recorded(&recording(&meeting, 5_000));
        pipeline.settled();
        fs::write(&path, &sealed).unwrap();
        waker.recorded(&recording(&meeting, 6_000));
        pipeline.settled();
        assert_eq!(transcript(&library, &meeting).len(), 1);
    }

    #[test]
    fn an_engine_not_available_is_not_asked_again_at_each_second_recorded() {
        let folder = tempfile::tempdir().unwrap();
        let (_, library) = open(&folder);
        let ear = Ear::new(30_000, vec![Answer::Unavailable]);
        let pipeline = Pipeline::spawn(library.clone(), engines(&ear)).unwrap();
        let waker = pipeline.waker();
        waker.recorded(&recording(&id("recorded-1"), 0));
        let meeting = begin(&library, "recorded-1", 0);
        let mut writer = library.record(&meeting, 1).unwrap();
        let sound = sentences(&[3_000, 3_000]);
        for (second, sound) in sound.chunks(16_000).enumerate() {
            frames(sound).iter().for_each(|frame| writer.write(&[*frame]).unwrap());
            waker.recorded(&recording(&meeting, (second as u64 + 1) * 1_000));
            pipeline.settled();
        }
        assert_eq!(ear.calls().len(), 1);
        pipeline.offered();
        pipeline.settled();
        assert_eq!(transcript(&library, &meeting).len(), 2);
    }

    #[test]
    fn the_pipeline_is_shared_by_the_threads_of_an_app() {
        fn shared<T: Send + Sync>() {}
        shared::<Pipeline>();
        shared::<Waker>();
    }
}
