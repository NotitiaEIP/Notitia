import Foundation

/// Style inline d'un fragment de texte.
public struct TextStyle: Codable, Hashable, Sendable {
    public var bold: Bool
    public var italic: Bool
    public var underline: Bool
    public var strikethrough: Bool
    public var code: Bool
    public var color: NoteColor?
    public var highlight: NoteColor?
    public var link: String?

    public init(
        bold: Bool = false,
        italic: Bool = false,
        underline: Bool = false,
        strikethrough: Bool = false,
        code: Bool = false,
        color: NoteColor? = nil,
        highlight: NoteColor? = nil,
        link: String? = nil
    ) {
        self.bold = bold
        self.italic = italic
        self.underline = underline
        self.strikethrough = strikethrough
        self.code = code
        self.color = color
        self.highlight = highlight
        self.link = link
    }

    public static let plain = TextStyle()

    // Décodage tolérant : les champs absents valent leur valeur par défaut,
    // ce qui garde le JSON compact et compatible avec les versions futures.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        bold = try c.decodeIfPresent(Bool.self, forKey: .bold) ?? false
        italic = try c.decodeIfPresent(Bool.self, forKey: .italic) ?? false
        underline = try c.decodeIfPresent(Bool.self, forKey: .underline) ?? false
        strikethrough = try c.decodeIfPresent(Bool.self, forKey: .strikethrough) ?? false
        code = try c.decodeIfPresent(Bool.self, forKey: .code) ?? false
        color = try c.decodeIfPresent(NoteColor.self, forKey: .color)
        highlight = try c.decodeIfPresent(NoteColor.self, forKey: .highlight)
        link = try c.decodeIfPresent(String.self, forKey: .link)
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        if bold { try c.encode(true, forKey: .bold) }
        if italic { try c.encode(true, forKey: .italic) }
        if underline { try c.encode(true, forKey: .underline) }
        if strikethrough { try c.encode(true, forKey: .strikethrough) }
        if code { try c.encode(true, forKey: .code) }
        try c.encodeIfPresent(color, forKey: .color)
        try c.encodeIfPresent(highlight, forKey: .highlight)
        try c.encodeIfPresent(link, forKey: .link)
    }

    private enum CodingKeys: String, CodingKey {
        case bold, italic, underline, strikethrough, code, color, highlight, link
    }
}

/// Attributs booléens qu'on peut basculer depuis la barre de format.
public enum ToggleableTextAttribute: String, CaseIterable, Sendable {
    case bold, italic, underline, strikethrough, code

    public var keyPath: WritableKeyPath<TextStyle, Bool> {
        switch self {
        case .bold: \.bold
        case .italic: \.italic
        case .underline: \.underline
        case .strikethrough: \.strikethrough
        case .code: \.code
        }
    }
}

/// Un fragment de texte homogène en style.
public struct TextRun: Codable, Hashable, Sendable {
    public var text: String
    public var style: TextStyle

    public init(_ text: String, style: TextStyle = .plain) {
        self.text = text
        self.style = style
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        text = try c.decode(String.self, forKey: .text)
        style = try c.decodeIfPresent(TextStyle.self, forKey: .style) ?? .plain
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(text, forKey: .text)
        if style != .plain { try c.encode(style, forKey: .style) }
    }

    private enum CodingKeys: String, CodingKey { case text, style }
}

/// Texte riche portable : une suite de fragments stylés.
///
/// Tous les décalages sont exprimés en **unités UTF-16**, comme `NSString`,
/// les chaînes Java/Kotlin et C#. Les futures applications Android, Windows et
/// Linux manipulent ainsi exactement les mêmes positions.
public struct RichText: Codable, Hashable, Sendable, ExpressibleByStringLiteral {
    public private(set) var runs: [TextRun]

    public init(runs: [TextRun] = []) {
        self.runs = runs
        normalize()
    }

    public init(_ plain: String, style: TextStyle = .plain) {
        self.init(runs: [TextRun(plain, style: style)])
    }

    public init(stringLiteral value: String) {
        self.init(value)
    }

    public init(from decoder: any Decoder) throws {
        self.init(runs: try decoder.singleValueContainer().decode([TextRun].self))
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(runs)
    }

    // MARK: Lecture

    public var plainText: String { runs.map(\.text).joined() }

    /// Longueur en unités UTF-16.
    public var length: Int { runs.reduce(0) { $0 + $1.text.utf16.count } }

    public var isEmpty: Bool { length == 0 }

    /// Style du caractère qui précède `offset` (celui dont hérite la saisie).
    public func style(at offset: Int) -> TextStyle {
        guard !runs.isEmpty else { return .plain }
        var position = 0
        for run in runs {
            let end = position + run.text.utf16.count
            if offset > position && offset <= end { return run.style }
            position = end
        }
        return offset <= 0 ? runs[0].style : runs[runs.count - 1].style
    }

    /// Vrai si tout le texte de `range` porte l'attribut. Une plage vide n'est jamais « entièrement » stylée.
    public func allHave(_ attribute: ToggleableTextAttribute, in range: Range<Int>) -> Bool {
        let clamped = clamp(range)
        guard !clamped.isEmpty else { return false }
        var position = 0
        for run in runs {
            let runRange = position..<(position + run.text.utf16.count)
            if runRange.overlaps(clamped), !run.style[keyPath: attribute.keyPath] { return false }
            position = runRange.upperBound
        }
        return true
    }

    // MARK: Édition

    /// Coupe le texte en deux à `offset`.
    public func split(at offset: Int) -> (RichText, RichText) {
        let offset = min(max(0, offset), length)
        var head: [TextRun] = []
        var tail: [TextRun] = []
        var position = 0
        for run in runs {
            let count = run.text.utf16.count
            if position + count <= offset {
                head.append(run)
            } else if position >= offset {
                tail.append(run)
            } else {
                let (a, b) = Self.splitString(run.text, at: offset - position)
                head.append(TextRun(a, style: run.style))
                tail.append(TextRun(b, style: run.style))
            }
            position += count
        }
        return (RichText(runs: head), RichText(runs: tail))
    }

    public func appending(_ other: RichText) -> RichText {
        RichText(runs: runs + other.runs)
    }

    public func substring(_ range: Range<Int>) -> RichText {
        let clamped = clamp(range)
        let (_, rest) = split(at: clamped.lowerBound)
        let (middle, _) = rest.split(at: clamped.count)
        return middle
    }

    /// Supprime les `count` premières unités UTF-16 (utilisé par les raccourcis Markdown).
    public func droppingPrefix(_ count: Int) -> RichText {
        split(at: count).1
    }

    /// Applique une transformation de style sur une plage.
    public func applying(in range: Range<Int>, _ transform: (inout TextStyle) -> Void) -> RichText {
        let clamped = clamp(range)
        guard !clamped.isEmpty else { return self }
        let (head, rest) = split(at: clamped.lowerBound)
        let (middle, tail) = rest.split(at: clamped.count)
        let styled = middle.runs.map { run -> TextRun in
            var style = run.style
            transform(&style)
            return TextRun(run.text, style: style)
        }
        return RichText(runs: head.runs + styled + tail.runs)
    }

    /// Bascule un attribut : s'il couvre déjà toute la plage on le retire, sinon on l'applique partout.
    public func toggling(_ attribute: ToggleableTextAttribute, in range: Range<Int>) -> RichText {
        let newValue = !allHave(attribute, in: range)
        return applying(in: range) { $0[keyPath: attribute.keyPath] = newValue }
    }

    // MARK: Interne

    private func clamp(_ range: Range<Int>) -> Range<Int> {
        let lower = min(max(0, range.lowerBound), length)
        let upper = min(max(lower, range.upperBound), length)
        return lower..<upper
    }

    /// Fusionne les fragments adjacents de même style et retire les fragments vides.
    private mutating func normalize() {
        var merged: [TextRun] = []
        for run in runs where !run.text.isEmpty {
            if let last = merged.last, last.style == run.style {
                merged[merged.count - 1].text += run.text
            } else {
                merged.append(run)
            }
        }
        runs = merged
    }

    static func splitString(_ string: String, at utf16Offset: Int) -> (String, String) {
        // On avance scalaire par scalaire : un décalage au milieu d'une paire de substitution
        // est arrondi à la frontière précédente, sans jamais produire de demi-caractère.
        let scalars = string.unicodeScalars
        var index = scalars.startIndex
        var consumed = 0
        while index < scalars.endIndex {
            let width = scalars[index].utf16.count
            if consumed + width > utf16Offset { break }
            consumed += width
            index = scalars.index(after: index)
        }
        return (String(scalars[..<index]), String(scalars[index...]))
    }
}
