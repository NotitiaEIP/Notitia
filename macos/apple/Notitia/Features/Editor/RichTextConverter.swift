import AppKit
import NotitiaKit

/// Boîte objet pour stocker un `TextStyle` dans un `NSAttributedString`.
nonisolated final class TextStyleBox: NSObject {
    let style: TextStyle
    init(_ style: TextStyle) { self.style = style }

    override func isEqual(_ object: Any?) -> Bool {
        (object as? TextStyleBox)?.style == style
    }

    override var hash: Int { style.hashValue }
}

extension NSAttributedString.Key {
    /// Style Notitia d'origine : c'est lui qu'on relit, les attributs visuels n'en sont que le rendu.
    static let notitiaStyle = NSAttributedString.Key("com.notitia.style")
}

/// Conversion entre le modèle portable (`RichText`) et l'affichage AppKit.
///
/// Le rendu (police, couleur…) dépend du type de bloc ; la relecture ne s'appuie que sur
/// l'attribut `.notitiaStyle`. On évite ainsi toute ambiguïté (un titre est déjà en gras
/// visuellement sans que son texte soit « gras »).
enum RichTextConverter {
    struct BlockAppearance: Equatable {
        var kind: BlockKind
        var isChecked = false
    }

    // MARK: Polices

    static func baseFont(for kind: BlockKind) -> NSFont {
        switch kind {
        case .heading1: .systemFont(ofSize: 28, weight: .bold)
        case .heading2: .systemFont(ofSize: 22, weight: .semibold)
        case .heading3: .systemFont(ofSize: 18, weight: .semibold)
        case .code: .monospacedSystemFont(ofSize: 13, weight: .regular)
        default: .systemFont(ofSize: 15)
        }
    }

    /// Hauteur d'une ligne de texte (sans l'interligne), pour aligner puces, numéros et cases.
    /// L'interligne est ajouté sous chaque ligne : la première ligne commence en haut du bloc.
    static func lineHeight(for kind: BlockKind) -> CGFloat {
        ceil(NSLayoutManager().defaultLineHeight(for: baseFont(for: kind)))
    }

    static func lineSpacing(for kind: BlockKind) -> CGFloat {
        kind.isHeading ? 2 : 5
    }

    static func paragraphStyle(for kind: BlockKind) -> NSParagraphStyle {
        let style = NSMutableParagraphStyle()
        style.lineSpacing = lineSpacing(for: kind)
        return style
    }

    // MARK: Rendu

    static func attributes(for style: TextStyle, appearance: BlockAppearance) -> [NSAttributedString.Key: Any] {
        let base = baseFont(for: appearance.kind)
        var font = style.code && appearance.kind != .code
            ? NSFont.monospacedSystemFont(ofSize: base.pointSize * 0.9, weight: .regular)
            : base
        var traits: NSFontDescriptor.SymbolicTraits = font.fontDescriptor.symbolicTraits
        if style.bold { traits.insert(.bold) }
        if style.italic { traits.insert(.italic) }
        if traits != font.fontDescriptor.symbolicTraits {
            font = NSFont(descriptor: font.fontDescriptor.withSymbolicTraits(traits), size: font.pointSize) ?? font
        }

        var color: NSColor = style.color?.nsColor ?? .labelColor
        if appearance.kind == .todo, appearance.isChecked { color = .secondaryLabelColor }
        if appearance.kind == .quote, style.color == nil { color = .secondaryLabelColor }

        var attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: color,
            .paragraphStyle: paragraphStyle(for: appearance.kind),
            .notitiaStyle: TextStyleBox(style),
        ]
        if style.underline { attributes[.underlineStyle] = NSUnderlineStyle.single.rawValue }
        if style.strikethrough || (appearance.kind == .todo && appearance.isChecked) {
            attributes[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
        }
        if let highlight = style.highlight {
            attributes[.backgroundColor] = highlight.nsColor.withAlphaComponent(0.28)
        } else if style.code, appearance.kind != .code {
            attributes[.backgroundColor] = NSColor.quaternaryLabelColor.withAlphaComponent(0.55)
        }
        if let link = style.link, let url = URL(string: link) {
            attributes[.link] = url
        }
        return attributes
    }

    static func render(_ text: RichText, appearance: BlockAppearance) -> NSAttributedString {
        let result = NSMutableAttributedString()
        for run in text.runs {
            result.append(NSAttributedString(string: run.text, attributes: attributes(for: run.style, appearance: appearance)))
        }
        return result
    }

    // MARK: Relecture

    static func parse(_ string: NSAttributedString) -> RichText {
        var runs: [TextRun] = []
        let ns = string.string as NSString
        string.enumerateAttribute(.notitiaStyle, in: NSRange(location: 0, length: string.length)) { value, range, _ in
            let style = (value as? TextStyleBox)?.style ?? .plain
            runs.append(TextRun(ns.substring(with: range), style: style))
        }
        return RichText(runs: runs)
    }

    static func style(in attributes: [NSAttributedString.Key: Any]) -> TextStyle {
        (attributes[.notitiaStyle] as? TextStyleBox)?.style ?? .plain
    }
}
