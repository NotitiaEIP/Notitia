import Foundation
import SwiftData

/// Instantané sérialisable de toutes les données (droit d'accès et portabilité, art. 15 et 20 du RGPD).
public struct ExportSnapshot: Codable, Sendable {
    public struct FolderEntry: Codable, Sendable {
        public var id: UUID
        public var name: String
        public var path: String
        public var parentID: UUID?
        public var symbolName: String
        public var color: String
        public var createdAt: Date
    }

    public struct NoteEntry: Codable, Sendable {
        public var id: UUID
        public var title: String
        public var folderID: UUID?
        public var folderPath: String?
        public var tags: [String]
        public var isPinned: Bool
        public var createdAt: Date
        public var updatedAt: Date
        public var trashedAt: Date?
        public var document: NoteDocument
    }

    public struct RecordingEntry: Codable, Sendable {
        public var id: UUID
        public var noteID: UUID?
        public var createdAt: Date
        public var duration: TimeInterval
        public var locale: String
        public var engine: String
        public var consentConfirmedAt: Date
        public var hasAudio: Bool
        public var audioDeletedAt: Date?
        public var segments: [TranscriptSegment]
        /// Annotations (sons, volume, ton), y compris celles masquées par l'utilisateur.
        public var annotations: [TranscriptAnnotation]
        /// Empreinte de la transcription d'origine (voir `TranscriptIntegrity`).
        public var integrityHash: String?
    }

    public var format = "notitia-export"
    public var formatVersion = 1
    public var exportedAt: Date
    public var folders: [FolderEntry]
    public var notes: [NoteEntry]
    public var recordings: [RecordingEntry]

    @MainActor
    public static func make(context: ModelContext, now: Date = .now) throws -> ExportSnapshot {
        let folders = try context.fetch(FetchDescriptor<Folder>())
        let notes = try context.fetch(FetchDescriptor<Note>())
        let recordings = try context.fetch(FetchDescriptor<Recording>())
        return ExportSnapshot(
            exportedAt: now,
            folders: folders.map {
                FolderEntry(id: $0.uid, name: $0.name, path: $0.pathDescription, parentID: $0.parent?.uid,
                            symbolName: $0.symbolName, color: $0.colorName, createdAt: $0.createdAt)
            },
            notes: notes.map {
                NoteEntry(id: $0.uid, title: $0.title, folderID: $0.folder?.uid, folderPath: $0.folder?.pathDescription,
                          tags: $0.tags.map(\.name).sorted(), isPinned: $0.isPinned, createdAt: $0.createdAt,
                          updatedAt: $0.updatedAt, trashedAt: $0.trashedAt, document: $0.document)
            },
            recordings: recordings.map {
                RecordingEntry(id: $0.uid, noteID: $0.note?.uid, createdAt: $0.createdAt, duration: $0.duration,
                               locale: $0.localeIdentifier, engine: $0.engineName,
                               consentConfirmedAt: $0.consentConfirmedAt, hasAudio: $0.hasAudio,
                               audioDeletedAt: $0.audioDeletedAt, segments: $0.segments,
                               annotations: $0.annotations, integrityHash: $0.integrityHash)
            }
        )
    }
}

/// Écrit un export complet et lisible dans un dossier choisi par l'utilisateur :
///
/// ```
/// Notitia-Export-2026-10-09/
/// ├── LISEZMOI.txt
/// ├── notitia-export.json      ← toutes les données, format structuré
/// ├── Notes/<Dossier>/<Titre>.md
/// ├── Audio/<id>.m4a           ← audio déchiffré
/// └── Attachments/…
/// ```
public struct DataExporter: Sendable {
    public let paths: AppPaths
    public let vault: AudioVault

    public init(paths: AppPaths, vault: AudioVault) {
        self.paths = paths
        self.vault = vault
    }

    @discardableResult
    public func export(_ snapshot: ExportSnapshot, into parent: URL) throws -> URL {
        let fm = FileManager.default
        let stamp = snapshot.exportedAt.formatted(.iso8601.year().month().day())
        var root = parent.appending(path: "Notitia-Export-\(stamp)", directoryHint: .isDirectory)
        var suffix = 2
        while fm.fileExists(atPath: root.path) {
            root = parent.appending(path: "Notitia-Export-\(stamp)-\(suffix)", directoryHint: .isDirectory)
            suffix += 1
        }
        try fm.createDirectory(at: root, withIntermediateDirectories: true)

        // 1. JSON structuré
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(snapshot).write(to: root.appending(path: "notitia-export.json"))

        // 2. Markdown, rangé comme dans l'application
        let segments = Dictionary(uniqueKeysWithValues: snapshot.recordings.map { ($0.id, $0.segments) })
        let annotations = Dictionary(uniqueKeysWithValues: snapshot.recordings.map { ($0.id, $0.annotations.filter { !$0.isHidden }) })
        let notesRoot = root.appending(path: "Notes", directoryHint: .isDirectory)
        var usedNames = Set<String>()
        for note in snapshot.notes {
            var directory = notesRoot
            if note.trashedAt != nil {
                directory = directory.appending(path: "Corbeille", directoryHint: .isDirectory)
            } else if let path = note.folderPath {
                for component in path.components(separatedBy: " › ") {
                    directory = directory.appending(path: Self.safeFileName(component), directoryHint: .isDirectory)
                }
            }
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
            let title = note.title.isEmpty ? "Sans titre" : note.title
            var name = Self.safeFileName(title)
            var candidate = directory.appending(path: name + ".md")
            var counter = 2
            while usedNames.contains(candidate.path) {
                name = Self.safeFileName(title) + " (\(counter))"
                candidate = directory.appending(path: name + ".md")
                counter += 1
            }
            usedNames.insert(candidate.path)
            let markdown = MarkdownRenderer.render(title: title, document: note.document, segmentsByRecording: segments, annotationsByRecording: annotations)
            try markdown.write(to: candidate, atomically: true, encoding: .utf8)
        }

        // 3. Audio déchiffré
        let audioDirectory = root.appending(path: "Audio", directoryHint: .isDirectory)
        for recording in snapshot.recordings where recording.hasAudio {
            try fm.createDirectory(at: audioDirectory, withIntermediateDirectories: true)
            if let data = try? vault.open(recording.id) {
                try data.write(to: audioDirectory.appending(path: "\(recording.id.uuidString).m4a"))
            }
        }

        // 4. Pièces jointes
        if let files = try? fm.contentsOfDirectory(at: paths.attachmentsDirectory, includingPropertiesForKeys: nil), !files.isEmpty {
            let target = root.appending(path: "Attachments", directoryHint: .isDirectory)
            try fm.createDirectory(at: target, withIntermediateDirectories: true)
            for file in files {
                try fm.copyItem(at: file, to: target.appending(path: file.lastPathComponent))
            }
        }

        try Self.readme(snapshot).write(to: root.appending(path: "LISEZMOI.txt"), atomically: true, encoding: .utf8)
        return root
    }

    static func readme(_ snapshot: ExportSnapshot) -> String {
        """
        Export Notitia — \(snapshot.exportedAt.formatted(date: .long, time: .shortened))

        Cet export contient l'intégralité des données enregistrées par Notitia sur cet appareil :
        • notitia-export.json : toutes les données dans un format structuré et réutilisable ;
        • Notes/ : chaque note au format Markdown, rangée selon vos dossiers ;
        • Audio/ : les enregistrements, déchiffrés (format AAC .m4a) ;
        • Attachments/ : les images insérées dans les notes.

        Les annotations des transcriptions (rires, applaudissements, volume, silences, ton des passages) sont
        produites automatiquement sur l'appareil : elles sont marquées « automatique » et ne décrivent jamais
        l'état d'une personne. L'empreinte (integrityHash) permet de vérifier que la transcription d'origine
        n'a pas été modifiée dans la base ; ce n'est pas une preuve opposable à un tiers.

        \(snapshot.notes.count) note(s), \(snapshot.folders.count) dossier(s), \(snapshot.recordings.count) enregistrement(s).

        Attention : les fichiers audio de cet export ne sont plus chiffrés. Conservez-les en lieu sûr.
        """
    }

    static func safeFileName(_ name: String) -> String {
        let forbidden = CharacterSet(charactersIn: "/\\:?%*|\"<>").union(.newlines).union(.controlCharacters)
        let cleaned = name.components(separatedBy: forbidden).joined(separator: "-")
            .trimmingCharacters(in: .whitespaces.union(CharacterSet(charactersIn: ".")))
        return cleaned.isEmpty ? "Sans titre" : String(cleaned.prefix(120))
    }
}
