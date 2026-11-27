// Who an action is for, told by the first name as it was written and never by the voice (product rule 4): "clément",
// "Clément" and "Clement" are one person, whatever speaker said it.

// The letters of the languages of the users that carry a mark. The same table as the Kotlin it replaces: a name keeps
// the key it had, so no one changes colour. A full Unicode decomposition would take a crate and move every key.
fn plain(letter: char) -> Option<&'static str> {
    Some(match letter {
        'à' | 'â' | 'ä' | 'á' => "a",
        'ç' => "c",
        'é' | 'è' | 'ê' | 'ë' => "e",
        'î' | 'ï' | 'í' => "i",
        'ô' | 'ö' | 'ó' => "o",
        'ù' | 'û' | 'ü' | 'ú' => "u",
        'ÿ' => "y",
        'ñ' => "n",
        'œ' => "oe",
        'æ' => "ae",
        _ => return None,
    })
}

// Words are told apart by one space of any kind, then lower-cased, then stripped of their marks, in that order: an
// upper-case letter with a mark is first made lower-case, then plain.
pub fn key(name: &str) -> String {
    let words: Vec<&str> = name.split_whitespace().collect();
    let mut key = String::with_capacity(name.len());
    for letter in words.join(" ").to_lowercase().chars() {
        match plain(letter) {
            Some(plain) => key.push_str(plain),
            None => key.push(letter),
        }
    }
    key
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_names_match_whatever_their_case_accents_or_spaces() {
        assert_eq!(key("  Clément "), "clement");
        assert_eq!(key("clement"), key("CLÉMENT"));
        assert_eq!(key("Anne   Marie"), "anne marie");
        assert_eq!(key("Zoé"), "zoe");
    }

    #[test]
    fn a_space_of_any_kind_is_a_space() {
        // A no-break space, a tab, an ideographic space: one definition of a space for every app.
        assert_eq!(key("Anne\u{a0} Marie"), "anne marie");
        assert_eq!(key("\tAnne\u{3000}Marie\n"), "anne marie");
    }

    #[test]
    fn every_letter_of_the_table_is_plain_and_the_ligatures_are_two_letters() {
        assert_eq!(key("àâäáçéèêëîïíôöóùûüúÿñ"), "aaaaceeeeiiiooouuuuyn");
        assert_eq!(key("Œuvre Æon"), "oeuvre aeon");
    }

    #[test]
    fn what_the_table_does_not_know_is_kept_as_it_is() {
        assert_eq!(key("Łukasz"), "łukasz");
        assert_eq!(key("李"), "李");
        assert_eq!(key(""), "");
        assert_eq!(key("   "), "");
    }

    #[test]
    fn a_name_with_an_initial_is_someone_else() {
        assert_ne!(key("Clément B."), key("Clément D."));
    }

    // Past the first plane, UTF-8 and UTF-16 do not sort the same: the core sorts by code point, so the phone and the
    // computer agree because they read this order and sort nothing themselves.
    #[test]
    fn keys_sort_by_code_point() {
        let (private_use, emoji) = (key("\u{e000}"), key("😀"));
        assert!(private_use < emoji);
    }
}
