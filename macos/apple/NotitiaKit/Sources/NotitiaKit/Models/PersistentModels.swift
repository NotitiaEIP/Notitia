import Foundation
import SwiftData

// MARK: - Folder

/// Dossier (ou catégorie) : « Cours », « Chinois », « Réunions »…
/// Les dossiers s'imbriquent sans limite de profondeur.
@Model
public final class Folder {
    @Attribute(.unique) public var uid: UUID
    public var name: String
    public var symbolName: String
    public var colorName: String
    public var sortIndex: Int
    public var createdAt: Date

    @Relationship(deleteRule: .nullify, inverse: \Folder.children)
    public var parent: Folder?

    @Relationship(deleteRule: .cascade)
    public var children: [Folder] = []

    @Relationship(deleteRule: .nullify, inverse: \Note.folder)
    public var notes: [Note] = []

    public init(
        name: String,
        symbolName: String = "folder",
        color: NoteColor = .blue,
        parent: Folder? = nil,
        sortIndex: Int = 0
    ) {
        self.uid = UUID()
        self.name = name
        self.symbolName = symbolName
        self.colorName = color.rawValue
        self.parent = parent
        self.sortIndex = sortIndex
        self.createdAt = .now
    }

    public var color: NoteColor {
        get { NoteColor(rawValue: colorName) ?? .blue }
        set { colorName = newValue.rawValue }
    }

    public var sortedChildren: [Folder] {
        children.sorted { ($0.sortIndex, $0.name) < ($1.sortIndex, $1.name) }
    }

    /// Enfants ou `nil` pour une feuille (format attendu par `OutlineGroup`).
    public var sortedChildrenOrNil: [Folder]? {
        children.isEmpty ? nil : sortedChildren
    }

    /// Chemin depuis la racine, par exemple « Cours › Chinois ».
    public var path: [Folder] {
        var result: [Folder] = [self]
        var current = parent
        while let folder = current, !result.contains(where: { $0 === folder }) {
            result.insert(folder, at: 0)
            current = folder.parent
        }
        return result
    }

    public var pathDescription: String { path.map(\.name).joined(separator: " › ") }

    /// Ce dossier et tous ses descendants.
    public var subtree: [Folder] {
        var result: [Folder] = [self]
        var index = 0
        while index < result.count {
            for child in result[index].children where !result.contains(where: { $0 === child }) {
                result.append(child)
            }
            index += 1
        }
        return result
    }

    /// Un dossier ne peut pas être déplacé dans lui-même ni dans un de ses descendants.
    public func canMove(into target: Folder?) -> Bool {
        guard let target else { return true }
        return !subtree.contains { $0 === target }
    }
}

// MARK: - Note

@Model
public final class Note {
    @Attribute(.unique) public var uid: UUID
    public var title: String
    /// `NoteDocument` encodé en JSON (format portable, voir docs/ARCHITECTURE.md).
    @Attribute(.externalStorage) public var contentData: Data
    /// Cache de texte brut pour la recherche et les aperçus.
    public var plainText: String
    public var isPinned: Bool
    public var createdAt: Date
    public var updatedAt: Date
    /// Date de mise à la corbeille. `nil` = note active.
    public var trashedAt: Date?

    public var folder: Folder?

    @Relationship(deleteRule: .nullify, inverse: \Tag.notes)
    public var tags: [Tag] = []

    @Relationship(deleteRule: .cascade, inverse: \Recording.note)
    public var recordings: [Recording] = []

    public init(title: String = "", document: NoteDocument = NoteDocument(), folder: Folder? = nil) {
        self.uid = UUID()
        self.title = title
        self.contentData = document.encoded()
        self.plainText = document.plainText
        self.isPinned = false
        self.createdAt = .now
        self.updatedAt = .now
        self.folder = folder
    }

    public var document: NoteDocument {
        get { NoteDocument.decode(contentData) }
        set {
            contentData = newValue.encoded()
            plainText = newValue.plainText
        }
    }

    public var displayTitle: String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Sans titre" : trimmed
    }

    public var preview: String {
        let flattened = plainText
            .split(whereSeparator: \.isNewline)
            .joined(separator: " ")
        return String(flattened.prefix(180))
    }

    public var isTrashed: Bool { trashedAt != nil }

    public var hasRecording: Bool { !recordings.isEmpty }
}

// MARK: - Tag

@Model
public final class Tag {
    @Attribute(.unique) public var uid: UUID
    public var name: String
    public var colorName: String
    public var notes: [Note] = []

    public init(name: String, color: NoteColor = .gray) {
        self.uid = UUID()
        self.name = name
        self.colorName = color.rawValue
    }

    public var color: NoteColor {
        get { NoteColor(rawValue: colorName) ?? .gray }
        set { colorName = newValue.rawValue }
    }
}

// MARK: - Recording

/// Un enregistrement audio et sa transcription.
///
/// L'audio est stocké **chiffré** hors de la base (voir `AudioVault`) ;
/// la base ne contient que les métadonnées et les segments de texte.
@Model
public final class Recording {
    @Attribute(.unique) public var uid: UUID
    public var createdAt: Date
    public var duration: TimeInterval
    public var localeIdentifier: String
    /// Nom du moteur de transcription utilisé (traçabilité).
    public var engineName: String
    /// Date à laquelle l'utilisateur a confirmé avoir informé les participants (RGPD).
    public var consentConfirmedAt: Date
    /// Vrai si l'audio chiffré existe sur disque.
    public var hasAudio: Bool
    /// Date de suppression de l'audio (manuelle ou par la politique de conservation).
    public var audioDeletedAt: Date?
    @Attribute(.externalStorage) public var segmentsData: Data
    /// Annotations (sons, volume, ton). Optionnel : les enregistrements antérieurs n'en ont pas.
    @Attribute(.externalStorage) public var annotationsData: Data?
    /// Empreinte SHA-256 de la transcription d'origine, calculée à l'arrêt (voir `TranscriptIntegrity`).
    public var integrityHash: String?
    /// Noms donnés par l'utilisateur aux locuteurs (« Locuteur 1 » → « Lucas »). Hors de l'empreinte : la
    /// transcription garde le libellé neutre, le nom est une interprétation de l'utilisateur.
    public var speakerNamesData: Data?

    public var note: Note?

    public init(
        uid: UUID = UUID(),
        localeIdentifier: String,
        engineName: String,
        consentConfirmedAt: Date,
        note: Note? = nil
    ) {
        self.uid = uid
        self.createdAt = .now
        self.duration = 0
        self.localeIdentifier = localeIdentifier
        self.engineName = engineName
        self.consentConfirmedAt = consentConfirmedAt
        self.hasAudio = false
        self.segmentsData = Data()
        self.note = note
    }

    public var segments: [TranscriptSegment] {
        get { [TranscriptSegment].decode(segmentsData) }
        set { segmentsData = newValue.encoded() }
    }

    public var annotations: [TranscriptAnnotation] {
        get { [TranscriptAnnotation].decode(annotationsData) }
        set { annotationsData = newValue.isEmpty ? nil : newValue.encoded() }
    }

    public var speakerNames: [String: String] {
        get { speakerNamesData.flatMap { try? JSONDecoder().decode([String: String].self, from: $0) } ?? [:] }
        set { speakerNamesData = newValue.isEmpty ? nil : try? JSONEncoder().encode(newValue) }
    }

    /// Segments avec le nom choisi par l'utilisateur à la place du libellé neutre, pour l'affichage et l'export.
    public var namedSegments: [TranscriptSegment] {
        segments.renamingSpeakers(speakerNames)
    }

    /// Renomme un locuteur dans tout l'enregistrement. Un nom vide rend le libellé d'origine.
    public func rename(speaker: String, to name: String) {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        var names = speakerNames
        names[speaker] = name.isEmpty || name == speaker ? nil : name
        speakerNames = names
    }

    /// Fige la transcription d'origine : à appeler une seule fois, à la fin de l'enregistrement.
    public func seal() {
        integrityHash = TranscriptIntegrity.fingerprint(segments: segments, annotations: annotations)
    }

    public var integrityStatus: TranscriptIntegrity.Status {
        TranscriptIntegrity.status(expected: integrityHash, segments: segments, annotations: annotations)
    }
}

// MARK: - Schéma

public enum NotitiaSchema {
    public static let models: [any PersistentModel.Type] = [
        Folder.self, Note.self, Tag.self, Recording.self,
    ]

    public static var schema: Schema { Schema(models) }

    /// Conteneur persistant dans le dossier de l'application.
    @MainActor
    public static func makeContainer(paths: AppPaths = .default, inMemory: Bool = false) throws -> ModelContainer {
        let configuration: ModelConfiguration
        if inMemory {
            configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        } else {
            try paths.prepare()
            configuration = ModelConfiguration(schema: schema, url: paths.storeURL)
        }
        return try ModelContainer(for: schema, configurations: [configuration])
    }
}
