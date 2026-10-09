import Foundation
import NaturalLanguage

/// Analyse de texte locale (NaturalLanguage d'Apple, sans réseau).
struct TextAnalysis {
    struct Sentence {
        var index: Int
        var text: String
        var tokens: [String]
        var folded: String
    }

    let sentences: [Sentence]
    /// Fréquence des mots significatifs (forme pliée).
    let frequencies: [String: Int]
    /// Forme d'affichage la plus fréquente pour chaque mot plié.
    let displayForms: [String: String]

    init(text: String, title: String = "") {
        var sentences: [Sentence] = []
        let tokenizer = NLTokenizer(unit: .sentence)
        tokenizer.string = text
        tokenizer.enumerateTokens(in: text.startIndex..<text.endIndex) { range, _ in
            let raw = text[range].trimmingCharacters(in: .whitespacesAndNewlines)
            if raw.count > 2 {
                let tokens = Self.words(in: raw)
                sentences.append(Sentence(index: sentences.count, text: raw, tokens: tokens, folded: NoteSearch.fold(raw)))
            }
            return true
        }
        self.sentences = sentences

        var frequencies: [String: Int] = [:]
        var forms: [String: [String: Int]] = [:]
        for word in Self.surfaceWords(in: text) {
            let folded = NoteSearch.fold(word)
            guard Self.isSignificant(folded) else { continue }
            frequencies[folded, default: 0] += 1
            forms[folded, default: [:]][word, default: 0] += 1
        }
        for word in Self.surfaceWords(in: title) {
            let folded = NoteSearch.fold(word)
            if Self.isSignificant(folded) { frequencies[folded, default: 0] += 2 }
        }
        self.frequencies = frequencies
        self.displayForms = forms.mapValues { counts in
            counts.max { ($0.value, $1.key) < ($1.value, $0.key) }?.key ?? ""
        }
    }

    // MARK: Mots-clés

    func keywords(limit: Int) -> [String] {
        frequencies
            .filter { $0.value >= 1 }
            .sorted { ($0.value, $1.key) > ($1.value, $0.key) }
            .prefix(limit)
            .map { displayForms[$0.key].map(Self.capitalizedFirst) ?? $0.key }
    }

    // MARK: Phrases

    /// Score d'une phrase : somme des fréquences de ses mots, normalisée par sa longueur,
    /// avec un léger bonus pour les premières phrases (souvent l'objet de la discussion).
    func score(_ sentence: Sentence) -> Double {
        let significant = sentence.tokens.filter(Self.isSignificant)
        guard !significant.isEmpty else { return 0 }
        let total = significant.reduce(0.0) { $0 + Double(frequencies[$1] ?? 0) }
        let positionBonus = sentence.index < 2 ? 1.2 : 1.0
        return total / Double(significant.count).squareRoot() * positionBonus
    }

    func ranked() -> [Sentence] {
        sentences.sorted { (score($0), -$0.index) > (score($1), -$1.index) }
    }

    func sentences(mentioning keyword: String) -> [Sentence] {
        let folded = NoteSearch.fold(keyword)
        return sentences.filter { $0.folded.contains(folded) }
    }

    // MARK: Catégories de phrases

    func isQuestion(_ s: Sentence) -> Bool { s.text.hasSuffix("?") }

    func isDecision(_ s: Sentence) -> Bool {
        Self.decisionCues.contains { Self.containsCue(s.folded, $0) }
    }

    func isAction(_ s: Sentence) -> Bool {
        !isQuestion(s) && Self.actionCues.contains { Self.containsCue(s.folded, $0) }
    }

    /// L'indice doit commencer en début de mot (« faut » ne doit pas trouver « défaut »).
    static func containsCue(_ folded: String, _ cue: String) -> Bool {
        var searchRange = folded.startIndex..<folded.endIndex
        while let range = folded.range(of: cue, range: searchRange) {
            if range.lowerBound == folded.startIndex || !folded[folded.index(before: range.lowerBound)].isLetter {
                return true
            }
            searchRange = range.upperBound..<folded.endIndex
        }
        return false
    }

    /// Personnes et lieux nommés (reconnaissance d'entités hors ligne).
    func namedEntities(in text: String) -> (people: [String], places: [String]) {
        let tagger = NLTagger(tagSchemes: [.nameType])
        tagger.string = text
        var people: [String] = []
        var places: [String] = []
        tagger.enumerateTags(
            in: text.startIndex..<text.endIndex,
            unit: .word,
            scheme: .nameType,
            options: [.omitPunctuation, .omitWhitespace, .joinNames]
        ) { tag, range in
            let name = String(text[range])
            if tag == .personalName, !people.contains(name) { people.append(name) }
            if tag == .placeName, !places.contains(name) { places.append(name) }
            return true
        }
        return (people, places)
    }

    /// Dates mentionnées (détecteur de données de Foundation, hors ligne).
    func dateMentions(in text: String) -> [String] {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.date.rawValue) else { return [] }
        let ns = text as NSString
        return detector.matches(in: text, range: NSRange(location: 0, length: ns.length))
            .map { ns.substring(with: $0.range) }
    }

    // MARK: Outils

    /// Version courte d'une phrase pour un nœud de carte mentale.
    static func condensed(_ sentence: String, maxWords: Int = 8) -> String {
        var words = sentence
            .trimmingCharacters(in: .punctuationCharacters.union(.whitespaces))
            .split(separator: " ")
            .map(String.init)
        while let first = words.first, fillers.contains(NoteSearch.fold(first).trimmingCharacters(in: .punctuationCharacters)) {
            words.removeFirst()
        }
        let short = words.prefix(maxWords).joined(separator: " ")
        let label = words.count > maxWords ? short + "…" : short
        return capitalizedFirst(label.trimmingCharacters(in: CharacterSet(charactersIn: ",;:")))
    }

    static func capitalizedFirst(_ text: String) -> String {
        guard let first = text.first else { return text }
        return first.uppercased() + text.dropFirst()
    }

    static func words(in text: String) -> [String] {
        surfaceWords(in: text).map(NoteSearch.fold)
    }

    static func surfaceWords(in text: String) -> [String] {
        var words: [String] = []
        let tokenizer = NLTokenizer(unit: .word)
        tokenizer.string = text
        tokenizer.enumerateTokens(in: text.startIndex..<text.endIndex) { range, _ in
            words.append(stripElision(String(text[range])))
            return true
        }
        return words
    }

    /// Retire l'élision française : « l'appareil » → « appareil », « d'avancement » → « avancement ».
    static func stripElision(_ word: String) -> String {
        for apostrophe in ["'", "’"] {
            if let range = word.range(of: apostrophe), word.distance(from: word.startIndex, to: range.lowerBound) <= 3 {
                let prefix = NoteSearch.fold(String(word[..<range.lowerBound]))
                if ["l", "d", "j", "m", "n", "s", "t", "c", "qu", "jusqu", "lorsqu", "puisqu"].contains(prefix) {
                    return String(word[range.upperBound...])
                }
            }
        }
        return word
    }

    static func isSignificant(_ folded: String) -> Bool {
        folded.count >= 4 && !stopwords.contains(folded) && !folded.allSatisfy(\.isNumber)
    }

    static let fillers: Set<String> = [
        "alors", "donc", "euh", "bon", "ok", "et", "mais", "oui", "voila", "bref", "so", "well", "and", "but",
    ]

    static let decisionCues = [
        "decid", "c'est valide", "on valide", "on part sur", "on garde", "retenu", "on retient",
        "on choisit", "choisi", "agreed", "decided", "we will go with",
    ]

    static let actionCues = [
        "il faut", "faudra", "faut", "doit", "devons", "devez", "dois", "a faire", "todo", "je vais", "on va",
        "tu peux", "pourrais-tu", "prochaine etape", "avant le", "avant vendredi", "deadline", "preparer",
        "rediger", "need to", "should", "will ",
    ]

    static let stopwords: Set<String> = [
        // Français
        "alors", "aussi", "autre", "avant", "avec", "avoir", "bien", "cela", "celle", "celui", "cette", "ceux",
        "chez", "comme", "comment", "dans", "depuis", "donc", "elle", "elles", "encore", "entre", "etait",
        "etre", "fait", "faire", "faut", "leur", "leurs", "mais", "meme", "mettre", "moins", "nous", "notre",
        "nos", "parce", "pendant", "peut", "peux", "plus", "pour", "pourquoi", "quand", "quel", "quelle",
        "quelles", "quels", "sans", "selon", "sera", "seront", "sont", "sous", "suis", "tout", "toute",
        "toutes", "tous", "tres", "vers", "voila", "vous", "votre", "vos", "c'est", "qu'il", "qu'on",
        "est-ce", "d'accord", "merci", "bonjour", "point", "chose", "choses", "juste", "vraiment", "ensuite",
        "apres", "deja", "ici", "rien", "beaucoup", "peu", "avons", "avez", "ont", "etes", "sommes", "faudra",
        "doit", "devons", "dois", "aller", "allez", "allons", "idealement", "uniquement", "semaine",
        "prochain", "prochaine", "prochaines", "accord", "monde", "objectif", "reunion",
        // Anglais
        "about", "after", "also", "been", "before", "being", "could", "does", "doing", "each", "from",
        "have", "here", "into", "just", "like", "make", "more", "most", "much", "only", "other", "over",
        "should", "some", "such", "than", "that", "their", "them", "then", "there", "these", "they", "this",
        "those", "through", "very", "want", "were", "what", "when", "where", "which", "while", "will",
        "with", "would", "your", "yeah", "okay", "thanks", "hello",
    ]
}
