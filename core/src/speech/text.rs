use super::windows::Window;
use crate::audio::SAMPLES_PER_MS;
use crate::engine::Segment;
use crate::meeting::{Extra, TranscriptLine};

// What Whisper says on silence or noise, learned from the credits of video subtitles. A sentence is left out only when
// it is one of these and nothing more: a real sentence about subtitles is kept (#156).
const MADE_UP: &[&str] = &[
    "sous-titrage st' 501",
    "sous-titrage société radio-canada",
    "merci d'avoir regardé",
    "merci d'avoir regardé cette vidéo",
    "merci d'avoir regardé la vidéo",
    "abonnez-vous",
    "abonnez-vous à la chaîne",
    "thanks for watching",
    "thank you for watching",
];

// How credits open, whatever follows: no meeting says them.
const CREDITS: &[&str] = &["sous-titres réalisés par"];

// Named only by the credits of subtitles.
const CREDITED: &str = "amara.org";

// A second of speech holds two to four words: more than this alike is a sentence said again, not heard twice.
const LONGEST_REPEAT: usize = 6;

// What an engine is told was said before a window, in characters: a sentence or two keeps a model on its words.
const PREVIOUS: usize = 200;

// The lines a window adds to the transcript, from what the engine heard in it and the text of the last line kept
// before. Dated from the start of the meeting, and never before the edge of the window before: what the overlap holds
// was dated by it. The words it heard a second time at a cut in the middle of speech are left out.
pub fn lines(window: &Window, mut heard: Vec<Segment>, last: Option<&str>) -> Vec<TranscriptLine> {
    // Stable: an engine of the core may give its segments in any order, two at the same time keep theirs.
    heard.sort_by_key(|segment| segment.from_ms);
    let junction = junction(window);
    // An engine of the core dates as it likes: a line past the sound it heard would come before the next window's.
    let end = window.from_ms + (window.sound.len() / SAMPLES_PER_MS) as u64;
    let mut repeated = window.overlap_ms > 0;
    let mut lines = Vec::new();
    for segment in heard {
        let mut text = clean(&segment.text);
        if text.is_empty() {
            continue;
        }
        // The first words only: past them, a word said again was said again.
        if std::mem::take(&mut repeated)
            && u64::from(segment.from_ms) < window.overlap_ms
            && let Some(last) = last
        {
            text = without_repeat(last, &text);
            if text.is_empty() {
                continue;
            }
        }
        let at_ms = (window.from_ms + u64::from(segment.from_ms)).min(end).max(junction);
        lines.push(TranscriptLine { at_ms, text: Some(text), speaker: None, extra: Extra::new() });
    }
    lines
}

// The line of a window the engine failed to hear: no text, where the sound it adds starts. The transcript then says
// that something was said there.
pub fn unheard(window: &Window) -> TranscriptLine {
    TranscriptLine { at_ms: junction(window), text: None, speaker: None, extra: Extra::new() }
}

// What an engine is told was said before a window: the end of the transcript, PREVIOUS characters at most.
pub fn previous(transcript: &[TranscriptLine]) -> String {
    let mut said = Vec::new();
    let mut length = 0;
    for text in transcript.iter().rev().filter_map(|line| line.text.as_deref()) {
        if length >= PREVIOUS {
            break;
        }
        length += text.chars().count() + 1;
        said.push(text);
    }
    said.reverse();
    let said = said.join(" ");
    let skipped = said.chars().count().saturating_sub(PREVIOUS);
    said.chars().skip(skipped).collect()
}

// Where the sound a window adds starts: past its overlap, which the window before ended with.
fn junction(window: &Window) -> u64 {
    window.from_ms + window.overlap_ms
}

// The text without the sentences an engine makes up on silence.
fn clean(text: &str) -> String {
    let kept: Vec<&str> = sentences(text).filter(|sentence| !made_up(sentence)).collect();
    kept.join(" ")
}

// Cut after a full stop, an exclamation or a question mark followed by a space: amara.org stays whole.
fn sentences(text: &str) -> impl Iterator<Item = &str> {
    let mut rest = text.trim();
    std::iter::from_fn(move || {
        if rest.is_empty() {
            return None;
        }
        let ends = |(at, mark): (usize, char)| {
            let after = at + mark.len_utf8();
            (['.', '!', '?', '…'].contains(&mark) && rest[after..].starts_with(char::is_whitespace)).then_some(after)
        };
        let end = rest.char_indices().find_map(ends).unwrap_or(rest.len());
        let (sentence, after) = rest.split_at(end);
        rest = after.trim_start();
        Some(sentence.trim())
    })
}

fn made_up(sentence: &str) -> bool {
    let said = normalized(sentence);
    let said = said.trim_end_matches(['.', '!', '?', '…', ' ']);
    MADE_UP.contains(&said) || CREDITS.iter().any(|credit| said.starts_with(credit)) || said.contains(CREDITED)
}

// As words compare: in lower case, one apostrophe, one space between words.
fn normalized(text: &str) -> String {
    let words: Vec<String> = text.split_whitespace().map(|word| word.to_lowercase().replace('’', "'")).collect();
    words.join(" ")
}

// `text` without its first words when they are the last words of `before`, the most of them that match.
fn without_repeat(before: &str, text: &str) -> String {
    let word = |word: &str| normalized(word).trim_matches(|letter: char| !letter.is_alphanumeric()).to_owned();
    let before: Vec<String> = before.split_whitespace().map(word).collect();
    let words: Vec<&str> = text.split_whitespace().collect();
    let heard: Vec<String> = words.iter().map(|said| word(said)).collect();
    let longest = LONGEST_REPEAT.min(before.len()).min(heard.len());
    let repeat = (1..=longest).rev().find(|count| before[before.len() - count..] == heard[..*count]).unwrap_or(0);
    words[repeat..].join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::meeting::testing::line;
    use crate::testing::{Draw, cases};

    fn window(from_ms: u64, overlap_ms: u64) -> Window {
        Window { from_ms, sound: vec![0; 16 * 30_000], overlap_ms, until_ms: from_ms + 30_000 }
    }

    fn said(from_ms: u32, text: &str) -> Segment {
        Segment { from_ms, to_ms: from_ms + 2_000, text: text.into() }
    }

    fn texts(lines: &[TranscriptLine]) -> Vec<(u64, &str)> {
        lines.iter().map(|line| (line.at_ms, line.text.as_deref().unwrap_or_default())).collect()
    }

    #[test]
    fn what_whisper_makes_up_on_silence_is_dropped() {
        let heard = ["Sous-titrage ST' 501", "Merci d’avoir regardé cette vidéo !", "   ", "Le budget tient."];
        let heard = heard.iter().enumerate().map(|(rank, text)| said(rank as u32 * 3_000, text)).collect();
        assert_eq!(texts(&lines(&window(0, 0), heard, None)), [(9_000, "Le budget tient.")]);
    }

    #[test]
    fn a_real_sentence_about_subtitles_is_kept() {
        // #156: only a sentence that is the credit and nothing more goes.
        let kept = [
            "On doit livrer le sous-titrage de la vidéo avant vendredi.",
            "Les sous-titres réalisés par l'agence sont en retard ?",
            "Merci d'avoir regardé le budget, Paul.",
            "Abonnez-vous au compte rendu.",
        ];
        for text in kept {
            assert_eq!(clean(text), text);
        }
        assert_eq!(clean("Le budget tient. Sous-titres réalisés par la communauté d'Amara.org"), "Le budget tient.");
        assert_eq!(clean("Sous-titrage Société Radio-Canada. On reprend ? Thanks for watching!"), "On reprend ?");
        assert_eq!(clean("  Bonjour   à tous.  "), "Bonjour   à tous.");
    }

    #[test]
    fn the_lines_are_dated_from_the_start_of_the_meeting() {
        let heard = vec![said(4_500, "Ensuite le planning."), said(1_200, "D'abord le budget.")];
        let found = lines(&window(60_000, 0), heard, Some("Bonjour."));
        assert_eq!(texts(&found), [(61_200, "D'abord le budget."), (64_500, "Ensuite le planning.")]);
        assert!(found.iter().all(|line| line.speaker.is_none() && line.extra.is_empty()));
    }

    #[test]
    fn the_words_said_twice_at_a_forced_cut_are_removed() {
        let last = Some("Nous devons livrer le budget");
        let heard = vec![said(0, "livrer le budget avant vendredi."), said(2_500, "Et le budget aussi.")];
        let found = lines(&window(28_960, 1_000), heard, last);
        assert_eq!(texts(&found), [(29_960, "avant vendredi."), (31_460, "Et le budget aussi.")]);
        // Whatever the case, the marks and the apostrophes.
        let found = lines(&window(0, 1_000), vec![said(0, "Qu'on livre, et ensuite")], Some("… il faut qu’on livre."));
        assert_eq!(texts(&found), [(1_000, "et ensuite")]);
    }

    #[test]
    fn the_most_words_said_twice_are_removed_and_no_more() {
        let repeat = |last: &str, text: &str| without_repeat(last, text);
        assert_eq!(
            repeat("a b c d e f g", "a b c d e f g h"),
            "a b c d e f g h",
            "seven words are a sentence said again"
        );
        assert_eq!(repeat("a b c d e f g", "c d e f g h"), "h");
        assert_eq!(repeat("le plan", "le plan le plan"), "le plan");
        assert_eq!(repeat("rien", "à voir"), "à voir");
        assert_eq!(repeat("", "à voir"), "à voir");
        assert_eq!(repeat("à voir", "à voir"), "");
    }

    #[test]
    fn a_segment_heard_again_whole_is_left_out() {
        let heard = vec![said(0, "le budget"), said(900, "avant vendredi.")];
        let found = lines(&window(10_000, 1_000), heard, Some("livrer le budget"));
        assert_eq!(texts(&found), [(11_000, "avant vendredi.")]);
    }

    #[test]
    fn the_words_after_a_pause_are_never_removed() {
        let found = lines(&window(5_000, 0), vec![said(200, "Merci beaucoup.")], Some("Merci."));
        assert_eq!(texts(&found), [(5_200, "Merci beaucoup.")]);
    }

    #[test]
    fn a_segment_after_the_overlap_is_kept_whole() {
        let found = lines(&window(0, 1_000), vec![said(1_400, "budget avant tout.")], Some("le budget"));
        assert_eq!(texts(&found), [(1_400, "budget avant tout.")]);
    }

    #[test]
    fn a_line_never_starts_before_the_junction() {
        let heard = vec![said(0, "Bien."), said(600, "Alors on y va.")];
        let found = lines(&window(28_960, 1_000), heard, None);
        assert_eq!(texts(&found), [(29_960, "Bien."), (29_960, "Alors on y va.")]);
    }

    #[test]
    fn a_line_is_never_dated_past_the_sound_of_its_window() {
        let heard = vec![said(29_000, "Bien."), said(4_000_000_000, "Alors on y va.")];
        let found = lines(&window(60_000, 0), heard, None);
        assert_eq!(texts(&found), [(89_000, "Bien."), (90_000, "Alors on y va.")]);
    }

    #[test]
    fn a_window_the_engine_failed_to_hear_says_where_something_was_said() {
        let failed = unheard(&window(28_960, 1_000));
        assert_eq!((failed.at_ms, failed.text, failed.speaker), (29_960, None, None));
        assert_eq!(unheard(&window(5_000, 0)).at_ms, 5_000);
    }

    #[test]
    fn an_engine_is_told_the_end_of_what_was_said_before() {
        let silent = TranscriptLine { at_ms: 3_000, text: None, speaker: None, extra: Extra::new() };
        let transcript = [line(0, "Bonjour à tous."), silent.clone(), line(5_000, "On parle du budget.")];
        assert_eq!(previous(&transcript), "Bonjour à tous. On parle du budget.");
        assert_eq!(previous(&[]), "");
        assert_eq!(previous(&[silent]), "");
        // The last characters, whole letters, whatever the lines before.
        let long = [line(0, &"a".repeat(500)), line(1_000, &"é".repeat(150))];
        assert_eq!(previous(&long), format!("{} {}", "a".repeat(49), "é".repeat(150)));
        assert_eq!(previous(&[line(0, &"œ".repeat(300))]), "œ".repeat(200));
    }

    #[test]
    fn any_text_an_engine_gives_is_read_without_a_panic() {
        let pieces = [
            "a",
            "é",
            "Œuvre",
            "qu’on",
            "l'",
            " ",
            "  ",
            "\t",
            "\n",
            ".",
            "!",
            "?",
            "…",
            "…a",
            ",",
            "-",
            "😀",
            "amara.org",
            "sous-titrage",
            "ST'",
            "501",
        ];
        let mut draw = Draw(0x0074_6578_7473);
        let mut text = || (0..draw.below(24)).map(|_| pieces[draw.below(pieces.len())]).collect::<String>();
        for _ in 0..cases(2_000) {
            let (last, heard) = (text(), vec![said(0, &text()), said(10, &text()), said(5, &text())]);
            for line in lines(&window(1_000, 1_000), heard, Some(&last)) {
                assert!(line.at_ms >= 2_000 && line.text.is_some_and(|text| !text.is_empty() && text == text.trim()));
            }
        }
    }
}
