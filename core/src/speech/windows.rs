use std::collections::VecDeque;
use std::ops::Range;

use super::voice::{HEARD_FRAMES, Voice};
use crate::audio::{self, AudioError, FRAME_MS, FRAME_SAMPLES, FRAMES_PER_SECOND, Frame, Reader};
use crate::engine::{LONGEST_MS, SHORTEST_MS};

// The longest a window aims at, whatever the engine takes: a question waits one window at most, and a killed app or a
// failed call loses one. docs/adr/0011-fenetres.md.
const AIM_MS: u64 = 30_000;

// In frames of 20 ms, as the audio keeps them.
// Kept before the first speech and after the last: a word begins and ends softer than speech is heard.
const PAD: u64 = 15;
// Half of the shortest pause a window ends in, 320 ms: never the gap inside a word.
const HALF_GAP: u64 = 8;
// A pause this long ends a window, whatever its length: it is never sent.
const PAUSE: u64 = 100;
// Less is a cough or a door: not sent.
const LEAST_SPEECH: usize = 15;
// What a window cut in the middle of speech hears again of the one before: the word cut in two is heard whole.
const OVERLAP: u64 = 50;
// Read again before an edge to take it up: what the noise is learned from, before the overlap.
const TAKEN_UP: u64 = HEARD_FRAMES as u64 + OVERLAP;

const SECOND: u64 = FRAMES_PER_SECOND as u64;

// Carries what was said: no Debug outside the tests, as for Meeting.
#[cfg_attr(test, derive(Debug, PartialEq))]
pub struct Window {
    // Where its sound starts, from the start of the meeting: the silence left out before it counts.
    pub from_ms: u64,
    // One channel at 16 kHz, the tracks mixed as a player plays them. Never longer than the engine takes.
    pub sound: Vec<i16>,
    // The start of its sound that the window before ended with: a cut in the middle of speech. 0 after a pause.
    pub overlap_ms: u64,
    // Its edge: the audio before it is done with once this window is heard. The next windows are found from there.
    pub until_ms: u64,
}

#[cfg_attr(test, derive(Debug, PartialEq))]
pub enum Next {
    Window(Window),
    // What follows depends on sound not recorded yet: ask again once there is more.
    Wait,
    // Nothing more to hear, up to the end of the audio.
    End,
}

// Where a window starts, all that is kept from one window to the next. The edge of a window given says it alone, so
// the windows found after taking it up are the ones found without stopping.
#[derive(Clone, Copy)]
enum Start {
    // From an edge given back: the frames before it only teach the noise of the room.
    TakenUp { edge: u64 },
    // After a pause: the window starts with the speech, PAD before it, never before `at`.
    Pause { at: u64 },
    // After a cut in the middle of speech: at `at`, OVERLAP before the edge. `told`: the window before was given, so
    // the overlap was heard. An edge taken up is always one of a window given.
    Speech { at: u64, edge: u64, told: bool },
}

// Where a window ends, in frames.
struct Cut {
    sound: Range<u64>,
    overlap: u64,
    edge: u64,
    forced: bool,
}

enum Step {
    Read,
    Begin(Start),
    Cut(Cut),
    End,
}

// The windows of a meeting, one after the other. Held between two calls while the meeting is recorded, so what was
// read is not read again. Made again from the last edge for another engine.
pub struct Windows {
    aim: u64,
    overlap: u64,
    voice: Voice,
    // The frames read and not left behind yet, from `first`, each with whether it is speech.
    heard: VecDeque<(Frame, bool)>,
    first: u64,
    start: Start,
}

impl Windows {
    // From the start of a meeting (0), or from the edge of a window given before: the same windows either way.
    pub fn at(edge_ms: u64, longest_ms: u32) -> Self {
        let longest = u64::from(longest_ms.clamp(SHORTEST_MS, LONGEST_MS));
        let aim = longest.min(AIM_MS) / FRAME_MS;
        let edge = edge_ms / FRAME_MS;
        let start = if edge == 0 { Start::Pause { at: 0 } } else { Start::TakenUp { edge } };
        // From a whole second: see read_second.
        let first = edge.saturating_sub(TAKEN_UP) / SECOND * SECOND;
        Self { aim, overlap: OVERLAP.min(aim / 4), voice: Voice::default(), heard: VecDeque::new(), first, start }
    }

    // `ended`: the recording is over and `audio` was opened after it. Opened before, its last seconds would be missed
    // and the last window cut short. Until then, `audio` opened again once there is more gives what follows a Wait.
    pub fn next(&mut self, audio: &mut Reader, ended: bool) -> Result<Next, AudioError> {
        self.next_in(audio, ended)
    }

    fn next_in(&mut self, audio: &mut impl Seconds, ended: bool) -> Result<Next, AudioError> {
        loop {
            let all_read = self.read() >= audio.frames();
            match self.step(ended && all_read) {
                Step::Read if all_read => return Ok(Next::Wait),
                Step::Read => self.read_second(audio)?,
                Step::Begin(start) => self.begin(start),
                Step::Cut(cut) => {
                    if let Some(window) = self.take(cut) {
                        return Ok(Next::Window(window));
                    }
                }
                Step::End => return Ok(Next::End),
            }
        }
    }

    fn read(&self) -> u64 {
        self.first + self.heard.len() as u64
    }

    fn speech(&self, frame: u64) -> bool {
        let at = frame.checked_sub(self.first).and_then(|at| usize::try_from(at).ok());
        at.and_then(|at| self.heard.get(at)).is_some_and(|(_, speech)| *speech)
    }

    // What to do with the frames read so far. Never reads: `ended` says every frame of the audio is read.
    fn step(&self, ended: bool) -> Step {
        let read = self.read();
        match self.start {
            Start::TakenUp { edge } if read < edge => {
                if ended {
                    Step::End
                } else {
                    Step::Read
                }
            }
            // Every cut in a pause has silence just before its edge, every forced cut speech: the edge tells which.
            Start::TakenUp { edge } => {
                Step::Begin(if (edge.saturating_sub(HALF_GAP)..edge).any(|at| self.speech(at)) {
                    Start::Speech { at: edge.saturating_sub(self.overlap), edge, told: true }
                } else {
                    Start::Pause { at: edge }
                })
            }
            Start::Pause { at } => match (at..read).find(|frame| self.speech(*frame)) {
                None if ended => Step::End,
                // Only the last PAD frames of a silence may be sent: the rest is left behind as it is read.
                None if read > at + PAD => Step::Begin(Start::Pause { at: read - PAD }),
                None => Step::Read,
                Some(speech) => self.cut(at.max(speech.saturating_sub(PAD)), speech, 0, ended),
            },
            Start::Speech { at, edge, told } => self.cut(at, edge, if told { edge - at } else { 0 }, ended),
        }
    }

    // Where the window from `from` ends, as far as the frames read tell. It ends in a pause after `after`, so that
    // every edge moves on.
    fn cut(&self, from: u64, after: u64, overlap: u64, ended: bool) -> Step {
        let read = self.read();
        let full = from + self.aim;
        let mut last = None;
        for frame in from..read.min(full + HALF_GAP) {
            if self.speech(frame) {
                last = Some(frame);
            } else if let Some(spoken) = last
                && frame - spoken == PAUSE
            {
                let edge = frame + 1 - HALF_GAP;
                return Step::Cut(Cut { sound: from..edge.min(spoken + 1 + PAD), overlap, edge, forced: false });
            }
        }
        if read <= full || (read < full + HALF_GAP && !ended) {
            if !ended {
                return Step::Read;
            }
            let end = last.map_or(from, |spoken| read.min(spoken + 1 + PAD));
            return Step::Cut(Cut { sound: from..end, overlap, edge: read, forced: false });
        }
        // Full. Past the end of an audio that has ended, nothing more is said.
        let quiet = |frame: u64| frame >= read || !self.speech(frame);
        let last_before = |end: u64| (from..end).rev().find(|frame| self.speech(*frame));
        // The last point with silence on both sides: the fullest window that never cuts a word.
        let pause = (after + 1..=full).rev().find(|at| (at.saturating_sub(HALF_GAP)..at + HALF_GAP).all(quiet));
        Step::Cut(match pause {
            Some(edge) => {
                let end = last_before(edge).map_or(from, |spoken| edge.min(spoken + 1 + PAD));
                Cut { sound: from..end, overlap, edge, forced: false }
            }
            // Speech with no pause: cut just after a frame of speech, which says it at the edge.
            None => {
                let edge = last_before(full).map_or(full, |spoken| spoken + 1);
                Cut { sound: from..edge, overlap, edge, forced: true }
            }
        })
    }

    fn begin(&mut self, start: Start) {
        let at = match start {
            Start::TakenUp { .. } => self.first,
            Start::Pause { at } | Start::Speech { at, .. } => at,
        };
        let behind = at.saturating_sub(self.first).min(self.heard.len() as u64);
        self.heard.drain(..behind as usize);
        self.first += behind;
        self.start = start;
    }

    fn frames(&self, frames: &Range<u64>) -> impl Iterator<Item = &(Frame, bool)> {
        let at = |frame: u64| usize::try_from(frame.saturating_sub(self.first)).unwrap_or(usize::MAX);
        let end = at(frames.end).min(self.heard.len());
        self.heard.range(at(frames.start).min(end)..end)
    }

    // The window of a cut, unless what it adds to the one before holds too little speech to be heard: the overlap
    // alone, at the end of an audio, was heard already.
    fn take(&mut self, cut: Cut) -> Option<Window> {
        let added = cut.sound.start + cut.overlap..cut.sound.end;
        let spoken = self.frames(&added).filter(|(_, speech)| *speech).count();
        let window = (spoken >= LEAST_SPEECH).then(|| Window {
            from_ms: cut.sound.start * FRAME_MS,
            sound: self.frames(&cut.sound).flat_map(|(frame, _)| frame).copied().collect(),
            overlap_ms: cut.overlap * FRAME_MS,
            until_ms: cut.edge * FRAME_MS,
        });
        let told = window.is_some();
        self.begin(if cut.forced {
            Start::Speech { at: cut.edge.saturating_sub(self.overlap), edge: cut.edge, told }
        } else {
            Start::Pause { at: cut.edge }
        });
        window
    }

    // A whole second at a time, each decoded from its own start: a sample then has one value, whether the reading
    // began at the start of the meeting or at an edge taken up, and the frames are judged the same. A second read in
    // part, the last of an audio that grew since, gives only what follows.
    fn read_second(&mut self, audio: &mut impl Seconds) -> Result<(), AudioError> {
        let sound = audio.second(self.read() / SECOND)?;
        let (frames, _) = sound.as_chunks::<FRAME_SAMPLES>();
        let frames = frames.get((self.read() % SECOND) as usize..).unwrap_or_default();
        // An audio that gives less than it says would be read again and again.
        if frames.is_empty() {
            return Err(AudioError::Unsupported);
        }
        for frame in frames {
            let speech = self.voice.hear(frame);
            self.heard.push_back((*frame, speech));
        }
        Ok(())
    }
}

// What the windows read of an audio.
trait Seconds {
    fn frames(&self) -> u64;
    // Its tracks mixed, shorter at the end of the audio.
    fn second(&mut self, second: u64) -> Result<Vec<i16>, AudioError>;
}

impl Seconds for Reader {
    fn frames(&self) -> u64 {
        self.duration_ms() / FRAME_MS
    }

    fn second(&mut self, second: u64) -> Result<Vec<i16>, AudioError> {
        Ok(audio::mix(self.read(second * 1_000, 1_000)?))
    }
}

#[cfg(test)]
mod tests {
    use std::fs::{File, OpenOptions};
    use std::path::{Path, PathBuf};

    use tempfile::TempDir;

    use super::*;
    use crate::secret::testing::key;
    use crate::speech::testing::{added, frames, hum, noise, quiet, samples, talk};
    use crate::testing::{Draw, cases};

    // A sound held whole in memory, read as an audio is: `heard` of it is recorded so far.
    struct Heard {
        sound: Vec<i16>,
        heard: usize,
        // Fails once, at this second.
        fails_at: Option<u64>,
    }

    impl Heard {
        fn whole(sound: &[i16]) -> Self {
            Self { sound: sound.to_vec(), heard: sound.len(), fails_at: None }
        }
    }

    impl Seconds for Heard {
        fn frames(&self) -> u64 {
            (self.heard / FRAME_SAMPLES) as u64
        }

        fn second(&mut self, second: u64) -> Result<Vec<i16>, AudioError> {
            if self.fails_at.take_if(|at| *at == second).is_some() {
                return Err(AudioError::Unreadable);
            }
            let from = (second as usize * samples(1_000)).min(self.heard);
            Ok(self.sound[from..(from + samples(1_000)).min(self.heard)].to_vec())
        }
    }

    fn all(windows: &mut Windows, audio: &mut impl Seconds) -> Vec<Window> {
        let mut found = Vec::new();
        loop {
            match windows.next_in(audio, true).unwrap() {
                Next::Window(window) => found.push(window),
                Next::End => return found,
                Next::Wait => panic!("an audio that has ended never waits"),
            }
        }
    }

    fn windows_from(sound: &[i16], longest_ms: u32, edge_ms: u64) -> Vec<Window> {
        all(&mut Windows::at(edge_ms, longest_ms), &mut Heard::whole(sound))
    }

    fn windows(sound: &[i16], longest_ms: u32) -> Vec<Window> {
        windows_from(sound, longest_ms, 0)
    }

    fn length_ms(window: &Window) -> u64 {
        (window.sound.len() / samples(1)) as u64
    }

    // From, length, overlap and edge, in ms.
    fn spans(windows: &[Window]) -> Vec<(u64, u64, u64, u64)> {
        windows.iter().map(|window| (window.from_ms, length_ms(window), window.overlap_ms, window.until_ms)).collect()
    }

    // What holds for the windows of any sound.
    fn hold(sound: &[i16], longest_ms: u32, found: &[Window]) {
        let longest = u64::from(longest_ms.clamp(SHORTEST_MS, LONGEST_MS)).min(AIM_MS);
        let mut edge = 0;
        for (rank, window) in found.iter().enumerate() {
            let context = format!("{longest_ms} ms, window {rank}: {:?}", spans(&found[rank..=rank]));
            assert!(!window.sound.is_empty() && length_ms(window) <= longest, "{context}");
            assert!(window.from_ms + window.overlap_ms >= edge && window.overlap_ms <= 1_000, "{context}");
            assert!(window.from_ms + length_ms(window) <= window.until_ms && window.until_ms > edge, "{context}");
            assert!(window.until_ms <= (sound.len() / samples(1)) as u64, "{context}");
            assert_eq!(window.sound, sound[samples(window.from_ms)..samples(window.from_ms + length_ms(window))]);
            edge = window.until_ms;
        }
    }

    // The windows found again from each edge are the ones found without stopping, to the sample.
    fn taken_up_alike(sound: &[i16], longest_ms: u32, found: &[Window]) {
        for (rank, window) in found.iter().enumerate() {
            let (edge, taken_up) = (window.until_ms, windows_from(sound, longest_ms, window.until_ms));
            assert_eq!(spans(&taken_up), spans(&found[rank + 1..]), "{longest_ms} ms, taken up at {edge} ms");
            assert!(taken_up == found[rank + 1..], "{longest_ms} ms, taken up at {edge} ms");
        }
    }

    // A meeting of every kind of moment, 3 min 20 s: words, breaths, pauses, a talk with no pause, a noisy room.
    fn meeting() -> Vec<i16> {
        let room = |sound: Vec<i16>| added(&sound, &noise((sound.len() / samples(1)) as u64, 500));
        [
            quiet(1_200),
            talk(4_000),
            quiet(400),
            talk(2_600),
            quiet(2_500),
            talk(45_000),
            quiet(900),
            talk(6_000),
            quiet(12_000),
            room([talk(3_000), quiet(1_000)].concat().repeat(8)),
            room(talk(35_000)),
            room(quiet(5_000)),
            talk(1_000),
            quiet(150),
            talk(20_000),
            quiet(30_000),
            talk(600),
        ]
        .concat()
    }

    #[test]
    fn the_windows_go_to_the_thread_of_the_work() {
        fn sent<T: Send>() {}
        sent::<Windows>();
        sent::<Window>();
    }

    #[test]
    fn silence_gives_no_window() {
        assert!(windows(&quiet(20_000), 30_000).is_empty());
    }

    #[test]
    fn a_pause_ends_a_window() {
        let found = windows(&[talk(3_000), quiet(2_500), talk(3_000)].concat(), 30_000);
        let [first, second] = &found[..] else { panic!("{:?}", spans(&found)) };
        // The last syllable fades below speech a frame or two before it ends.
        assert!((first.from_ms, first.overlap_ms) == (0, 0) && (3_200..=3_300).contains(&length_ms(first)));
        // Cut in the pause, the silence after the words left out.
        assert!((3_000..5_500).contains(&first.until_ms));
        assert_eq!(spans(&found[1..]), [(5_200, 3_300, 0, 8_500)], "{:?}", spans(&found));
        assert_eq!(second.sound, [quiet(300), talk(3_000)].concat());
    }

    #[test]
    fn a_breath_does_not_cut_a_window() {
        assert_eq!(spans(&windows(&[talk(3_000), quiet(300), talk(1_000)].concat(), 30_000)), [(0, 4_300, 0, 4_300)]);
    }

    #[test]
    fn endless_speech_is_cut_at_the_longest_the_engine_takes() {
        let found = windows(&talk(70_000), 30_000);
        let [first, second, third] = &found[..] else { panic!("{:?}", spans(&found)) };
        // Cut just after the last frame heard as speech, then heard again from a second earlier.
        assert!((first.from_ms, first.overlap_ms) == (0, 0) && (29_800..=30_000).contains(&first.until_ms));
        assert_eq!(length_ms(first), first.until_ms);
        assert_eq!((second.from_ms, second.overlap_ms), (first.until_ms - 1_000, 1_000));
        assert_eq!((third.from_ms, third.overlap_ms, third.until_ms), (second.until_ms - 1_000, 1_000, 70_000));
        assert!(found.iter().all(|window| length_ms(window) <= 30_000));
    }

    #[test]
    fn the_silence_left_out_still_counts_in_the_time() {
        assert_eq!(spans(&windows(&[quiet(3_000), talk(3_000)].concat(), 30_000)), [(2_700, 3_300, 0, 6_000)]);
    }

    #[test]
    fn a_loud_noise_from_the_first_second_still_lets_the_pauses_cut() {
        // #155: a room at RMS 400 from the first frame, words of 3 s and pauses of 1 s, an engine of 5 s.
        let spoken = [talk(3_000), quiet(1_000)].concat().repeat(7);
        let sound = added(&[quiet(2_000), spoken, quiet(6_000)].concat(), &noise(36_000, 400));
        let found = windows(&sound, 5_000);
        assert_eq!(found.len(), 7, "{:?}", spans(&found));
        for (rank, window) in found.iter().enumerate() {
            let words = 2_000 + 4_000 * rank as u64;
            // Never a cut in the middle of a word, never the noise alone.
            assert_eq!(window.overlap_ms, 0);
            assert!((words - 300..=words).contains(&window.from_ms), "{:?}", spans(&found));
            assert!((words + 3_000..=words + 3_300).contains(&(window.from_ms + length_ms(window))));
        }
    }

    #[test]
    fn a_window_never_holds_more_than_the_engine_takes() {
        let sound = meeting();
        for longest_ms in [0, 1_000, 5_000, 29_999, 30_000, 600_000, u32::MAX] {
            let found = windows(&sound, longest_ms);
            assert!(found.len() > 5);
            hold(&sound, longest_ms, &found);
        }
    }

    #[test]
    fn a_ten_minute_engine_still_gets_thirty_second_windows() {
        let found = windows(&talk(600_000), 600_000);
        let (last, before) = found.split_last().unwrap();
        assert!(before.len() > 15 && before.iter().all(|window| (29_000..=30_000).contains(&length_ms(window))));
        assert!(length_ms(last) <= 30_000 && last.until_ms == 600_000);
    }

    #[test]
    fn any_sound_cuts_into_valid_windows() {
        let mut draw = Draw(0x0077_696e_646f_7773);
        for _ in 0..cases(30) {
            // Frames of speech and of silence in runs of any length, loud or soft, in a quiet or a noisy room.
            let mut sound = Vec::new();
            while sound.len() < samples(1_000 * (1 + draw.below(120)) as u64) {
                let longest = if draw.below(4) == 0 { 300 } else { 40 };
                let ms = 20 * (1 + draw.below(longest)) as u64;
                sound.extend(match draw.below(4) {
                    0 => quiet(ms),
                    1 => talk(ms),
                    2 => noise(ms, draw.below(3_000) as i64),
                    _ => added(&talk(ms), &noise(ms, draw.below(1_500) as i64)),
                });
            }
            let longest_ms = [1_000, 2_000, 5_000, 30_000][draw.below(4)];
            let found = windows(&sound, longest_ms);
            hold(&sound, longest_ms, &found);
            taken_up_alike(&sound, longest_ms, &found);
        }
    }

    #[test]
    fn resuming_at_any_edge_gives_the_same_windows() {
        let sound = meeting();
        for longest_ms in [5_000, 30_000] {
            let found = windows(&sound, longest_ms);
            assert!(found.iter().any(|window| window.overlap_ms > 0), "a talk with no pause is cut");
            hold(&sound, longest_ms, &found);
            taken_up_alike(&sound, longest_ms, &found);
        }
    }

    #[test]
    fn windows_found_while_recording_are_those_found_after() {
        let sound = meeting();
        let mut windows = Windows::at(0, 30_000);
        let mut audio = Heard { heard: 0, ..Heard::whole(&sound) };
        let mut found = Vec::new();
        // As the recorder seals it: whole seconds, a few at a time.
        while audio.heard < sound.len() {
            audio.heard = (audio.heard + samples(7_000)).min(sound.len() / samples(1_000) * samples(1_000));
            while let Next::Window(window) = windows.next_in(&mut audio, false).unwrap() {
                found.push(window);
            }
            if audio.heard == sound.len() / samples(1_000) * samples(1_000) {
                audio.heard = sound.len();
            }
        }
        found.extend(all(&mut windows, &mut audio));
        assert_eq!(found, self::windows(&sound, 30_000));
    }

    #[test]
    fn a_window_after_one_left_out_has_heard_nothing_twice() {
        // Clicks too sparse to be heard and too close for a pause: cut by force, never sent. Then someone speaks.
        let click = [hum(20, 8_000.0), quiet(280)].concat();
        let sound = [click.repeat(30), talk(3_000)].concat();
        let found = windows(&sound, 1_000);
        assert!(found[0].from_ms < 9_000, "{:?}", spans(&found));
        assert_eq!(found[0].overlap_ms, 0, "{:?}", spans(&found));
        assert!(found[1..].iter().all(|window| window.overlap_ms > 0));
    }

    #[test]
    fn a_second_heard_in_part_is_not_heard_twice() {
        let sound = meeting();
        let mut windows = Windows::at(0, 30_000);
        let mut audio = Heard { heard: samples(2_500), ..Heard::whole(&sound) };
        let mut found = Vec::new();
        while let Next::Window(window) = windows.next_in(&mut audio, false).unwrap() {
            found.push(window);
        }
        audio.heard = sound.len();
        found.extend(all(&mut windows, &mut audio));
        assert_eq!(spans(&found), spans(&self::windows(&sound, 30_000)));
    }

    #[test]
    fn a_failed_read_is_tried_again_and_changes_nothing() {
        let sound = meeting();
        for second in [0, 1, 6, 60, 100, 198] {
            let mut windows = Windows::at(0, 30_000);
            let mut audio = Heard { fails_at: Some(second), ..Heard::whole(&sound) };
            let mut found = Vec::new();
            loop {
                match windows.next_in(&mut audio, true) {
                    Ok(Next::Window(window)) => found.push(window),
                    Ok(_) => break,
                    Err(error) => assert_eq!(error, AudioError::Unreadable),
                }
            }
            assert_eq!(found, self::windows(&sound, 30_000), "failed at second {second}");
        }
    }

    #[test]
    fn an_edge_past_the_end_of_the_audio_gives_nothing() {
        let sound = talk(10_000);
        assert!(windows_from(&sound, 30_000, 60_000).is_empty());
        let mut audio = Heard::whole(&sound);
        assert_eq!(Windows::at(60_000, 30_000).next_in(&mut audio, false).unwrap(), Next::Wait);
    }

    #[test]
    fn a_recording_waits_for_what_follows() {
        let sound = [talk(3_000), quiet(1_000)].concat();
        let mut audio = Heard::whole(&sound);
        let mut windows = Windows::at(0, 30_000);
        assert_eq!(windows.next_in(&mut audio, false).unwrap(), Next::Wait);
        let [window] = &all(&mut windows, &mut audio)[..] else { panic!() };
        assert!((window.from_ms, window.until_ms) == (0, 4_000) && (3_200..=3_300).contains(&length_ms(window)));
    }

    // The same, through the audio a meeting keeps.
    fn sealed(folder: &TempDir, tracks: &[Vec<i16>], seconds: Range<usize>) -> PathBuf {
        let path = folder.path().join("recorded-1.audio");
        let file = OpenOptions::new().read(true).write(true).create(true).truncate(false).open(&path).unwrap();
        let mut writer = audio::Writer::open(file, &key(1), tracks.len()).unwrap();
        let frames: Vec<Vec<Frame>> = tracks.iter().map(|track| frames(track)).collect();
        let until = seconds.end.saturating_mul(FRAMES_PER_SECOND).min(frames[0].len());
        for frame in seconds.start * FRAMES_PER_SECOND..until {
            writer.write(&frames.iter().map(|track| track[frame]).collect::<Vec<_>>()).unwrap();
        }
        if until == frames[0].len() {
            writer.finish().unwrap();
        }
        path
    }

    fn reader(path: &Path) -> Reader {
        Reader::open(File::open(path).unwrap(), &key(1)).unwrap()
    }

    fn sealed_windows(path: &Path, longest_ms: u32, edge_ms: u64) -> Vec<Window> {
        all(&mut Windows::at(edge_ms, longest_ms), &mut reader(path))
    }

    #[test]
    fn resuming_a_sealed_audio_at_any_edge_gives_the_same_windows_to_the_sample() {
        let folder = tempfile::tempdir().unwrap();
        let sound = meeting()[..samples(100_000)].to_vec();
        let path = sealed(&folder, std::slice::from_ref(&sound), 0..usize::MAX);
        let found = sealed_windows(&path, 10_000, 0);
        assert!(found.len() > 8 && found.iter().any(|window| window.overlap_ms > 0));
        for (rank, window) in found.iter().enumerate() {
            assert_eq!(sealed_windows(&path, 10_000, window.until_ms), found[rank + 1..], "at {} ms", window.until_ms);
        }
    }

    #[test]
    fn a_sealed_audio_read_while_recorded_gives_the_windows_read_after() {
        let folder = tempfile::tempdir().unwrap();
        let sound = meeting()[..samples(90_500)].to_vec();
        let mut windows = Windows::at(0, 30_000);
        let mut found = Vec::new();
        for until in (5..=95).step_by(5) {
            let path = sealed(&folder, std::slice::from_ref(&sound), until - 5..until);
            let ended = until > 90;
            while let Next::Window(window) = windows.next(&mut reader(&path), ended).unwrap() {
                found.push(window);
            }
        }
        let path = folder.path().join("recorded-1.audio");
        assert_eq!(found, sealed_windows(&path, 30_000, 0));
        assert_eq!(found.last().map(|window| window.until_ms), Some(90_500));
    }

    #[test]
    fn two_tracks_are_heard_mixed() {
        let folder = tempfile::tempdir().unwrap();
        // The microphone, then the computer's sound, then both at once.
        let microphone = [talk(4_000), quiet(10_000), talk(3_000)].concat();
        let computer = [quiet(7_000), talk(4_000), quiet(3_000), talk(3_000)].concat();
        let path = sealed(&folder, &[microphone, computer], 0..usize::MAX);
        let found = sealed_windows(&path, 30_000, 0);
        let starts: Vec<u64> = found.iter().map(|window| window.from_ms).collect();
        assert_eq!(starts, [0, 6_700, 13_700], "{:?}", spans(&found));
        // Two voices together are louder than one.
        let loudest = |sound: &[i16]| sound.iter().map(|sample| sample.unsigned_abs()).max().unwrap_or(0);
        assert!(loudest(&found[2].sound) > loudest(&found[0].sound) * 3 / 2);
    }
}
