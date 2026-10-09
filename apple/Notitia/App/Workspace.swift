import Foundation
import NotitiaKit
import Observation
import SwiftData
import SwiftUI

/// Élément sélectionné dans la barre latérale.
enum SidebarItem: Hashable {
    case allNotes
    case pinned
    case recent
    case recordings
    case folder(UUID)
    case tag(UUID)
    case trash
}

/// État d'une fenêtre : sélection, recherche, note ouverte.
@Observable
final class Workspace {
    var sidebarSelection: SidebarItem? = .allNotes
    var selectedNoteID: UUID? {
        didSet { if oldValue != selectedNoteID { openSelectedNote() } }
    }
    var searchText = ""
    var isInspectorPresented = false
    var isQuickOpenPresented = false
    var isTutorialPresented = false
    var folderEditor: FolderEditorRequest?
    var pendingConsent: PendingRecording?
    var alert: AlertMessage?

    /// Éditeur de la note ouverte.
    private(set) var editor: NoteEditorModel?

    let services: AppServices
    var context: ModelContext { services.container.mainContext }
    var store: NotitiaStore { services.store }

    init(services: AppServices) {
        self.services = services
    }

    // MARK: Requêtes

    func note(_ uid: UUID?) -> Note? {
        guard let uid else { return nil }
        return try? context.fetch(FetchDescriptor<Note>(predicate: #Predicate { $0.uid == uid })).first
    }

    func folder(_ uid: UUID?) -> Folder? {
        guard let uid else { return nil }
        return try? context.fetch(FetchDescriptor<Folder>(predicate: #Predicate { $0.uid == uid })).first
    }

    func tag(_ uid: UUID?) -> NotitiaKit.Tag? {
        guard let uid else { return nil }
        return try? context.fetch(FetchDescriptor<NotitiaKit.Tag>(predicate: #Predicate { $0.uid == uid })).first
    }

    /// Dossier courant, utilisé pour ranger les nouvelles notes.
    var currentFolder: Folder? {
        if case .folder(let uid) = sidebarSelection { return folder(uid) }
        return editor?.note.folder
    }

    // MARK: Notes

    private func openSelectedNote() {
        editor?.flush()
        if let note = note(selectedNoteID), !note.isTrashed {
            editor = NoteEditorModel(note: note, services: services)
        } else if let note = note(selectedNoteID) {
            editor = NoteEditorModel(note: note, services: services, isReadOnly: true)
        } else {
            editor = nil
        }
    }

    @discardableResult
    func createNote(title: String = "", document: NoteDocument = NoteDocument()) -> Note {
        let folder = currentFolder
        let note = store.createNote(in: folder, title: title, document: document)
        if case .tag(let uid) = sidebarSelection, let tag = tag(uid) { note.tags.append(tag) }
        if sidebarSelection == .trash || sidebarSelection == .recordings { sidebarSelection = .allNotes }
        try? context.save()
        searchText = ""
        selectedNoteID = note.uid
        editor?.requestTitleFocus()
        return note
    }

    func trash(_ note: Note) {
        if note.uid == selectedNoteID { selectedNoteID = nil }
        store.moveToTrash(note)
        try? context.save()
    }

    func restore(_ note: Note) {
        store.restore(note)
        try? context.save()
        if note.uid == selectedNoteID { openSelectedNote() }
    }

    func deletePermanently(_ note: Note) {
        if note.uid == selectedNoteID { selectedNoteID = nil }
        store.deletePermanently(note)
        try? context.save()
    }

    func duplicate(_ note: Note) {
        var document = note.document
        document.blocks = document.blocks.filter { $0.kind != .transcript }.map { block in
            var copy = block
            copy.id = UUID()
            return copy
        }
        let copy = store.createNote(in: note.folder, title: note.title + " (copie)", document: document)
        copy.tags = note.tags
        try? context.save()
        selectedNoteID = copy.uid
    }

    func move(_ note: Note, to folder: Folder?) {
        note.folder = folder
        note.updatedAt = .now
        try? context.save()
    }

    /// Ouvre la note d'aide (la recrée si elle a été supprimée, la restaure si elle est à la corbeille).
    func openHelp() {
        let help = HelpContent.install(in: context)
        try? context.save()
        searchText = ""
        sidebarSelection = .allNotes
        selectedNoteID = help.uid
    }

    // MARK: Glisser-déposer

    /// Traite un élément déposé sur un dossier (`nil` = racine). Renvoie `true` si accepté.
    @discardableResult
    func handleDrop(_ payloads: [String], onto target: Folder?) -> Bool {
        var handled = false
        for payload in payloads {
            switch DragPayload(payload) {
            case .note(let uid):
                if let note = note(uid) {
                    if note.isTrashed { store.restore(note) }
                    move(note, to: target)
                    handled = true
                }
            case .folder(let uid):
                if let folder = folder(uid), store.move(folder, into: target) {
                    handled = true
                }
            default:
                break
            }
        }
        try? context.save()
        return handled
    }

    // MARK: Dossiers

    func requestNewFolder(parent: Folder?) {
        folderEditor = FolderEditorRequest(folder: nil, parent: parent)
    }

    func deleteFolder(_ folder: Folder) {
        if case .folder(let uid) = sidebarSelection, folder.subtree.contains(where: { $0.uid == uid }) {
            sidebarSelection = .allNotes
        }
        store.deleteFolder(folder)
        try? context.save()
    }
}

/// Contenu glissé entre les vues (texte préfixé, sans type de fichier à déclarer).
enum DragPayload {
    case note(UUID)
    case folder(UUID)
    case block(UUID)
    case unknown

    init(_ string: String) {
        let parts = string.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2, let uid = UUID(uuidString: parts[1]) else { self = .unknown; return }
        switch parts[0] {
        case "notitia-note": self = .note(uid)
        case "notitia-folder": self = .folder(uid)
        case "notitia-block": self = .block(uid)
        default: self = .unknown
        }
    }

    static func note(_ uid: UUID) -> String { "notitia-note:\(uid.uuidString)" }
    static func folder(_ uid: UUID) -> String { "notitia-folder:\(uid.uuidString)" }
    static func block(_ uid: UUID) -> String { "notitia-block:\(uid.uuidString)" }
}

struct FolderEditorRequest: Identifiable {
    let id = UUID()
    var folder: Folder?
    var parent: Folder?
}

struct PendingRecording: Identifiable {
    let id = UUID()
}

struct AlertMessage: Identifiable {
    let id = UUID()
    var title: String
    var message: String
}

// MARK: - Accès depuis les menus

extension FocusedValues {
    @Entry var workspace: Workspace?
}
