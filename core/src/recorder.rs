use std::collections::VecDeque;
use std::io;
use std::panic::{self, AssertUnwindSafe};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread::{self, JoinHandle};

use serde::{Deserialize, Serialize};
use time::UtcDateTime;

use crate::audio::{self, AudioError, FRAME_MS, FRAME_SAMPLES, FRAMES_PER_SECOND, Frame, SAMPLE_RATE};
use crate::library::Library;
use crate::meeting::{Change, MeetingId, instant};

const BYTES_PER_SECOND: usize = SAMPLE_RATE * 2;
// The sound that may wait for the recorder, per track: what a slow disk is given before the recording is cut.
const QUEUE_SECONDS: usize = 10;
// How far a track may run ahead of another, for the same reason: past it the recording is cut, rather than hold
// the sound of a whole meeting in memory.
const AHEAD_SAMPLES: usize = QUEUE_SECONDS * SAMPLE_RATE;
// In frames: the timer and the level move every 100 ms, the meeting every 10 s.
const TOLD_EVERY: u64 = 5;
const LASTED_EVERY: u64 = 10 * FRAMES_PER_SECOND as u64;
// The ear hears decibels, not amplitude: -50 dBFS (a quiet room) gives 0, -6 dBFS (a shout close by) gives 1.
const FLOOR_DB: f64 = -50.0;
const LOUD_DB: f64 = -6.0;

// What an app asks of the recorder. Crosses the bridge as text, read here as it stands:
// tests/fixtures/bridge/recorder.json holds one of each kind.
#[derive(Deserialize)]
#[cfg_attr(test, derive(Debug, PartialEq))]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum Command {
    // One track on a phone, two on a computer (docs/adr/0005-audio.md). The offset is how far ahead of UTC the
    // device is: the core never asks the system for its time zone.
    Start { tracks: u8, offset_minutes: i16 },
    Pause,
    Resume,
    Stop,
    // The microphone gives no more sound: refused, taken by another app, or failed.
    Cut { problem: Problem },
    // The failure was shown.
    Dismiss,
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Problem {
    Denied,
    Busy,
    Unavailable,
    // No key: without one, no audio is written.
    Storage,
}

// The whole state of the recorder, told at each change and every 100 ms of sound: an app shows it as it is.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum State {
    Idle,
    Failed {
        problem: Problem,
    },
    Recording {
        #[serde(flatten)]
        progress: Progress,
        // From 0 to 1: the loudest 20 ms since the last time it was told.
        level: f32,
    },
    Paused(Progress),
    // Sound was heard, then the microphone, the disk or the queue failed: resuming goes on with the same audio.
    Interrupted(Progress),
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Progress {
    // Made at the first sound: a recording refused before it leaves no meeting behind.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub meeting: Option<MeetingId>,
    #[serde(with = "instant")]
    pub started_at: UtcDateTime,
    // The audio kept, pauses left out: the duration the meeting will have.
    pub recorded_ms: u64,
}

impl State {
    fn progress(&self) -> Option<&Progress> {
        match self {
            State::Recording { progress, .. } | State::Paused(progress) | State::Interrupted(progress) => {
                Some(progress)
            }
            State::Idle | State::Failed { .. } => None,
        }
    }
}

enum Message {
    Command(Command),
    Sound { track: u8, pcm: Vec<u8> },
    // The queue was full: what was heard after this point is gone.
    Lost,
    // Answered once the recorder holds none of these meetings.
    Forget { meetings: Vec<MeetingId>, done: Sender<()> },
}

// The sound waiting for the recorder, counted by the microphone's thread without a lock.
struct Queue {
    bytes: AtomicUsize,
    bound: AtomicUsize,
    lost: AtomicBool,
}

// The one owner of a recording. Commands and sound reach its thread through one queue, in the order they were
// given: its state changes there alone, and the microphone never waits for the disk.
pub struct Recorder {
    // None once dropped, which ends the thread.
    sender: Option<Sender<Message>>,
    queue: Arc<Queue>,
    thread: Option<JoinHandle<()>>,
}

impl Recorder {
    // `tell` is called on the recorder's thread. It may read a meeting, it must never wait for the recorder.
    pub fn spawn(library: Arc<Library>, tell: impl Fn(&State) + Send + 'static) -> io::Result<Self> {
        Self::bounded(library, tell, QUEUE_SECONDS * BYTES_PER_SECOND)
    }

    fn bounded(
        library: Arc<Library>,
        tell: impl Fn(&State) + Send + 'static,
        bytes_per_track: usize,
    ) -> io::Result<Self> {
        let queue = Arc::new(Queue {
            bytes: AtomicUsize::new(0),
            bound: AtomicUsize::new(bytes_per_track),
            lost: AtomicBool::new(false),
        });
        let (sender, messages) = mpsc::channel();
        let actor = Actor {
            library,
            queue: queue.clone(),
            bytes_per_track,
            tell: Box::new(tell),
            state: State::Idle,
            take: None,
        };
        let thread = thread::Builder::new().name("notitia-recorder".into()).spawn(move || actor.run(messages))?;
        Ok(Self { sender: Some(sender), queue, thread: Some(thread) })
    }

    pub fn apply(&self, command: Command) {
        self.send(Message::Command(command));
    }

    // 16-bit little-endian samples at 16 kHz, as the microphone reads them: an odd byte waits for the next read.
    // Returns at once, without a lock, and keeps the bytes it is given rather than copy them. Sound that finds the
    // queue full is thrown away and the recording is interrupted, so a meeting never has a silent hole nobody was
    // told about.
    pub fn hear(&self, track: u8, pcm: Vec<u8>) {
        let queue = &self.queue;
        if pcm.is_empty() || queue.lost.load(Ordering::Acquire) {
            return;
        }
        let waiting = queue.bytes.fetch_add(pcm.len(), Ordering::AcqRel) + pcm.len();
        if waiting > queue.bound.load(Ordering::Acquire) {
            queue.bytes.fetch_sub(pcm.len(), Ordering::AcqRel);
            if !queue.lost.swap(true, Ordering::AcqRel) {
                self.send(Message::Lost);
            }
            return;
        }
        self.send(Message::Sound { track, pcm });
    }

    // Waits until the recorder lets go of the audio of these meetings: a system that refuses to delete an open
    // file (Windows) can then delete it.
    pub fn forget(&self, meetings: &[MeetingId]) {
        let (done, answered) = mpsc::channel();
        self.send(Message::Forget { meetings: meetings.to_vec(), done });
        // An error: the thread is gone, and holds nothing.
        let _ = answered.recv();
    }

    fn send(&self, message: Message) {
        if let Some(sender) = &self.sender {
            let _ = sender.send(message);
        }
    }
}

impl Drop for Recorder {
    // What was heard is written, but the audio is not finished: a killed app leaves it so, and the next launch
    // takes it up.
    fn drop(&mut self) {
        self.sender = None;
        // Dropped by its own listener, the thread cannot wait for itself: it ends once the listener returns.
        if let Some(thread) = self.thread.take().filter(|thread| thread.thread().id() != thread::current().id()) {
            let _ = thread.join();
        }
    }
}

struct Actor {
    library: Arc<Library>,
    queue: Arc<Queue>,
    bytes_per_track: usize,
    tell: Box<dyn Fn(&State) + Send>,
    state: State,
    take: Option<Take>,
}

// What a recording holds besides its state.
struct Take {
    tracks: usize,
    offset_minutes: i16,
    // Opened after the meeting is made, and kept when a write fails: resuming tries it again. The frames it holds
    // are the duration, which nothing counts beside it.
    writer: Option<audio::Writer>,
    // Track by track, the samples short of a frame and a byte short of a sample.
    heard: Vec<VecDeque<i16>>,
    odd: Vec<Option<u8>>,
    // Since the state was last told.
    untold: u64,
    loudest: f64,
}

impl Actor {
    fn run(mut self, messages: Receiver<Message>) {
        for message in messages {
            if panic::catch_unwind(AssertUnwindSafe(|| self.handle(message))).is_err() {
                // A fault of the core, or of the app's listener: the audio is left as a crash leaves it, and the
                // recorder takes the next command.
                self.take = None;
                let failed = State::Failed { problem: Problem::Unavailable };
                let _ = panic::catch_unwind(AssertUnwindSafe(|| self.set(failed)));
            }
        }
    }

    fn handle(&mut self, message: Message) {
        match message {
            Message::Command(command) => self.command(command),
            Message::Sound { track, pcm } => {
                self.queue.bytes.fetch_sub(pcm.len(), Ordering::AcqRel);
                self.hear(usize::from(track), &pcm);
            }
            Message::Lost => {
                self.queue.lost.store(false, Ordering::Release);
                self.interrupt();
            }
            Message::Forget { meetings, done } => {
                let held = self.state.progress().and_then(|progress| progress.meeting.as_ref());
                if held.is_some_and(|meeting| meetings.contains(meeting)) {
                    self.take = None;
                    self.set(State::Idle);
                }
                let _ = done.send(());
            }
        }
    }

    fn command(&mut self, command: Command) {
        let progress = self.state.progress().cloned();
        match (command, progress) {
            (Command::Start { tracks, offset_minutes }, None) => self.start(tracks, offset_minutes),
            (Command::Pause, Some(progress)) if matches!(self.state, State::Recording { .. }) => {
                self.set(State::Paused(progress));
            }
            (Command::Resume, Some(progress)) if !matches!(self.state, State::Recording { .. }) => {
                if let Some(take) = &mut self.take {
                    // The sound heard before is not followed by the next read: no byte waits across a gap.
                    take.odd.fill(None);
                }
                self.set(State::Recording { progress, level: 0.0 });
            }
            (Command::Stop, Some(progress)) => self.stop(progress),
            (Command::Cut { problem }, Some(progress)) => {
                if progress.meeting.is_some() {
                    self.set(State::Interrupted(progress));
                } else {
                    self.take = None;
                    self.set(State::Failed { problem });
                }
            }
            (Command::Dismiss, None) if matches!(self.state, State::Failed { .. }) => self.set(State::Idle),
            // A second start, or a command that does not fit the state: the recording is left as it is.
            _ => {}
        }
    }

    fn start(&mut self, tracks: u8, offset_minutes: i16) {
        if !self.library.has_key() {
            return self.set(State::Failed { problem: Problem::Storage });
        }
        if tracks == 0 {
            return self.set(State::Failed { problem: Problem::Unavailable });
        }
        let tracks = usize::from(tracks);
        self.queue.bound.store(self.bytes_per_track * tracks, Ordering::Release);
        self.take = Some(Take::new(tracks, offset_minutes));
        let progress = Progress { meeting: None, started_at: self.library.now(), recorded_ms: 0 };
        self.set(State::Recording { progress, level: 0.0 });
    }

    // Sound counts only while recording: a meeting paused is not listened to. A read under way when the pause came
    // was queued before it, and counts.
    fn hear(&mut self, track: usize, pcm: &[u8]) {
        let State::Recording { progress, .. } = &mut self.state else { return };
        let Some(take) = &mut self.take else { return };
        if track >= take.tracks {
            return;
        }
        take.add(track, pcm);
        if progress.meeting.is_none() {
            let id = MeetingId::random();
            progress.meeting = Some(id.clone());
            let begun = Change::Begin { id, started_at: progress.started_at, offset_minutes: take.offset_minutes };
            // Told before the meetings list it: an app that keeps the meeting being recorded out of its list never
            // shows it for a frame. At once, since the app needs it to give it its lines.
            (self.tell)(&self.state);
            self.library.apply(begun);
        }
        while let Some(moment) = self.take.as_mut().and_then(Take::moment) {
            if self.write(&moment).is_err() {
                return self.interrupt();
            }
            self.tell_progress();
        }
        // The computer's sound gives nothing while nothing plays (US-72): the microphone's runs ahead alone.
        if let Some(take) = self.take.as_mut().filter(|take| take.out_of_step()) {
            take.forget_heard();
            self.interrupt();
        }
    }

    fn write(&mut self, moment: &[Frame]) -> Result<(), AudioError> {
        let (Some(take), Some(progress)) = (&mut self.take, self.state.progress()) else { return Ok(()) };
        let Some(meeting) = &progress.meeting else { return Ok(()) };
        let writer = match &mut take.writer {
            Some(writer) => writer,
            None => take.writer.insert(self.library.record(meeting, take.tracks)?),
        };
        writer.write(moment)?;
        take.untold += 1;
        take.loudest = moment.iter().map(rms).fold(take.loudest, f64::max);
        let frames = writer.frames();
        if frames.is_multiple_of(LASTED_EVERY) {
            self.library.apply(Change::Lasted { meeting: meeting.clone(), duration_ms: frames * FRAME_MS });
        }
        Ok(())
    }

    fn tell_progress(&mut self) {
        let Some(take) = &mut self.take else { return };
        let State::Recording { progress, level } = &mut self.state else { return };
        progress.recorded_ms = take.recorded_ms();
        if take.untold >= TOLD_EVERY {
            *level = loudness(take.loudest);
            (take.untold, take.loudest) = (0, 0.0);
            (self.tell)(&self.state);
        }
    }

    fn interrupt(&mut self) {
        if let State::Recording { progress, .. } = &self.state {
            let recorded_ms = self.take.as_ref().map_or(progress.recorded_ms, Take::recorded_ms);
            self.set(State::Interrupted(Progress { recorded_ms, ..progress.clone() }));
        }
    }

    // The frame begun is completed with silence, so the audio keeps every sound heard. The meeting then lasts as
    // long as its audio, read back rather than counted: a second the disk refused is in neither.
    fn stop(&mut self, progress: Progress) {
        if let Some(moment) = self.take.as_mut().and_then(Take::rest) {
            let _ = self.write(&moment);
        }
        let take = self.take.take();
        if let (Some(meeting), Some(take)) = (progress.meeting, take) {
            if let Some(writer) = take.writer {
                let _ = writer.finish();
            }
            let duration_ms = self.library.audio(&meeting).map_or(0, |audio| audio.duration_ms());
            self.library.apply(Change::Lasted { meeting, duration_ms });
        }
        self.set(State::Idle);
    }

    fn set(&mut self, state: State) {
        if state != self.state {
            self.state = state;
            (self.tell)(&self.state);
        }
    }
}

impl Take {
    fn new(tracks: usize, offset_minutes: i16) -> Self {
        Take {
            tracks,
            offset_minutes,
            writer: None,
            heard: vec![VecDeque::new(); tracks],
            odd: vec![None; tracks],
            untold: 0,
            loudest: 0.0,
        }
    }

    fn add(&mut self, track: usize, mut pcm: &[u8]) {
        let (heard, odd) = (&mut self.heard[track], &mut self.odd[track]);
        if let Some(low) = *odd
            && let Some((high, rest)) = pcm.split_first()
        {
            heard.push_back(i16::from_le_bytes([low, *high]));
            (*odd, pcm) = (None, rest);
        }
        let (samples, rest) = pcm.as_chunks::<2>();
        heard.extend(samples.iter().map(|sample| i16::from_le_bytes(*sample)));
        if let Some(last) = rest.first() {
            *odd = Some(*last);
        }
    }

    // A frame of each track, once every track has one.
    fn moment(&mut self) -> Option<Vec<Frame>> {
        if self.heard.iter().any(|heard| heard.len() < FRAME_SAMPLES) {
            return None;
        }
        Some(self.heard.iter_mut().map(take_frame).collect())
    }

    // What is left once the recording stops, made whole with silence.
    fn rest(&mut self) -> Option<Vec<Frame>> {
        if self.heard.iter().all(VecDeque::is_empty) {
            return None;
        }
        let whole = |heard: &mut VecDeque<i16>| {
            heard.resize(FRAME_SAMPLES.max(heard.len()), 0);
            take_frame(heard)
        };
        Some(self.heard.iter_mut().map(whole).collect())
    }

    fn out_of_step(&self) -> bool {
        self.heard.iter().any(|heard| heard.len() > AHEAD_SAMPLES)
    }

    fn forget_heard(&mut self) {
        self.heard.iter_mut().for_each(VecDeque::clear);
        self.odd.fill(None);
    }

    fn recorded_ms(&self) -> u64 {
        self.writer.as_ref().map_or(0, |writer| writer.frames() * FRAME_MS)
    }
}

fn take_frame(heard: &mut VecDeque<i16>) -> Frame {
    let mut frame = [0; FRAME_SAMPLES];
    frame.iter_mut().zip(heard.drain(..FRAME_SAMPLES)).for_each(|(slot, sample)| *slot = sample);
    frame
}

fn rms(frame: &Frame) -> f64 {
    let sum: f64 = frame.iter().map(|sample| f64::from(*sample).powi(2)).sum();
    (sum / FRAME_SAMPLES as f64).sqrt()
}

fn loudness(rms: f64) -> f32 {
    if rms <= 0.0 {
        return 0.0;
    }
    let db = 20.0 * (rms / 32_768.0).log10();
    ((db - FLOOR_DB) / (LOUD_DB - FLOOR_DB)).clamp(0.0, 1.0) as f32
}

#[cfg(test)]
pub(crate) mod testing {
    use std::f64::consts::TAU;

    use super::*;

    // Made up: a note held for `ms`, as the microphone reads it.
    pub fn sound(ms: u64, loudness: f64, note: f64) -> Vec<u8> {
        let sample = |at: u64| ((TAU * note * at as f64 / SAMPLE_RATE as f64).sin() * loudness) as i16;
        (0..ms * SAMPLE_RATE as u64 / 1_000).flat_map(|at| sample(at).to_le_bytes()).collect()
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::Path;
    use std::sync::Mutex;
    use std::sync::mpsc::TryRecvError;

    use tempfile::TempDir;
    use time::macros::utc_datetime;
    use uuid::Uuid;

    use super::testing::sound;
    use super::*;
    use crate::audio::testing::likeness;
    use crate::library::{Event, Listener};
    use crate::meeting::MinutesState;
    use crate::secret::Key;
    use crate::secret::testing::key;

    const NOW: UtcDateTime = utc_datetime!(2026-11-18 9:00);
    const SPOKEN: f64 = 5_000.0;

    struct Unheard;

    impl Listener for Unheard {
        fn on_event(&self, _: &Event) {}
    }

    // Closed by a test, the next state told waits for it: the recorder then stops reading its queue.
    struct Gate {
        closed: Arc<Mutex<()>>,
        entered: Receiver<()>,
    }

    struct Desk {
        folder: TempDir,
        library: Arc<Library>,
        told: Arc<Mutex<Vec<State>>>,
        recorder: Recorder,
    }

    fn library_at(folder: &Path, key: Option<Key>) -> Arc<Library> {
        Arc::new(Library::open_at(folder.into(), key, Arc::new(Unheard), Box::new(|| NOW)).unwrap())
    }

    fn desk() -> Desk {
        made(Some(key(1)), QUEUE_SECONDS * BYTES_PER_SECOND, |_| {}).0
    }

    // `then` is called after each state is kept, on the recorder's thread.
    fn made(key: Option<Key>, bytes_per_track: usize, then: impl Fn(&State) + Send + 'static) -> (Desk, Gate) {
        let folder = tempfile::tempdir().unwrap();
        let library = library_at(folder.path(), key);
        let told = Arc::new(Mutex::new(Vec::new()));
        let (closed, (entering, entered)) = (Arc::new(Mutex::new(())), mpsc::channel());
        let (kept, gate) = (told.clone(), closed.clone());
        let tell = move |state: &State| {
            kept.lock().unwrap().push(state.clone());
            let _ = entering.send(());
            drop(gate.lock().unwrap());
            then(state);
        };
        let recorder = Recorder::bounded(library.clone(), tell, bytes_per_track).unwrap();
        (Desk { folder, library, told, recorder }, Gate { closed, entered })
    }

    fn start() -> Command {
        Command::Start { tracks: 1, offset_minutes: 60 }
    }

    impl Desk {
        fn order(&self, command: Command) {
            self.recorder.apply(command);
        }

        // In reads of 100 ms, as a microphone gives them, each second waited for so the queue never fills.
        fn hear(&self, ms: u64) {
            let heard = sound(ms, SPOKEN, 440.0);
            for (read, pcm) in heard.chunks(BYTES_PER_SECOND / 10).enumerate() {
                self.recorder.hear(0, pcm.to_vec());
                if read % 10 == 9 {
                    self.settled();
                }
            }
        }

        // Once the recorder has done all it was asked, the last state it told.
        fn settled(&self) -> State {
            self.recorder.forget(&[]);
            self.told.lock().unwrap().last().cloned().unwrap_or(State::Idle)
        }

        fn progress(&self) -> Progress {
            self.settled().progress().cloned().unwrap()
        }

        fn meeting(&self) -> MeetingId {
            self.progress().meeting.unwrap()
        }

        // The meetings and the audios on the disk.
        fn files(&self) -> Vec<String> {
            let names = fs::read_dir(self.folder.path()).unwrap().map(|entry| entry.unwrap().file_name());
            let mut kept: Vec<String> = names
                .filter_map(|name| name.into_string().ok())
                .filter(|name| name.ends_with(".notitia") || name.ends_with(".audio"))
                .collect();
            kept.sort();
            kept
        }

        // The duration of the meeting, and that of its audio.
        fn lasted(&self, meeting: &MeetingId) -> (u64, u64) {
            let duration = self.library.meeting(meeting).unwrap().duration_ms;
            (duration, self.library.audio(meeting).unwrap().duration_ms())
        }
    }

    fn progress(meeting: Option<&MeetingId>, recorded_ms: u64) -> Progress {
        Progress { meeting: meeting.cloned(), started_at: NOW, recorded_ms }
    }

    #[test]
    fn the_duration_is_the_audio_heard_not_the_time_spent() {
        let desk = desk();
        desk.order(start());
        assert_eq!(desk.settled(), State::Recording { progress: progress(None, 0), level: 0.0 });
        desk.hear(1_000);
        desk.hear(500);
        let meeting = desk.meeting();
        assert_eq!(desk.progress(), progress(Some(&meeting), 1_500));
        desk.order(Command::Stop);
        assert_eq!(desk.settled(), State::Idle);
        assert_eq!(desk.lasted(&meeting), (1_500, 1_500));
    }

    #[test]
    fn a_pause_adds_nothing_and_the_resume_goes_on_with_the_same_audio() {
        let desk = desk();
        desk.order(start());
        desk.hear(1_000);
        let meeting = desk.meeting();
        desk.order(Command::Pause);
        desk.hear(1_000);
        assert_eq!(desk.settled(), State::Paused(progress(Some(&meeting), 1_000)));
        desk.order(Command::Resume);
        desk.hear(1_000);
        desk.order(Command::Stop);
        desk.settled();
        assert_eq!(desk.lasted(&meeting), (2_000, 2_000));
        assert_eq!(desk.files().len(), 2);
    }

    #[test]
    fn the_level_follows_the_loudness() {
        let desk = desk();
        desk.order(start());
        let level_of = |loudness: f64| {
            desk.recorder.hear(0, sound(100, loudness, 440.0));
            let State::Recording { level, .. } = desk.settled() else { panic!("not recording") };
            level
        };
        assert_eq!(level_of(0.0), 0.0);
        let (quiet, loud) = (level_of(300.0), level_of(10_000.0));
        assert!(quiet > 0.0 && loud > quiet && loud <= 1.0, "quiet: {quiet}, loud: {loud}");
    }

    #[test]
    fn a_refused_microphone_fails_and_leaves_no_meeting() {
        let desk = desk();
        desk.order(start());
        desk.order(Command::Cut { problem: Problem::Denied });
        desk.order(Command::Stop);
        assert_eq!(desk.settled(), State::Failed { problem: Problem::Denied });
        assert!(desk.files().is_empty());
        desk.order(Command::Dismiss);
        assert_eq!(desk.settled(), State::Idle);
    }

    #[test]
    fn a_microphone_taken_away_keeps_what_was_heard_and_can_resume() {
        let desk = desk();
        desk.order(start());
        desk.hear(1_000);
        let meeting = desk.meeting();
        desk.order(Command::Cut { problem: Problem::Busy });
        desk.hear(1_000);
        assert_eq!(desk.settled(), State::Interrupted(progress(Some(&meeting), 1_000)));
        desk.order(Command::Resume);
        desk.hear(1_000);
        assert!(matches!(desk.settled(), State::Recording { .. }));
        desk.order(Command::Stop);
        desk.settled();
        assert_eq!(desk.lasted(&meeting), (2_000, 2_000));
    }

    #[test]
    fn a_second_start_leaves_the_recording_alone() {
        let desk = desk();
        desk.order(start());
        desk.hear(1_000);
        let (before, told) = (desk.settled(), desk.told.lock().unwrap().len());
        desk.order(Command::Start { tracks: 2, offset_minutes: 0 });
        assert_eq!((desk.settled(), desk.told.lock().unwrap().len()), (before, told));
    }

    #[test]
    fn without_the_key_nothing_is_recorded() {
        let (desk, _) = made(None, BYTES_PER_SECOND, |_| {});
        desk.order(start());
        desk.hear(1_000);
        assert_eq!(desk.settled(), State::Failed { problem: Problem::Storage });
        assert!(desk.files().is_empty());
    }

    #[test]
    fn the_meeting_is_made_at_the_first_sound_with_an_identifier_drawn_by_the_core() {
        let desk = desk();
        desk.order(start());
        desk.settled();
        assert!(desk.files().is_empty());
        desk.hear(20);
        let meeting = desk.meeting();
        assert!(Uuid::parse_str(meeting.as_str()).is_ok());
        let made = desk.library.meeting(&meeting).unwrap();
        assert_eq!((made.started_at, made.offset_minutes, made.minutes), (NOW, Some(60), MinutesState::Writing));
        assert_eq!(desk.files(), [format!("{}.audio", meeting.as_str()), format!("{}.notitia", meeting.as_str())]);
    }

    #[test]
    fn after_a_crash_the_meeting_lasts_as_long_as_its_audio() {
        let desk = desk();
        desk.order(start());
        desk.hear(12_000);
        let meeting = desk.meeting();
        // Told at 10 s: the last two seconds are in the audio alone.
        assert_eq!(desk.library.meeting(&meeting).unwrap().duration_ms, 10_000);
        let Desk { folder, library, recorder, .. } = desk;
        drop((recorder, library));
        let library = library_at(folder.path(), Some(key(1)));
        assert_eq!(library.meeting(&meeting).unwrap().duration_ms, 12_000);
        assert_eq!(library.audio(&meeting).unwrap().duration_ms(), 12_000);
    }

    #[test]
    fn a_meeting_let_go_stops_and_a_late_sound_changes_nothing() {
        let desk = desk();
        desk.order(start());
        desk.hear(100);
        let meeting = desk.meeting();
        desk.recorder.forget(&[MeetingId::new("recorded-1").unwrap()]);
        assert!(matches!(desk.settled(), State::Recording { .. }));
        desk.recorder.forget(std::slice::from_ref(&meeting));
        desk.hear(100);
        assert_eq!(desk.settled(), State::Idle);
        assert_eq!(desk.library.audio(&meeting).unwrap().duration_ms(), 0);
    }

    #[test]
    fn a_full_queue_interrupts_the_recording_and_leaves_no_silent_hole() {
        let (desk, gate) = made(Some(key(1)), BYTES_PER_SECOND, |_| {});
        desk.order(start());
        desk.hear(100);
        let meeting = desk.meeting();
        let closed = gate.closed.lock().unwrap();
        while gate.entered.try_recv() != Err(TryRecvError::Empty) {}
        desk.recorder.hear(0, sound(500, SPOKEN, 440.0));
        gate.entered.recv().unwrap();
        // A second waits, which is the bound: the next read is thrown away, and those after it.
        for _ in 0..14 {
            desk.recorder.hear(0, sound(100, SPOKEN, 440.0));
        }
        drop(closed);
        assert_eq!(desk.settled(), State::Interrupted(progress(Some(&meeting), 1_600)));
        desk.order(Command::Resume);
        desk.hear(100);
        desk.order(Command::Stop);
        desk.settled();
        assert_eq!(desk.lasted(&meeting), (1_700, 1_700));
    }

    #[test]
    fn a_disk_that_refuses_the_audio_interrupts_the_recording_and_resuming_tries_again() {
        let desk = desk();
        desk.order(start());
        // Half a frame: the meeting is made, its audio not opened yet.
        desk.recorder.hear(0, sound(10, SPOKEN, 440.0));
        let meeting = desk.meeting();
        // A folder where the audio goes: the disk refuses it, whoever runs the tests.
        let taken = desk.folder.path().join(format!("{}.audio", meeting.as_str()));
        fs::create_dir(&taken).unwrap();
        desk.recorder.hear(0, sound(10, SPOKEN, 440.0));
        assert_eq!(desk.settled(), State::Interrupted(progress(Some(&meeting), 0)));
        fs::remove_dir(&taken).unwrap();
        desk.order(Command::Resume);
        desk.hear(1_000);
        desk.order(Command::Stop);
        desk.settled();
        assert_eq!(desk.lasted(&meeting), (1_000, 1_000));
    }

    #[test]
    fn a_track_far_ahead_of_the_other_interrupts_the_recording_and_is_not_kept() {
        let desk = desk();
        desk.order(Command::Start { tracks: 2, offset_minutes: 60 });
        // The microphone alone, as when the computer plays nothing.
        for _ in 0..11 {
            desk.recorder.hear(0, sound(1_000, SPOKEN, 440.0));
            desk.settled();
        }
        let meeting = desk.meeting();
        assert_eq!(desk.settled(), State::Interrupted(progress(Some(&meeting), 0)));
        // In step again once resumed: nothing of the ten seconds before is paired with the new sound.
        desk.order(Command::Resume);
        desk.recorder.hear(0, sound(1_000, SPOKEN, 440.0));
        desk.recorder.hear(1, sound(1_000, SPOKEN, 880.0));
        desk.order(Command::Stop);
        desk.settled();
        assert_eq!(desk.lasted(&meeting), (1_000, 1_000));
    }

    #[test]
    fn threads_that_command_and_send_sound_leave_one_story() {
        let desk = desk();
        desk.order(start());
        thread::scope(|scope| {
            for worker in 0..4 {
                let desk = &desk;
                scope.spawn(move || {
                    for turn in 0..25 {
                        desk.recorder.hear(0, sound(100, SPOKEN, 440.0));
                        if worker == 0 {
                            desk.order(if turn % 2 == 0 { Command::Pause } else { Command::Resume });
                        }
                    }
                });
            }
        });
        let meeting = desk.meeting();
        desk.order(Command::Stop);
        assert_eq!(desk.settled(), State::Idle);
        let told = desk.told.lock().unwrap().clone();
        let progress: Vec<&Progress> = told.iter().filter_map(State::progress).collect();
        assert!(progress.iter().filter_map(|progress| progress.meeting.as_ref()).all(|told| *told == meeting));
        assert!(progress.windows(2).all(|pair| pair[0].recorded_ms <= pair[1].recorded_ms));
        let (lasted, audio) = desk.lasted(&meeting);
        assert_eq!(lasted, audio);
        assert!(lasted >= progress.last().unwrap().recorded_ms && lasted <= 10_000);
    }

    #[test]
    fn two_tracks_are_kept_together() {
        let desk = desk();
        desk.order(Command::Start { tracks: 2, offset_minutes: 60 });
        desk.recorder.hear(0, sound(1_000, SPOKEN, 440.0));
        // A track the recording does not have.
        desk.recorder.hear(2, sound(1_000, SPOKEN, 440.0));
        assert_eq!(desk.progress().recorded_ms, 0);
        desk.recorder.hear(1, sound(1_000, SPOKEN, 880.0));
        let meeting = desk.meeting();
        desk.order(Command::Stop);
        desk.settled();
        let mut audio = desk.library.audio(&meeting).unwrap();
        assert_eq!((audio.tracks(), audio.duration_ms()), (2, 1_000));
        let read = audio.read(0, 1_000).unwrap();
        assert!(likeness(&read[0], &read[1]).abs() < 0.2);
    }

    #[test]
    fn a_read_of_an_odd_size_loses_no_byte() {
        let desk = desk();
        desk.order(start());
        let heard = sound(1_000, SPOKEN, 440.0);
        heard.chunks(333).for_each(|pcm| desk.recorder.hear(0, pcm.to_vec()));
        let meeting = desk.meeting();
        desk.order(Command::Stop);
        desk.settled();
        let read = desk.library.audio(&meeting).unwrap().read(0, 1_000).unwrap().remove(0);
        let samples: Vec<i16> = heard.as_chunks::<2>().0.iter().map(|sample| i16::from_le_bytes(*sample)).collect();
        // Once the delay of the encoder is taken off.
        let alike = (100..110).map(|late| likeness(&samples[..read.len() - late], &read[late..])).fold(-1.0, f64::max);
        assert!(alike > 0.9, "{alike}");
    }

    #[test]
    fn a_byte_waits_for_its_pair_whatever_comes_between() {
        let mut take = Take::new(1, 0);
        for read in [&[0x34][..], &[], &[0x12, 0x78], &[0x56]] {
            take.add(0, read);
        }
        assert_eq!((take.heard[0].clone(), take.odd[0]), (VecDeque::from([0x1234, 0x5678]), None));
    }

    #[test]
    fn a_fault_fails_the_recording_and_the_recorder_takes_the_next_one() {
        let faulted = AtomicBool::new(false);
        let (desk, _) = made(Some(key(1)), BYTES_PER_SECOND, move |state| {
            assert!(!matches!(state, State::Paused(_)) || faulted.swap(true, Ordering::AcqRel), "the app failed");
        });
        desk.order(start());
        desk.hear(100);
        desk.order(Command::Pause);
        assert_eq!(desk.settled(), State::Failed { problem: Problem::Unavailable });
        desk.order(Command::Dismiss);
        desk.order(start());
        desk.hear(100);
        let meeting = desk.meeting();
        desk.order(Command::Stop);
        desk.settled();
        assert_eq!(desk.lasted(&meeting), (100, 100));
    }
}
