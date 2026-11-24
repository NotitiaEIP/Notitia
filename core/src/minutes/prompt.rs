use time::Date;

use super::{ACTION, DECISION, NONE, SUMMARY};
use crate::meeting::TranscriptLine;

// What a model is asked, in English, which the open models follow best, for minutes in French.

// The minutes of the whole transcript, or of one part of a longer one.
pub fn instructions(part: bool) -> String {
    let scope = if part {
        "This transcript is one part of a longer meeting: write the minutes of this part alone.\n"
    } else {
        ""
    };
    format!(
        "You write the minutes of a meeting, in French, from its transcript. Each transcript line starts with its time.
{scope}Keep only what was actually said: never make up a decision, a task, a name or a date.
The speakers are not identified: name the person in charge of a task only if the transcript names them.
Reply only with these lines, and nothing else:
{SUMMARY} two or three sentences on what the meeting was about.
{DECISION} decision | time of the transcript line where it was taken, as written at its start
One {DECISION} line per decision taken. If nothing was decided, write exactly: {DECISION} {NONE}
{ACTION} task | first name of the person in charge, or {NONE} | due date as YYYY-MM-DD, or {NONE} | time of the \
transcript line where it was agreed, as written at its start, or {NONE}
One {ACTION} line per task someone agreed to do. If there is none, write exactly: {ACTION} {NONE}"
    )
}

// The summaries of the parts of one meeting, made one.
pub fn merging() -> String {
    format!(
        "You merge the summaries of the parts of one meeting into one, in French.
Keep only what the summaries say: never add anything.
Reply only with this line, and nothing else:
{SUMMARY} two or three sentences on what the whole meeting was about."
    )
}

// A line of the transcript as a model reads it: a passage the engine could not transcribe is left out.
#[derive(Clone, Copy)]
#[cfg_attr(test, derive(Debug, PartialEq))]
pub struct Said<'a> {
    pub at_ms: u64,
    pub text: &'a str,
}

impl<'a> Said<'a> {
    pub fn of(line: &'a TranscriptLine) -> Option<Self> {
        line.said().map(|text| Said { at_ms: line.at_ms, text })
    }

    pub fn line(&self) -> String {
        format!("[{}] {}\n", time(self.at_ms), self.text)
    }
}

// The day lets a model turn « vendredi » into a date. `part`: which one, of how many.
pub fn header(day: Date, part: Option<(usize, usize)>) -> String {
    let weekday = day.weekday().to_string().to_lowercase();
    let part = part.map_or(String::new(), |(rank, of)| format!(" Part {rank} of {of}."));
    format!("Meeting of {day} ({weekday}).{part}\n\n")
}

pub fn transcript(lines: &[Said], day: Date, part: Option<(usize, usize)>) -> String {
    let mut prompt = header(day, part);
    lines.iter().for_each(|said| prompt.push_str(&said.line()));
    prompt
}

pub fn summaries(summaries: &[String], day: Date) -> String {
    let mut prompt = header(day, None);
    for (rank, summary) in summaries.iter().enumerate() {
        prompt.push_str(&format!("Part {}: {summary}\n", rank + 1));
    }
    prompt
}

// As the app shows it: 02:03, then 1:02:03 past an hour.
pub fn time(at_ms: u64) -> String {
    let seconds = at_ms / 1_000;
    let (hours, minutes, seconds) = (seconds / 3_600, seconds % 3_600 / 60, seconds % 60);
    if hours > 0 { format!("{hours}:{minutes:02}:{seconds:02}") } else { format!("{minutes:02}:{seconds:02}") }
}

// The second a model names, as `time` writes it or near enough: 00:05, 0:05, [00:05], 1:02:03. None for anything
// else, which then points to no line.
pub fn second(text: &str) -> Option<u64> {
    let text = text.trim().trim_start_matches('[').trim_end_matches(']').trim();
    let number = |field: &str| -> Option<u64> {
        let short = !field.is_empty() && field.len() <= 2 && field.bytes().all(|byte| byte.is_ascii_digit());
        short.then(|| field.parse().ok()).flatten()
    };
    let fields: Vec<&str> = text.split(':').collect();
    let (hours, minutes, seconds) = match fields.as_slice() {
        [minutes, seconds] => (0, number(minutes)?, number(seconds)?),
        [hours, minutes, seconds] => {
            (number(hours)?, number(minutes).filter(|minutes| *minutes < 60)?, number(seconds)?)
        }
        _ => return None,
    };
    (seconds < 60).then_some((hours * 60 + minutes) * 60 + seconds)
}

#[cfg(test)]
mod tests {
    use time::macros::date;

    use super::*;
    use crate::meeting::Extra;
    use crate::meeting::testing::line;

    const DAY: Date = date!(2026 - 11 - 06);

    #[test]
    fn the_prompt_gives_the_day_and_the_timed_lines_but_not_the_failed_ones() {
        let failed = TranscriptLine { at_ms: 30_000, text: None, speaker: None, extra: Extra::new() };
        let lines =
            [line(5_000, "Bonjour à tous."), failed, line(123_000, "Lucas prépare la démo."), line(124_000, " ")];
        let said: Vec<Said> = lines.iter().filter_map(Said::of).collect();
        assert_eq!(
            transcript(&said, DAY, None),
            "Meeting of 2026-11-06 (friday).\n\n[00:05] Bonjour à tous.\n[02:03] Lucas prépare la démo.\n"
        );
        assert!(transcript(&said, DAY, Some((2, 5))).starts_with("Meeting of 2026-11-06 (friday). Part 2 of 5.\n\n"));
    }

    #[test]
    fn a_part_is_told_it_is_one() {
        let (whole, part) = (instructions(false), instructions(true));
        assert!(!whole.contains("one part") && part.contains("one part of a longer meeting"));
        assert_eq!(
            part.replace(
                "This transcript is one part of a longer meeting: write the minutes of this part alone.\n",
                ""
            ),
            whole
        );
        for marker in [SUMMARY, DECISION, ACTION] {
            assert!(whole.lines().any(|line| line.starts_with(marker)), "{marker}");
        }
        assert!(merging().contains(SUMMARY));
        let merged = summaries(&["Le budget.".into(), "La démo.".into()], DAY);
        assert_eq!(merged, "Meeting of 2026-11-06 (friday).\n\nPart 1: Le budget.\nPart 2: La démo.\n");
    }

    #[test]
    fn a_time_is_written_as_the_app_shows_it_and_read_back_near_enough() {
        for (at_ms, written) in [(0, "00:00"), (5_999, "00:05"), (123_000, "02:03"), (3_723_000, "1:02:03")] {
            assert_eq!(time(at_ms), written);
            assert_eq!(second(written), Some(at_ms / 1_000));
        }
        for (text, read) in
            [("0:05", Some(5)), (" [00:05] ", Some(5)), ("75:00", Some(4_500)), ("[1:02:03]", Some(3_723))]
        {
            assert_eq!(second(text), read, "{text}");
        }
        for text in ["", "-", "5", "00:60", "1:60:00", "a:05", "-1:05", "1:2:3:4", "123:00", "00:05 environ", "１:05"]
        {
            assert_eq!(second(text), None, "{text}");
        }
    }
}
