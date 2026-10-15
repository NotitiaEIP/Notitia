import Foundation

/// Type d'un bloc de note.
public enum BlockKind: String, Codable, CaseIterable, Sendable, Identifiable {
    case paragraph
    case heading1
    case heading2
    case heading3
    case bulleted
    case numbered
    case todo
    case quote
    case callout
    case code
    case divider
    case image
    case transcript
    case mindMap

    public var id: String { rawValue }

    /// Le bloc contient du texte éditable.
    public var hasText: Bool {
        switch self {
        case .divider, .image, .transcript, .mindMap: false
        default: true
        }
    }

    /// Le bloc ne disparaît jamais au clavier ni par conversion : le supprimer efface un enregistrement
    /// (audio chiffré et transcription), ce qui demande une confirmation.
    public var requiresConfirmedDeletion: Bool { self == .transcript }

    public var isList: Bool { self == .bulleted || self == .numbered || self == .todo }

    public var isHeading: Bool { self == .heading1 || self == .heading2 || self == .heading3 }

    /// Type du bloc créé quand on appuie sur Entrée à la fin de celui-ci.
    public var continuation: BlockKind {
        isList ? self : .paragraph
    }

    /// Libellé du menu « / ».
    public var displayName: String {
        switch self {
        case .paragraph: "Texte"
        case .heading1: "Titre 1"
        case .heading2: "Titre 2"
        case .heading3: "Titre 3"
        case .bulleted: "Liste à puces"
        case .numbered: "Liste numérotée"
        case .todo: "Liste de tâches"
        case .quote: "Citation"
        case .callout: "Encadré"
        case .code: "Code"
        case .divider: "Séparateur"
        case .image: "Image"
        case .transcript: "Transcription"
        case .mindMap: "Carte mentale"
        }
    }

    /// SF Symbol associé.
    public var symbolName: String {
        switch self {
        case .paragraph: "text.alignleft"
        case .heading1: "textformat.size.larger"
        case .heading2: "textformat.size"
        case .heading3: "textformat.size.smaller"
        case .bulleted: "list.bullet"
        case .numbered: "list.number"
        case .todo: "checklist"
        case .quote: "text.quote"
        case .callout: "exclamationmark.bubble"
        case .code: "chevron.left.forwardslash.chevron.right"
        case .divider: "minus"
        case .image: "photo"
        case .transcript: "waveform"
        case .mindMap: "point.3.connected.trianglepath.dotted"
        }
    }

    /// Mots-clés reconnus par le filtre du menu « / ».
    public var searchTerms: [String] {
        switch self {
        case .paragraph: ["texte", "paragraphe", "text"]
        case .heading1: ["titre", "h1", "heading"]
        case .heading2: ["titre", "h2", "heading"]
        case .heading3: ["titre", "h3", "heading"]
        case .bulleted: ["liste", "puces", "bullet"]
        case .numbered: ["liste", "numéro", "numbered"]
        case .todo: ["tâche", "todo", "checklist", "case"]
        case .quote: ["citation", "quote"]
        case .callout: ["encadré", "callout", "note"]
        case .code: ["code"]
        case .divider: ["séparateur", "ligne", "divider"]
        case .image: ["image", "photo"]
        case .transcript: ["transcription"]
        case .mindMap: ["carte", "mentale", "mind"]
        }
    }

    /// Types proposés dans le menu « / » (les blocs générés n'y figurent pas).
    public static let insertable: [BlockKind] = [
        .paragraph, .heading1, .heading2, .heading3,
        .bulleted, .numbered, .todo,
        .quote, .callout, .code, .divider, .image,
    ]
}

/// Un bloc de note. Type valeur : l'éditeur manipule un tableau de blocs
/// avec des fonctions pures (voir `BlockOperations`).
public struct Block: Identifiable, Codable, Hashable, Sendable {
    public var id: UUID
    public var kind: BlockKind
    public var text: RichText
    public var indent: Int
    public var isChecked: Bool
    /// Nom du fichier joint (bloc image), relatif au dossier des pièces jointes.
    public var attachment: String?
    /// Enregistrement lié (bloc transcription).
    public var recordingID: UUID?
    /// Carte mentale (bloc carte mentale).
    public var mindMap: MindMapDocument?

    public init(
        id: UUID = UUID(),
        kind: BlockKind = .paragraph,
        text: RichText = RichText(),
        indent: Int = 0,
        isChecked: Bool = false,
        attachment: String? = nil,
        recordingID: UUID? = nil,
        mindMap: MindMapDocument? = nil
    ) {
        self.id = id
        self.kind = kind
        self.text = text
        self.indent = indent
        self.isChecked = isChecked
        self.attachment = attachment
        self.recordingID = recordingID
        self.mindMap = mindMap
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        // Un type inconnu (écrit par une version plus récente) est lu comme du texte.
        kind = (try? c.decode(BlockKind.self, forKey: .kind)) ?? .paragraph
        text = try c.decodeIfPresent(RichText.self, forKey: .text) ?? RichText()
        indent = try c.decodeIfPresent(Int.self, forKey: .indent) ?? 0
        isChecked = try c.decodeIfPresent(Bool.self, forKey: .isChecked) ?? false
        attachment = try c.decodeIfPresent(String.self, forKey: .attachment)
        recordingID = try c.decodeIfPresent(UUID.self, forKey: .recordingID)
        mindMap = try c.decodeIfPresent(MindMapDocument.self, forKey: .mindMap)
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(kind, forKey: .kind)
        if !text.isEmpty { try c.encode(text, forKey: .text) }
        if indent != 0 { try c.encode(indent, forKey: .indent) }
        if isChecked { try c.encode(true, forKey: .isChecked) }
        try c.encodeIfPresent(attachment, forKey: .attachment)
        try c.encodeIfPresent(recordingID, forKey: .recordingID)
        try c.encodeIfPresent(mindMap, forKey: .mindMap)
    }

    private enum CodingKeys: String, CodingKey {
        case id, kind, text, indent, isChecked, attachment, recordingID, mindMap
    }
}

/// Contenu sérialisé d'une note. Le format est versionné pour pouvoir évoluer
/// sans casser les notes existantes, y compris sur les autres plateformes.
public struct NoteDocument: Codable, Hashable, Sendable {
    public static let currentVersion = 1

    public var version: Int
    public var blocks: [Block]

    public init(blocks: [Block] = [Block()]) {
        self.version = Self.currentVersion
        self.blocks = blocks.isEmpty ? [Block()] : blocks
    }

    public static func decode(_ data: Data?) -> NoteDocument {
        guard let data, !data.isEmpty,
              let document = try? JSONDecoder().decode(NoteDocument.self, from: data)
        else { return NoteDocument() }
        return document.blocks.isEmpty ? NoteDocument() : document
    }

    public func encoded() -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return (try? encoder.encode(self)) ?? Data()
    }

    /// Texte brut indexé pour la recherche.
    public var plainText: String {
        blocks.compactMap { block -> String? in
            if block.kind.hasText { return block.text.plainText }
            if let map = block.mindMap { return map.allLabels.joined(separator: " ") }
            return nil
        }
        .filter { !$0.isEmpty }
        .joined(separator: "\n")
    }
}
