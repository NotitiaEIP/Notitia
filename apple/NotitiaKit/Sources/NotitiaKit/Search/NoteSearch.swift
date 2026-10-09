import Foundation

/// Recherche plein texte locale, insensible à la casse et aux accents.
public enum NoteSearch {
    /// Normalise un texte : minuscules, sans accents.
    public static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
    }

    public static func tokens(_ query: String) -> [String] {
        fold(query)
            .split(whereSeparator: { $0.isWhitespace || $0.isPunctuation })
            .map(String.init)
    }

    /// Score de pertinence, ou `nil` si la note ne correspond pas.
    /// Tous les mots de la requête doivent apparaître (dans le titre ou le contenu) ;
    /// une correspondance dans le titre compte davantage.
    public static func score(title: String, body: String, query: String) -> Int? {
        let terms = tokens(query)
        guard !terms.isEmpty else { return 0 }
        let foldedTitle = fold(title)
        let foldedBody = fold(body)
        var score = 0
        for term in terms {
            let inTitle = foldedTitle.contains(term)
            let inBody = foldedBody.contains(term)
            guard inTitle || inBody else { return nil }
            if inTitle { score += 10 }
            if foldedTitle.hasPrefix(term) { score += 5 }
            if inBody { score += 1 }
        }
        return score
    }

    /// Extrait le passage de `body` autour de la première occurrence de la requête.
    public static func snippet(body: String, query: String, radius: Int = 60) -> String? {
        guard let term = tokens(query).first else { return nil }
        let folded = fold(body)
        guard let range = folded.range(of: term) else { return nil }
        // Le pliage peut changer la longueur ; on se replace via les décalages en caractères.
        let start = folded.distance(from: folded.startIndex, to: range.lowerBound)
        let characters = Array(body)
        guard start < characters.count else { return nil }
        let lower = max(0, start - radius)
        let upper = min(characters.count, start + term.count + radius)
        let prefix = lower > 0 ? "…" : ""
        let suffix = upper < characters.count ? "…" : ""
        return prefix + String(characters[lower..<upper]).replacingOccurrences(of: "\n", with: " ") + suffix
    }
}
