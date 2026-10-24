import Foundation

/// Raccourcis Markdown tapés en début de paragraphe (« # », « - », « [] », « > »…).
public enum MarkdownShortcut {
    public struct Match: Equatable, Sendable {
        public var kind: BlockKind
        /// Nombre d'unités UTF-16 à retirer en tête du texte.
        public var prefixLength: Int
        public var isChecked: Bool
    }

    private static let rules: [(prefix: String, kind: BlockKind, checked: Bool)] = [
        ("### ", .heading3, false),
        ("## ", .heading2, false),
        ("# ", .heading1, false),
        ("- [x] ", .todo, true),
        ("- [ ] ", .todo, false),
        ("[x] ", .todo, true),
        ("[] ", .todo, false),
        ("[ ] ", .todo, false),
        ("- ", .bulleted, false),
        ("* ", .bulleted, false),
        ("• ", .bulleted, false),
        ("1. ", .numbered, false),
        ("1) ", .numbered, false),
        ("> ", .quote, false),
        ("! ", .callout, false),
        ("```", .code, false),
        ("---", .divider, false),
    ]

    /// Détecte un raccourci au début du texte d'un paragraphe.
    /// Ne s'applique qu'aux blocs de type texte, pour ne pas surprendre dans une liste ou du code.
    public static func detect(in text: String, currentKind: BlockKind) -> Match? {
        guard currentKind == .paragraph else { return nil }
        for rule in rules where text.hasPrefix(rule.prefix) {
            // « --- » et « ``` » ne se déclenchent que s'ils sont seuls dans le bloc.
            if rule.kind == .divider || rule.kind == .code, text != rule.prefix { continue }
            return Match(kind: rule.kind, prefixLength: rule.prefix.utf16.count, isChecked: rule.checked)
        }
        return nil
    }

    /// Applique le raccourci éventuel au bloc. Renvoie `nil` si rien ne change.
    public static func apply(to block: Block) -> Block? {
        guard let match = detect(in: block.text.plainText, currentKind: block.kind) else { return nil }
        var block = block
        block.kind = match.kind
        block.isChecked = match.isChecked
        block.text = match.kind.hasText ? block.text.droppingPrefix(match.prefixLength) : RichText()
        return block
    }
}
