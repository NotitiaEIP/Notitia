use time::Date;

use super::prompt::second;
use super::{ACTION, DECISION, NONE, SUMMARY};
use crate::meeting::{ActionDraft, ActionSource, Extra, TranscriptLine, civil_date};
use crate::speech::text::normalized;

// What a model answered, read line by line. Lines it adds around the asked ones are dropped, and so is a date it wrote
// in another format.

// Asked after a task: who is in charge, by when, and the time of the line where it was agreed.
const ACTION_FIELDS: usize = 3;

// Some open models think aloud before they answer.
const THINKING: &str = "<think>";
const THOUGHT: &str = "</think>";

// What a model writes for an empty list despite the instructions, as a whole sentence.
const NOTHING: &[&str] = &["-", "none", "n/a", "na", "néant", "rien", "aucun", "aucune", "pas de"];
// « Aucune décision n'a été prise », « Pas d'action à signaler »: how such a sentence opens, then what it is about.
const NO: &[&str] = &["aucune", "aucun", "pas de", "pas d'"];
const KINDS: &[&str] = &["décision", "décisions", "action", "actions", "tâche", "tâches"];
// And how it goes on.
const UNSAID: &[&str] = &[
    "n'a",
    "n'ont",
    "n'est",
    "n'sont",
    "prise",
    "prises",
    "retenue",
    "retenues",
    "identifiée",
    "identifiées",
    "définie",
    "définies",
    "attribuée",
    "attribuées",
];

// The minutes of what one call read.
#[derive(Default)]
#[cfg_attr(test, derive(Debug, PartialEq))]
pub struct Part {
    pub summary: Vec<String>,
    pub decisions: Vec<String>,
    pub actions: Vec<ActionDraft>,
}

// None when no line of the reply is one asked for: a model that rambled, or was cut before its first line, failed
// and says nothing of the meeting. `lines`: the transcript, where a time points.
pub fn read(reply: &str, lines: &[TranscriptLine]) -> Option<Part> {
    let mut part = Part::default();
    let mut asked = false;
    for line in without_reasoning(reply).lines() {
        let line = line.trim().trim_start_matches(['-', '*', '•', ' ']);
        if let Some(summary) = after(line, SUMMARY) {
            let summary = summary.trim();
            if !summary.is_empty() && summary != NONE {
                part.summary.push(summary.to_owned());
            }
        } else if let Some(fields) = after(line, DECISION) {
            part.decisions.extend(decision(fields, lines));
        } else if let Some(fields) = after(line, ACTION) {
            part.actions.extend(action(fields, lines));
        } else {
            continue;
        }
        asked = true;
    }
    asked.then_some(part)
}

// The rest of a line that opens with `marker`, in any case.
fn after<'a>(line: &'a str, marker: &str) -> Option<&'a str> {
    let opens = line.get(..marker.len()).is_some_and(|opening| opening.eq_ignore_ascii_case(marker));
    opens.then(|| line.get(marker.len()..)).flatten()
}

// Kept only when it names the time of the line it was taken at, as an action does: a decision the model made up has
// no sentence to point to (#183). Version 2 of the format has no place for that sentence: it is not kept.
fn decision(fields: &str, lines: &[TranscriptLine]) -> Option<String> {
    let (text, time) = fields.rsplit_once('|')?;
    let text = text.trim();
    let said = !text.is_empty() && !nothing_said(text) && quoted(time, lines).is_some();
    said.then(|| text.to_owned())
}

// A task may hold a bar of its own: the fields asked of the model are always the last ones.
fn action(fields: &str, lines: &[TranscriptLine]) -> Option<ActionDraft> {
    let parts: Vec<&str> = fields.split('|').map(str::trim).collect();
    let (task, fields) = parts.split_at_checked(parts.len().saturating_sub(ACTION_FIELDS).max(1))?;
    let text = task.join(" | ");
    if text.is_empty() || nothing_said(&text) {
        return None;
    }
    let field = |rank: usize| fields.get(rank).copied().filter(|field| !field.is_empty() && *field != NONE);
    Some(ActionDraft {
        text,
        owner: field(0).map(str::to_owned),
        due: field(1).and_then(|due| Date::parse(due, civil_date::FORMAT).ok()),
        source: field(2).and_then(|time| quoted(time, lines)),
    })
}

// The sentence a time points to, copied from the transcript: the model may point to a line, never write one.
fn quoted(time: &str, lines: &[TranscriptLine]) -> Option<ActionSource> {
    let second = second(time)?;
    let (line, quote) =
        lines.iter().filter(|line| line.at_ms / 1_000 == second).find_map(|line| Some((line, line.said()?)))?;
    Some(ActionSource {
        quote: quote.to_owned(),
        speaker: line.speaker.clone(),
        at_ms: line.at_ms,
        extra: Extra::new(),
    })
}

// What the model weighed is not its answer. A reasoning never closed, cut by the length of the reply, holds none.
fn without_reasoning(reply: &str) -> String {
    // In lower case for ASCII alone, every byte stays where it was: a place found in one is a place in the other,
    // and at an ASCII mark, never inside a letter.
    let lower = reply.to_ascii_lowercase();
    let mut kept = String::with_capacity(reply.len());
    let mut at = 0;
    while let Some(start) = lower[at..].find(THINKING).map(|found| at + found) {
        kept.push_str(&reply[at..start]);
        let Some(end) = lower[start..].find(THOUGHT) else { return kept };
        at = start + end + THOUGHT.len();
    }
    kept.push_str(&reply[at..]);
    kept
}

// Only the whole sentence counts: « Aucune action marketing avant janvier » stays a decision.
fn nothing_said(text: &str) -> bool {
    let said = normalized(text);
    let said = said.strip_suffix('.').unwrap_or(&said);
    if NOTHING.contains(&said) || said.strip_prefix("pas d'").is_some_and(|word| letters(word.trim_start())) {
        return true;
    }
    let Some(rest) = NO.iter().find_map(|no| said.strip_prefix(no)) else { return false };
    let mut words = rest.split_whitespace();
    if !words.next().is_some_and(|kind| KINDS.contains(&kind)) {
        return false;
    }
    let words: Vec<&str> = words.collect();
    match words.as_slice() {
        [] => true,
        [first, after @ ..] => unsaid(&words) || (letters(first) && (after.is_empty() || unsaid(after))),
    }
}

// « n'a été prise », « attribuée », « ne sont pas », « à signaler ».
fn unsaid(words: &[&str]) -> bool {
    let word =
        |rank: usize| words.get(rank).map(|word| word.trim_end_matches(|letter: char| !letter.is_alphanumeric()));
    match word(0) {
        Some("ne") => words.len() > 1,
        Some("à") => word(1) == Some("signaler"),
        Some(first) => UNSAID.contains(&first),
        None => false,
    }
}

fn letters(word: &str) -> bool {
    !word.is_empty() && word.chars().all(char::is_alphabetic)
}

#[cfg(test)]
mod tests {
    use time::macros::date;

    use super::*;
    use crate::meeting::testing::line;
    use crate::testing::{Draw, cases};

    fn read_alone(reply: &str) -> Part {
        read(reply, &[]).unwrap()
    }

    fn draft(text: &str, owner: Option<&str>, due: Option<Date>) -> ActionDraft {
        ActionDraft { text: text.into(), owner: owner.map(Into::into), due, source: None }
    }

    #[test]
    fn every_kind_of_line_is_read() {
        let lines = [line(5_000, "On fixe la démo au 15 novembre."), line(9_000, "Et on garde le violet.")];
        let part = read(
            "SUMMARY: L’équipe a préparé la démo.
DECISION: La démo a lieu le 15 novembre. | 00:05
DECISION: On garde le violet. | 00:09
ACTION: Préparer la démo | Lucas | 2026-11-13
ACTION: Réserver la salle | - | -",
            &lines,
        )
        .unwrap();
        assert_eq!(part.summary, ["L’équipe a préparé la démo."]);
        assert_eq!(part.decisions, ["La démo a lieu le 15 novembre.", "On garde le violet."]);
        assert_eq!(
            part.actions,
            [
                draft("Préparer la démo", Some("Lucas"), Some(date!(2026 - 11 - 13))),
                draft("Réserver la salle", None, None)
            ]
        );
    }

    #[test]
    fn an_action_takes_its_sentence_from_the_transcript_not_from_the_model() {
        let lines = [line(5_000, "Bonjour à tous."), line(123_400, "Lucas, tu prépares la démo pour vendredi ?")];
        let part = read("ACTION: Préparer la démo | Lucas | 2026-11-13 | [02:03]", &lines).unwrap();
        let source = ActionSource {
            quote: "Lucas, tu prépares la démo pour vendredi ?".into(),
            speaker: None,
            at_ms: 123_400,
            extra: Extra::new(),
        };
        assert_eq!(part.actions[0].source, Some(source));
        // Written another way, the same second.
        let other = read("ACTION: Préparer la démo | Lucas | - | 2:03", &lines).unwrap();
        assert_eq!(other.actions[0].source.as_ref().map(|source| source.at_ms), Some(123_400));
    }

    #[test]
    fn a_time_that_points_to_no_sentence_gives_no_source() {
        let failed = TranscriptLine { at_ms: 30_000, text: None, speaker: None, extra: Extra::new() };
        let lines = [line(5_000, "Bonjour à tous."), failed];
        let part = read(
            "ACTION: Réserver la salle | - | - | 04:00
ACTION: Relire la PR | Adam | - | 00:30
ACTION: Envoyer le devis | Adam | - | -
ACTION: Choisir la police | Clément | -",
            &lines,
        )
        .unwrap();
        assert!(part.actions.iter().all(|action| action.source.is_none()));
        assert_eq!(part.actions.len(), 4);
        assert_eq!(part.actions[3].text, "Choisir la police");
    }

    #[test]
    fn a_bar_in_the_task_keeps_the_fields_in_place() {
        let lines = [line(12_000, "On choisit entre A et B, Paul tranche.")];
        let action = read("ACTION: Choisir A | B | Paul | 2026-11-20 | 00:12", &lines).unwrap().actions.remove(0);
        assert_eq!(
            (action.text.as_str(), action.owner.as_deref(), action.due),
            ("Choisir A | B", Some("Paul"), Some(date!(2026 - 11 - 20)))
        );
        assert_eq!(action.source.map(|source| source.at_ms), Some(12_000));
    }

    #[test]
    fn a_date_in_another_format_is_dropped_but_not_its_action() {
        let action = read_alone("ACTION: Envoyer le devis | Adam | vendredi prochain").actions.remove(0);
        assert_eq!((action.owner.as_deref(), action.due), (Some("Adam"), None));
    }

    #[test]
    fn reasoning_bullets_and_other_lines_are_ignored() {
        let lines = [line(0, "On garde le violet.")];
        let part = read(
            "<think>DECISION: pas une vraie décision | 00:00</think>
Voici le compte rendu :
- DECISION: On garde le violet. | 00:00
* action: Choisir la police
ACTION:  | Lucas | -",
            &lines,
        )
        .unwrap();
        assert_eq!(part.decisions, ["On garde le violet."]);
        assert_eq!(part.actions, [draft("Choisir la police", None, None)]);
        assert!(part.summary.is_empty());
        // A reasoning cut before its end hides all that follows, and answers nothing.
        assert_eq!(read("<THINK>SUMMARY: peut-être…", &lines), None);
        let twice = read("<think>a</think>SUMMARY: Un.\n<Think>b</think>\nSUMMARY: Deux.", &lines).unwrap();
        assert_eq!(twice.summary, ["Un.", "Deux."]);
    }

    #[test]
    fn what_the_model_writes_for_an_empty_list_is_not_an_item() {
        // Each pointing to a line said: the sentence alone leaves them out.
        let part = read(
            "SUMMARY: -
DECISION: -
DECISION: Aucune décision n’a été prise lors de cette réunion. | 00:00
DECISION: Aucune décision formelle n'a été prise. | 00:00
DECISION: Pas de décision. | 00:00
DECISION: Néant | 00:00
ACTION: -
ACTION: Aucune action. | - | -
ACTION: Aucune tâche attribuée.
ACTION: Pas d’action à signaler. | - | - | 00:00
ACTION: N/A",
            &[line(0, "Rien de neuf cette semaine.")],
        );
        assert_eq!(part, Some(Part::default()));
    }

    #[test]
    fn a_real_item_that_opens_with_aucune_is_kept() {
        let lines = [line(0, "Pas de marketing."), line(1_000, "Pas un euro de plus.")];
        let part = read(
            "DECISION: Aucune action marketing avant janvier. | 00:00
DECISION: Aucun budget supplémentaire n’est accordé. | 00:01
ACTION: Rien changer au logo avant la démo | Adam | -",
            &lines,
        )
        .unwrap();
        assert_eq!(
            part.decisions,
            ["Aucune action marketing avant janvier.", "Aucun budget supplémentaire n’est accordé."]
        );
        assert_eq!(part.actions, [draft("Rien changer au logo avant la démo", Some("Adam"), None)]);
    }

    #[test]
    fn a_decision_that_points_to_no_sentence_was_made_up_and_is_left_out() {
        let failed = TranscriptLine { at_ms: 30_000, text: None, speaker: None, extra: Extra::new() };
        let lines = [line(5_000, "On garde le violet."), failed];
        let part = read(
            "DECISION: Le budget passe à 50 000 €.
DECISION: Le budget passe à 50 000 €. | -
DECISION: Le budget passe à 50 000 €. | 12:00
DECISION: Le budget passe à 50 000 €. | 00:30
DECISION: On garde le violet | 00:05",
            &lines,
        )
        .unwrap();
        assert_eq!(part.decisions, ["On garde le violet"]);
    }

    #[test]
    fn a_reply_with_no_line_asked_for_says_nothing() {
        for reply in ["", "Voici le compte rendu de la réunion.", "<think>tout</think>", "SUMMARY"] {
            assert_eq!(read(reply, &[]), None, "{reply}");
        }
        assert_eq!(read("SUMMARY: -\nDECISION: -\nACTION: -", &[]), Some(Part::default()));
    }

    #[test]
    fn any_reply_is_read_without_a_panic() {
        // Made of what the reader looks for, cut anywhere, letters of several bytes included.
        let words = [
            "SUMMARY:",
            "DECISION:",
            "ACTION:",
            "|",
            "-",
            "<think>",
            "</think>",
            "<THINK>",
            "\n",
            " ",
            "00:05",
            "1:02:03",
            "2026-11-13",
            "aucune",
            "décision",
            "n’a",
            "pas d'",
            "é",
            "€",
            "𝄞",
            "[",
            "]",
            ":",
            "*",
            "•",
        ];
        let lines = [line(5_000, "Bonjour à tous."), line(3_723_000, "La suite.")];
        let mut draw = Draw(0x6d69_6e75_7465);
        for _ in 0..cases(20_000) {
            let reply: String = (0..draw.below(40)).map(|_| words[draw.below(words.len())]).collect();
            let cut = reply
                .char_indices()
                .map(|(at, _)| at)
                .nth(draw.below(reply.chars().count() + 1))
                .unwrap_or(reply.len());
            let _ = read(&reply[..cut], &lines);
        }
    }
}
