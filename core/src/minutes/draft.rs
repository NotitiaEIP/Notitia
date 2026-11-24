use std::collections::HashSet;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::mem;

use time::Date;

use super::prompt::{self, Said};
use super::reply::{self, Part};
use crate::engine::{EngineError, Stop, TextEngine};
use crate::meeting::{MinutesDraft, TranscriptLine};
use crate::speech::text::normalized;

// What a prompt may fill of the window once the reply is set aside. The rest is a margin: the tokens of a lent engine
// are an average of 3 bytes each, and a transcript full of figures holds more of them than counted.
const FILLED_PERCENT: usize = 85;

pub enum Step {
    // A call was made, and more are owed: whatever is more awaited may come before the next one.
    More,
    Done(MinutesDraft),
}

// The minutes of one meeting as far as they are written, kept between two calls so that no slice is asked twice while
// the app runs. An hour no model reads at once is cut into slices that fit its window: each gives the minutes of its
// part, then their summaries are merged. One slice is one call, the minutes of the whole.
#[derive(Default)]
pub struct Draft {
    // How the parts were cut: another transcript, or an engine that cuts it otherwise, starts over.
    cut: u64,
    parts: Vec<Part>,
    // Once each part is read: the summaries left to merge, as many as fit a call.
    merging: Option<Vec<String>>,
}

impl Draft {
    // At most one call. `lines`: the whole transcript, cut again at each step into the same slices while neither it nor
    // the engine changes.
    pub fn step(
        &mut self,
        lines: &[TranscriptLine],
        day: Date,
        engine: &dyn TextEngine,
        stop: &Stop,
    ) -> Result<Step, EngineError> {
        let said: Vec<Said> = lines.iter().filter_map(Said::of).collect();
        if said.is_empty() {
            // Nothing was said: nothing is owed, and no model is asked.
            return Ok(Step::Done(MinutesDraft::owed(lines)));
        }
        // Counted with the longest header a part has: whatever the number of slices, each fits.
        let longest = prompt::header(day, Some((usize::MAX, usize::MAX)));
        let room = room(engine, &prompt::instructions(true), &longest).ok_or(EngineError::Failed)?;
        let slices = slices(&said, room, engine);
        let cut = cut(&slices);
        if cut != self.cut {
            *self = Draft { cut, ..Draft::default() };
        }
        let several = slices.len() > 1;
        if let Some(slice) = slices.get(self.parts.len()) {
            let part = several.then_some((self.parts.len() + 1, slices.len()));
            let reply = engine.complete(&prompt::instructions(several), &prompt::transcript(slice, day, part), stop)?;
            self.parts.push(reply::read(&reply, lines).ok_or(EngineError::Failed)?);
            if several {
                return Ok(Step::More);
            }
        }
        self.merge(day, engine, stop)
    }

    // The summaries of the parts, as many in a call as fit, until one is left.
    fn merge(&mut self, day: Date, engine: &dyn TextEngine, stop: &Stop) -> Result<Step, EngineError> {
        let parts = &self.parts;
        let merging = self.merging.get_or_insert_with(|| {
            parts.iter().map(|part| part.summary.join(" ")).filter(|summary| !summary.is_empty()).collect()
        });
        let instructions = prompt::merging();
        let room = room(engine, &instructions, "").ok_or(EngineError::Failed)?;
        while merging.len() > 1 {
            let fits = |count: &usize| engine.tokens(&prompt::summaries(&merging[..*count], day)) <= room;
            let Some(count) = (2..=merging.len()).take_while(fits).last() else {
                // Two summaries longer than a window: kept side by side rather than lost.
                let joined = merging[..2].join(" ");
                merging.splice(..2, [joined]);
                continue;
            };
            let reply = engine.complete(&instructions, &prompt::summaries(&merging[..count], day), stop)?;
            let merged = reply::read(&reply, &[]).ok_or(EngineError::Failed)?.summary.join(" ");
            merging.splice(..count, [merged]);
            if merging.len() > 1 {
                return Ok(Step::More);
            }
        }
        Ok(Step::Done(self.written()))
    }

    // The parts end to end, each decision and action once: two slices may say the same.
    fn written(&mut self) -> MinutesDraft {
        let summary = self.merging.take().and_then(|mut merged| merged.pop()).unwrap_or_default();
        let parts = mem::take(&mut self.parts);
        let (mut decided, mut tasks) = (HashSet::new(), HashSet::new());
        let mut decisions = Vec::new();
        let mut actions = Vec::new();
        for part in parts {
            decisions.extend(part.decisions.into_iter().filter(|decision| decided.insert(same(decision))));
            actions.extend(part.actions.into_iter().filter(|action| tasks.insert(same(&action.text))));
        }
        MinutesDraft::Written { summary, decisions, actions }
    }
}

// What a call to `engine` leaves for a prompt opened by `header`, the reply and the margin set aside. None when the
// instructions alone fill it.
fn room(engine: &dyn TextEngine, instructions: &str, header: &str) -> Option<usize> {
    let window = engine.window();
    let filled = window.context.saturating_sub(window.reply) * FILLED_PERCENT / 100;
    filled.checked_sub(engine.tokens(instructions) + engine.tokens(header)).filter(|room| *room > 0)
}

// The lines in their order, as many to a slice as fit. A line longer than a slice is cut between two words, each
// piece at the time of its line: nothing is lost.
fn slices<'a>(said: &[Said<'a>], room: usize, engine: &dyn TextEngine) -> Vec<Vec<Said<'a>>> {
    let mut slices = Vec::new();
    let mut slice = Vec::new();
    let mut filled = 0;
    for piece in said.iter().flat_map(|said| pieces(*said, room, engine)) {
        let tokens = engine.tokens(&piece.line());
        if filled + tokens > room && !slice.is_empty() {
            slices.push(mem::take(&mut slice));
            filled = 0;
        }
        filled += tokens;
        slice.push(piece);
    }
    if !slice.is_empty() {
        slices.push(slice);
    }
    slices
}

fn pieces<'a>(said: Said<'a>, room: usize, engine: &dyn TextEngine) -> Vec<Said<'a>> {
    let fits = |text: &str| engine.tokens(&Said { text, ..said }.line()) <= room;
    if fits(said.text) {
        return vec![said];
    }
    let mut pieces = Vec::new();
    let mut rest = said.text;
    while !rest.is_empty() {
        let end = fitting(rest, &fits);
        let (piece, after) = rest.split_at(end);
        pieces.push(Said { text: piece.trim_end(), ..said });
        rest = after.trim_start();
    }
    pieces
}

// Where the longest start of `text` that fits ends: after a word, or within a word longer than a slice. Never at 0,
// so each piece holds something. `text` starts with a letter that is not a space.
fn fitting(text: &str, fits: &impl Fn(&str) -> bool) -> usize {
    let mut letters = text.char_indices().peekable();
    let mut words = Vec::new();
    while let Some((at, letter)) = letters.next() {
        let ends = letters.peek().is_none_or(|(_, next)| next.is_whitespace());
        if !letter.is_whitespace() && ends {
            words.push(at + letter.len_utf8());
        }
    }
    let fit = |end: &usize| text.get(..*end).is_some_and(fits);
    let ends = if words.first().is_some_and(fit) {
        words
    } else {
        text.char_indices().map(|(at, letter)| at + letter.len_utf8()).collect()
    };
    // Each end is longer than the one before: those that fit come first.
    let fitting = ends.partition_point(fit);
    ends.get(fitting.saturating_sub(1)).copied().unwrap_or(text.len())
}

// Stands for the slices as they are cut: the same transcript cut by the same engine gives the same.
fn cut(slices: &[Vec<Said>]) -> u64 {
    let mut hasher = DefaultHasher::new();
    for slice in slices {
        slice.len().hash(&mut hasher);
        for said in slice {
            (said.at_ms, said.text).hash(&mut hasher);
        }
    }
    hasher.finish()
}

// Said twice, perhaps with another case or a full stop of its own.
fn same(text: &str) -> String {
    normalized(text).trim_end_matches(['.', '!', ' ']).to_owned()
}

#[cfg(test)]
mod tests {
    use std::collections::VecDeque;
    use std::sync::Mutex;

    use time::macros::date;

    use super::*;
    use crate::engine::{About, Engine, Window};
    use crate::meeting::testing::line;
    use crate::meeting::{ActionDraft, Extra};

    const DAY: Date = date!(2026 - 11 - 06);

    // What one call does, before the engine reads the prompt.
    enum Answer {
        Read,
        Fail,
        Unavailable,
        Say(String),
    }

    // Writes the minutes a part of a transcript holds: a decision for each line that says « on décide », an action for
    // each one that says « je m'occupe », and as a summary the time of its first line. Merges by joining the times.
    struct Scribe {
        about: About,
        window: Window,
        answers: Mutex<VecDeque<Answer>>,
        asked: Mutex<Vec<(String, String)>>,
    }

    impl Scribe {
        fn new(context: usize, reply: usize, answers: Vec<Answer>) -> Self {
            let about = About { name: "scribe".into(), remote: false };
            let window = Window { context, reply };
            Scribe { about, window, answers: Mutex::new(answers.into()), asked: Mutex::default() }
        }

        fn asked(&self) -> Vec<(String, String)> {
            self.asked.lock().unwrap().clone()
        }

        fn prompts(&self) -> Vec<String> {
            self.asked().into_iter().map(|(_, prompt)| prompt).collect()
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

        fn complete(&self, system: &str, prompt: &str, _: &Stop) -> Result<String, EngineError> {
            self.asked.lock().unwrap().push((system.into(), prompt.into()));
            match self.answers.lock().unwrap().pop_front().unwrap_or(Answer::Read) {
                Answer::Read => Ok(written(prompt)),
                Answer::Fail => Err(EngineError::Failed),
                Answer::Unavailable => Err(EngineError::Unavailable),
                Answer::Say(reply) => Ok(reply),
            }
        }
    }

    fn written(prompt: &str) -> String {
        if prompt.contains("\nPart 1: ") {
            let parts: Vec<&str> =
                prompt.lines().filter_map(|line| line.split_once(": ").map(|(_, said)| said)).collect();
            return format!("SUMMARY: {}", parts.join(" + "));
        }
        let lines: Vec<(&str, &str)> =
            prompt.lines().filter_map(|line| line.strip_prefix('[')?.split_once("] ")).collect();
        let mut reply = format!("SUMMARY: {}\n", lines.first().map_or("-", |(time, _)| time));
        for (time, said) in &lines {
            if let Some(decided) = said.strip_prefix("On décide ") {
                reply.push_str(&format!("DECISION: {decided} | {time}\n"));
            }
            if let Some(task) = said.strip_prefix("Je m'occupe ") {
                reply.push_str(&format!("ACTION: {task} | - | - | {time}\n"));
            }
        }
        reply
    }

    // What a minute of a meeting holds, some 150 words, without a decision or a task.
    fn talk(at_ms: u64) -> TranscriptLine {
        line(at_ms, &["On fait le point sur le budget du trimestre et sur les recrutements prévus."; 10].join(" "))
    }

    fn written_whole(scribe: &Scribe, lines: &[TranscriptLine]) -> (MinutesDraft, usize) {
        let mut draft = Draft::default();
        let mut steps = 0;
        loop {
            steps += 1;
            assert!(steps < 1_000, "a draft that never ends");
            if let Step::Done(minutes) = draft.step(lines, DAY, scribe, &Stop::default()).unwrap() {
                return (minutes, steps);
            }
        }
    }

    fn decisions(minutes: &MinutesDraft) -> Vec<String> {
        let MinutesDraft::Written { decisions, .. } = minutes else { panic!("not written") };
        decisions.clone()
    }

    fn tasks(minutes: &MinutesDraft) -> Vec<String> {
        let MinutesDraft::Written { actions, .. } = minutes else { panic!("not written") };
        actions.iter().map(|action| action.text.clone()).collect()
    }

    fn summary(minutes: &MinutesDraft) -> String {
        let MinutesDraft::Written { summary, .. } = minutes else { panic!("not written") };
        summary.clone()
    }

    #[test]
    fn a_transcript_that_fits_is_one_call_with_the_instructions_of_the_whole() {
        let lines = [line(5_000, "Bonjour à tous."), line(65_000, "On décide de garder le violet.")];
        let scribe = Scribe::new(4_096, 1_024, vec![]);
        let (minutes, steps) = written_whole(&scribe, &lines);
        let said: Vec<Said> = lines.iter().filter_map(Said::of).collect();
        assert_eq!(scribe.asked(), [(prompt::instructions(false), prompt::transcript(&said, DAY, None))]);
        assert_eq!(steps, 1);
        let expected = MinutesDraft::Written {
            summary: "00:05".into(),
            decisions: vec!["de garder le violet.".into()],
            actions: vec![],
        };
        assert_eq!(minutes, expected);
    }

    #[test]
    fn an_hour_is_written_in_slices_and_nothing_of_its_end_is_lost() {
        // An hour, a line a minute; something decided and a task taken early, in the middle and in the last minute.
        let mut lines: Vec<TranscriptLine> = (0..60).map(|minute| talk(minute * 60_000)).collect();
        for (at_ms, said) in [
            (90_000, "On décide de repousser la démo."),
            (1_800_500, "Je m'occupe du devis."),
            (3_590_000, "On décide de recruter en janvier."),
            (3_595_000, "Je m'occupe de l'annonce."),
            // Said again in another slice, with another case: once in the minutes.
            (3_598_000, "On décide de repousser la démo"),
        ] {
            lines.push(line(at_ms, said));
        }
        lines.sort_by_key(|line| line.at_ms);
        let scribe = Scribe::new(4_096, 1_024, vec![]);
        let (minutes, steps) = written_whole(&scribe, &lines);
        let asked = scribe.asked();
        let slices = asked.len() - 1;
        assert_eq!(steps, asked.len(), "one call a step");
        assert!((5..=12).contains(&slices), "{slices} slices");
        let filled = (4_096 - 1_024) * FILLED_PERCENT / 100;
        for (rank, (system, prompt)) in asked[..slices].iter().enumerate() {
            assert_eq!(*system, prompt::instructions(true));
            assert!(prompt.contains(&format!("Part {} of {slices}.", rank + 1)));
            assert!(scribe.tokens(system) + scribe.tokens(prompt) <= filled, "slice {rank} past the window");
        }
        assert_eq!(asked[slices].0, prompt::merging());
        // Every line asked once, in its order.
        let heard: Vec<String> = asked[..slices]
            .iter()
            .flat_map(|(_, prompt)| {
                prompt.lines().filter(|line| line.starts_with('[')).map(str::to_owned).collect::<Vec<_>>()
            })
            .collect();
        let said: Vec<String> =
            lines.iter().filter_map(Said::of).map(|said| said.line().trim_end().to_owned()).collect();
        assert_eq!(heard, said);
        assert_eq!(decisions(&minutes), ["de repousser la démo.", "de recruter en janvier."]);
        assert_eq!(tasks(&minutes), ["du devis.", "de l'annonce."]);
        assert!(summary(&minutes).starts_with("00:00 + ") && summary(&minutes).matches(" + ").count() == slices - 1);
    }

    #[test]
    fn a_line_longer_than_a_slice_is_cut_between_its_words() {
        let words: Vec<String> = (0..3_000).map(|rank| format!("mot{rank}")).collect();
        let long = line(42_000, &words.join(" "));
        let scribe = Scribe::new(2_048, 1_024, vec![]);
        written_whole(&scribe, std::slice::from_ref(&long));
        let prompts = scribe.prompts();
        let pieces: Vec<&str> = prompts
            .iter()
            .filter(|prompt| !prompt.contains("\nPart 1: "))
            .flat_map(|prompt| prompt.lines().filter_map(|line| line.strip_prefix("[00:42] ")))
            .collect();
        assert!(pieces.len() > 1);
        assert_eq!(pieces.join(" "), long.text.unwrap());
    }

    #[test]
    fn a_word_longer_than_a_slice_is_cut_within_it() {
        let scribe = Scribe::new(2_048, 1_024, vec![]);
        let room = room(&scribe, &prompt::instructions(true), &prompt::header(DAY, Some((usize::MAX, usize::MAX))));
        let word = "é".repeat(5_000);
        let cut = pieces(Said { at_ms: 0, text: &word }, room.unwrap(), &scribe);
        assert!(cut.len() > 1);
        assert_eq!(cut.iter().map(|said| said.text).collect::<String>(), word);
        assert!(cut.iter().all(|said| scribe.tokens(&said.line()) <= room.unwrap()));
        // A room no letter fits: one letter a piece, still nothing lost.
        let tight = pieces(Said { at_ms: 0, text: "abc" }, 1, &scribe);
        assert_eq!(tight.iter().map(|said| said.text).collect::<Vec<_>>(), ["a", "b", "c"]);
    }

    // How many slices a transcript makes for an engine of this window.
    fn slices_of(lines: &[TranscriptLine], context: usize, reply: usize) -> usize {
        let probe = Scribe::new(context, reply, vec![]);
        written_whole(&probe, lines);
        probe.asked().iter().filter(|(system, _)| *system == prompt::instructions(true)).count()
    }

    #[test]
    fn summaries_longer_than_a_call_are_merged_a_group_at_a_time() {
        let lines: Vec<TranscriptLine> = (0..30).map(|minute| talk(minute * 60_000)).collect();
        let slices = slices_of(&lines, 2_048, 1_024);
        // Each part summed up at length, so that a few summaries fill a call; each merge short.
        let long = format!("SUMMARY: {}", ["Une synthèse soignée de cette partie."; 8].join(" "));
        let answers = (0..slices)
            .map(|_| Answer::Say(long.clone()))
            .chain((0..slices).map(|_| Answer::Say("SUMMARY: Fusion.".into())));
        let scribe = Scribe::new(2_048, 1_024, answers.collect());
        let (minutes, _) = written_whole(&scribe, &lines);
        let room = room(&scribe, &prompt::merging(), "").unwrap();
        let merges: Vec<String> = scribe
            .asked()
            .into_iter()
            .filter(|(system, _)| *system == prompt::merging())
            .map(|(_, prompt)| prompt)
            .collect();
        assert!(slices > 4 && merges.len() > 1, "{slices} slices, {} merges", merges.len());
        assert!(merges.iter().all(|prompt| scribe.tokens(prompt) <= room));
        // The merged summary is merged again with the next ones, until one is left.
        assert!(merges[1].starts_with(&format!("{}Part 1: Fusion.\n", prompt::header(DAY, None))));
        assert_eq!(summary(&minutes), "Fusion.");
    }

    #[test]
    fn nothing_said_is_written_empty_without_a_call() {
        let silent = TranscriptLine { at_ms: 0, text: None, speaker: None, extra: Extra::new() };
        let scribe = Scribe::new(4_096, 1_024, vec![]);
        let (minutes, _) = written_whole(&scribe, &[silent, line(30_000, " ")]);
        assert_eq!(minutes, MinutesDraft::owed(&[]));
        assert_eq!(scribe.asked(), []);
    }

    #[test]
    fn an_engine_away_for_a_while_takes_the_draft_up_where_it_was() {
        let lines: Vec<TranscriptLine> =
            (0..30).map(|minute| talk(minute * 60_000)).chain([line(1_799_000, "On décide de finir.")]).collect();
        let whole = Scribe::new(4_096, 1_024, vec![]);
        let (expected, _) = written_whole(&whole, &lines);
        let away = Scribe::new(4_096, 1_024, vec![Answer::Read, Answer::Read, Answer::Unavailable]);
        let mut draft = Draft::default();
        let stop = Stop::default();
        assert!(matches!(draft.step(&lines, DAY, &away, &stop), Ok(Step::More)));
        assert!(matches!(draft.step(&lines, DAY, &away, &stop), Ok(Step::More)));
        assert!(matches!(draft.step(&lines, DAY, &away, &stop), Err(EngineError::Unavailable)));
        let minutes = loop {
            if let Step::Done(minutes) = draft.step(&lines, DAY, &away, &stop).unwrap() {
                break minutes;
            }
        };
        assert_eq!(minutes, expected);
        // The slice it was away for is asked again, and no other.
        let mut prompts = away.prompts();
        assert_eq!(prompts.remove(2), prompts[2]);
        assert_eq!(prompts, whole.prompts());
    }

    #[test]
    fn a_transcript_changed_meanwhile_is_written_again_from_its_start() {
        let mut lines: Vec<TranscriptLine> = (0..30).map(|minute| talk(minute * 60_000)).collect();
        let scribe = Scribe::new(4_096, 1_024, vec![]);
        let mut draft = Draft::default();
        draft.step(&lines, DAY, &scribe, &Stop::default()).unwrap();
        draft.step(&lines, DAY, &scribe, &Stop::default()).unwrap();
        lines[0] = line(0, "Une phrase corrigée.");
        draft.step(&lines, DAY, &scribe, &Stop::default()).unwrap();
        let prompts = scribe.prompts();
        assert!(prompts[2].contains("Part 1 of") && prompts[2].contains("[00:00] Une phrase corrigée."));
    }

    #[test]
    fn a_reply_that_cannot_be_read_failed() {
        let lines = [line(0, "Bonjour à tous.")];
        let scribe = Scribe::new(4_096, 1_024, vec![Answer::Say("Voici le compte rendu.".into())]);
        assert!(matches!(Draft::default().step(&lines, DAY, &scribe, &Stop::default()), Err(EngineError::Failed)));
        // A merge as well.
        let lines: Vec<TranscriptLine> = (0..30).map(|minute| talk(minute * 60_000)).collect();
        let slices = slices_of(&lines, 4_096, 1_024);
        let answers = (0..slices).map(|_| Answer::Read).chain([Answer::Say("Je ne sais pas.".into())]);
        let failing = Scribe::new(4_096, 1_024, answers.collect());
        let mut draft = Draft::default();
        let failed = loop {
            match draft.step(&lines, DAY, &failing, &Stop::default()) {
                Ok(Step::More) => {}
                other => break other,
            }
        };
        assert!(slices > 1 && matches!(failed, Err(EngineError::Failed)));
        assert_eq!(failing.asked().len(), slices + 1);
    }

    #[test]
    fn an_engine_error_is_handed_back_as_it_is() {
        let lines = [line(0, "Bonjour à tous.")];
        let scribe = Scribe::new(4_096, 1_024, vec![Answer::Fail]);
        assert!(matches!(Draft::default().step(&lines, DAY, &scribe, &Stop::default()), Err(EngineError::Failed)));
    }

    #[test]
    fn a_window_the_instructions_fill_writes_nothing() {
        let lines = [line(0, "Bonjour à tous.")];
        let scribe = Scribe::new(600, 200, vec![]);
        assert!(matches!(Draft::default().step(&lines, DAY, &scribe, &Stop::default()), Err(EngineError::Failed)));
        assert_eq!(scribe.asked(), []);
    }

    #[test]
    fn each_decision_and_task_is_kept_once() {
        let draft = |decisions: &[&str], tasks: &[&str]| Part {
            summary: vec![],
            decisions: decisions.iter().map(|text| (*text).into()).collect(),
            actions: tasks
                .iter()
                .map(|text| ActionDraft { text: (*text).into(), owner: None, due: None, source: None })
                .collect(),
        };
        let mut written = Draft {
            cut: 0,
            parts: vec![
                draft(&["On garde le violet.", "On recrute"], &["Préparer la démo"]),
                draft(&["on garde  le violet"], &["préparer la démo."]),
            ],
            merging: Some(vec!["Résumé.".into()]),
        };
        let minutes = written.written();
        assert_eq!(decisions(&minutes), ["On garde le violet.", "On recrute"]);
        assert_eq!(tasks(&minutes), ["Préparer la démo"]);
        assert_eq!(summary(&minutes), "Résumé.");
    }
}
