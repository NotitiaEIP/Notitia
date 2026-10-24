import Foundation
import SwiftData

/// Opérations de données qui touchent à la fois la base et les fichiers
/// (audio chiffré, pièces jointes). Centralisées ici pour que la suppression
/// soit toujours **réelle** : jamais de fichier orphelin après effacement.
@MainActor
public struct NotitiaStore {
    public let context: ModelContext
    public let paths: AppPaths
    public let vault: AudioVault

    public init(context: ModelContext, paths: AppPaths, vault: AudioVault) {
        self.context = context
        self.paths = paths
        self.vault = vault
    }

    // MARK: Notes

    @discardableResult
    public func createNote(in folder: Folder?, title: String = "", document: NoteDocument = NoteDocument()) -> Note {
        let note = Note(title: title, document: document, folder: folder)
        context.insert(note)
        return note
    }

    public func moveToTrash(_ note: Note) {
        note.trashedAt = .now
        note.isPinned = false
    }

    public func restore(_ note: Note) {
        note.trashedAt = nil
        // Si son dossier a été supprimé entre-temps, la note revient hors dossier.
        if let folder = note.folder, folder.modelContext == nil { note.folder = nil }
    }

    /// Suppression définitive : la note, ses enregistrements, leur audio et ses pièces jointes.
    public func deletePermanently(_ note: Note) {
        for recording in note.recordings {
            try? vault.delete(recording.uid)
        }
        for block in note.document.blocks {
            if let name = block.attachment {
                try? FileManager.default.removeItem(at: paths.attachmentURL(name))
            }
        }
        context.delete(note)
    }

    public func emptyTrash() throws {
        let trashed = try context.fetch(FetchDescriptor<Note>(predicate: #Predicate { $0.trashedAt != nil }))
        trashed.forEach(deletePermanently)
    }

    // MARK: Dossiers

    /// Supprime un dossier et ses sous-dossiers ; leurs notes vont à la corbeille.
    public func deleteFolder(_ folder: Folder) {
        for sub in folder.subtree {
            for note in sub.notes {
                note.folder = nil
                moveToTrash(note)
            }
        }
        context.delete(folder)
    }

    public func move(_ folder: Folder, into target: Folder?) -> Bool {
        guard folder.canMove(into: target), folder.parent !== target else { return false }
        folder.parent = target
        folder.sortIndex = (target?.children.map(\.sortIndex).max() ?? 0) + 1
        return true
    }

    // MARK: Enregistrements

    /// Supprime l'audio en gardant la transcription.
    public func deleteAudio(of recording: Recording) {
        try? vault.delete(recording.uid)
        recording.hasAudio = false
        recording.audioDeletedAt = .now
    }

    // MARK: Maintenance RGPD

    public struct MaintenanceReport: Equatable, Sendable {
        public var expiredAudio = 0
        public var purgedNotes = 0

        public init(expiredAudio: Int = 0, purgedNotes: Int = 0) {
            self.expiredAudio = expiredAudio
            self.purgedNotes = purgedNotes
        }
    }

    /// Applique la politique de conservation : purge de la corbeille et de l'audio expiré.
    /// Exécutée au lancement puis régulièrement.
    @discardableResult
    public func runMaintenance(policy: RetentionPolicy, now: Date = .now) throws -> MaintenanceReport {
        var report = MaintenanceReport()
        let trashed = try context.fetch(FetchDescriptor<Note>(predicate: #Predicate { $0.trashedAt != nil }))
        for note in trashed {
            if let date = note.trashedAt, policy.isTrashExpired(trashedAt: date, now: now) {
                deletePermanently(note)
                report.purgedNotes += 1
            }
        }
        if policy.audioRetentionDays != nil {
            let recordings = try context.fetch(FetchDescriptor<Recording>(predicate: #Predicate { $0.hasAudio }))
            for recording in recordings where policy.isAudioExpired(recordedAt: recording.createdAt, now: now) {
                deleteAudio(of: recording)
                report.expiredAudio += 1
            }
        }
        try context.save()
        return report
    }

    // MARK: Droit à l'effacement

    /// Efface **toutes** les données : base, audio, clé de chiffrement, pièces jointes.
    public func eraseEverything() throws {
        // Suppression objet par objet : la suppression par lot de SwiftData
        // échoue sur les relations inverses (Recording.note).
        for note in try context.fetch(FetchDescriptor<Note>()) { context.delete(note) } // cascade : enregistrements
        for recording in try context.fetch(FetchDescriptor<Recording>()) { context.delete(recording) }
        for tag in try context.fetch(FetchDescriptor<Tag>()) { context.delete(tag) }
        for folder in try context.fetch(FetchDescriptor<Folder>(predicate: #Predicate { $0.parent == nil })) {
            context.delete(folder) // cascade : sous-dossiers
        }
        try context.save()
        try vault.destroyEverything()
        let fm = FileManager.default
        if fm.fileExists(atPath: paths.attachmentsDirectory.path) {
            try fm.removeItem(at: paths.attachmentsDirectory)
        }
        try paths.prepare()
    }

    // MARK: Statistiques (écran Confidentialité)

    public struct Statistics: Equatable, Sendable {
        public var notes = 0
        public var folders = 0
        public var recordings = 0
        public var recordingsWithAudio = 0
        public var trashed = 0
        public var diskUsage: Int64 = 0

        public init() {}
    }

    public func statistics() -> Statistics {
        var stats = Statistics()
        stats.notes = (try? context.fetchCount(FetchDescriptor<Note>())) ?? 0
        stats.folders = (try? context.fetchCount(FetchDescriptor<Folder>())) ?? 0
        stats.recordings = (try? context.fetchCount(FetchDescriptor<Recording>())) ?? 0
        stats.recordingsWithAudio = (try? context.fetchCount(FetchDescriptor<Recording>(predicate: #Predicate { $0.hasAudio }))) ?? 0
        stats.trashed = (try? context.fetchCount(FetchDescriptor<Note>(predicate: #Predicate { $0.trashedAt != nil }))) ?? 0
        stats.diskUsage = paths.diskUsage()
        return stats
    }
}
