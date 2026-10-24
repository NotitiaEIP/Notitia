import NotitiaKit
import SwiftData
import SwiftUI

struct NoteListView: View {
    @Environment(Workspace.self) private var workspace
    @Query private var allNotes: [Note]
    @Query(sort: [SortDescriptor(\Folder.sortIndex), SortDescriptor(\Folder.name)]) private var folders: [Folder]
    @AppStorage(Preferences.Keys.noteSortOrder) private var sortOrder = NoteSortOrder.modified
    @State private var confirmEmptyTrash = false

    var body: some View {
        @Bindable var workspace = workspace
        let notes = filteredNotes
        let pinned = showsPinnedSection ? notes.filter(\.isPinned) : []
        let others = showsPinnedSection ? notes.filter { !$0.isPinned } : notes

        List(selection: $workspace.selectedNoteID) {
            if !pinned.isEmpty {
                Section("Épinglées") {
                    ForEach(pinned) { row(for: $0) }
                }
            }
            Section {
                ForEach(others) { row(for: $0) }
            } header: {
                if !pinned.isEmpty { Text("Notes") }
            }
        }
        .listStyle(.inset)
        .overlay {
            if notes.isEmpty { emptyState }
        }
        .navigationTitle(title)
        .navigationSubtitle(notes.count == 1 ? "1 note" : "\(notes.count) notes")
        .toolbar {
            ToolbarItemGroup {
                Menu {
                    Picker("Trier par", selection: $sortOrder) {
                        ForEach(NoteSortOrder.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.inline)
                } label: {
                    Label("Trier", systemImage: "arrow.up.arrow.down")
                }
                .help("Trier les notes")

                if workspace.sidebarSelection == .trash {
                    Button {
                        confirmEmptyTrash = true
                    } label: {
                        Label("Vider la corbeille", systemImage: "trash.slash")
                    }
                    .disabled(notes.isEmpty)
                    .help("Vider la corbeille")
                } else {
                    Button {
                        workspace.createNote()
                    } label: {
                        Label("Nouvelle note", systemImage: "square.and.pencil")
                    }
                    .help("Nouvelle note (⌘N)")
                }
            }
        }
        .onDeleteCommand {
            guard let note = workspace.note(workspace.selectedNoteID) else { return }
            if note.isTrashed { workspace.deletePermanently(note) } else { workspace.trash(note) }
        }
        .confirmationDialog("Vider la corbeille ?", isPresented: $confirmEmptyTrash) {
            Button("Supprimer définitivement", role: .destructive) {
                workspace.selectedNoteID = nil
                try? workspace.store.emptyTrash()
                try? workspace.context.save()
            }
        } message: {
            Text("Les notes, leurs enregistrements et leur audio seront supprimés définitivement.")
        }
    }

    // MARK: Filtrage

    private var showsPinnedSection: Bool {
        workspace.searchText.isEmpty && workspace.sidebarSelection != .trash && workspace.sidebarSelection != .pinned
    }

    private var filteredNotes: [Note] {
        let query = workspace.searchText.trimmingCharacters(in: .whitespaces)
        if !query.isEmpty {
            // La recherche porte sur toutes les notes actives, classées par pertinence.
            return allNotes
                .filter { !$0.isTrashed }
                .compactMap { note in NoteSearch.score(title: note.title, body: note.plainText, query: query).map { (note, $0) } }
                .sorted { $0.1 > $1.1 }
                .map(\.0)
        }

        let scoped: [Note]
        switch workspace.sidebarSelection ?? .allNotes {
        case .allNotes: scoped = allNotes.filter { !$0.isTrashed }
        case .pinned: scoped = allNotes.filter { !$0.isTrashed && $0.isPinned }
        case .recent:
            let limit = Calendar.current.date(byAdding: .day, value: -7, to: .now) ?? .now
            scoped = allNotes.filter { !$0.isTrashed && $0.updatedAt >= limit }
        case .recordings: scoped = allNotes.filter { !$0.isTrashed && $0.hasRecording }
        case .folder(let uid):
            // Un dossier affiche aussi les notes de ses sous-dossiers.
            let subtree = Set(workspace.folder(uid)?.subtree.map(\.uid) ?? [])
            scoped = allNotes.filter { !$0.isTrashed && $0.folder.map { subtree.contains($0.uid) } == true }
        case .tag(let uid): scoped = allNotes.filter { !$0.isTrashed && $0.tags.contains { $0.uid == uid } }
        case .trash: scoped = allNotes.filter(\.isTrashed)
        }
        return sorted(scoped)
    }

    private func sorted(_ notes: [Note]) -> [Note] {
        switch sortOrder {
        case .modified: notes.sorted { $0.updatedAt > $1.updatedAt }
        case .created: notes.sorted { $0.createdAt > $1.createdAt }
        case .title: notes.sorted { $0.displayTitle.localizedStandardCompare($1.displayTitle) == .orderedAscending }
        }
    }

    private var title: String {
        if !workspace.searchText.isEmpty { return String(localized: "Recherche") }
        switch workspace.sidebarSelection ?? .allNotes {
        case .allNotes: return String(localized: "Toutes les notes")
        case .pinned: return String(localized: "Épinglées")
        case .recent: return String(localized: "Récentes")
        case .recordings: return String(localized: "Enregistrements")
        case .folder(let uid): return workspace.folder(uid)?.name ?? ""
        case .tag(let uid): return "#" + (workspace.tag(uid)?.name ?? "")
        case .trash: return String(localized: "Corbeille")
        }
    }

    @ViewBuilder
    private var emptyState: some View {
        if !workspace.searchText.isEmpty {
            ContentUnavailableView.search(text: workspace.searchText)
        } else if workspace.sidebarSelection == .trash {
            ContentUnavailableView("Corbeille vide", systemImage: "trash",
                                   description: Text("Les notes supprimées y restent 30 jours."))
        } else if workspace.sidebarSelection == .recordings {
            ContentUnavailableView("Aucun enregistrement", systemImage: "waveform",
                                   description: Text("Lancez un enregistrement avec ⇧⌘R."))
        } else {
            ContentUnavailableView {
                Label("Aucune note", systemImage: "note.text")
            } description: {
                Text("Créez une note avec ⌘N.")
            } actions: {
                Button("Nouvelle note") { workspace.createNote() }
            }
        }
    }

    // MARK: Lignes

    private func row(for note: Note) -> some View {
        NoteRow(
            note: note,
            showsFolder: !isFolderScope,
            snippet: workspace.searchText.isEmpty ? nil : NoteSearch.snippet(body: note.plainText, query: workspace.searchText)
        )
        .tag(note.uid)
        .draggable(DragPayload.note(note.uid)) {
            Label(note.displayTitle, systemImage: "note.text")
                .padding(Spacing.s)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Radius.s))
        }
        .contextMenu { contextMenu(for: note) }
    }

    private var isFolderScope: Bool {
        if case .folder = workspace.sidebarSelection, workspace.searchText.isEmpty { return true }
        return false
    }

    @ViewBuilder
    private func contextMenu(for note: Note) -> some View {
        if note.isTrashed {
            Button("Restaurer") { workspace.restore(note) }
            Button("Supprimer définitivement", role: .destructive) { workspace.deletePermanently(note) }
        } else {
            Button(note.isPinned ? "Désépingler" : "Épingler") { note.isPinned.toggle() }
            Menu("Déplacer vers") {
                Button("Aucun dossier") { workspace.move(note, to: nil) }
                Divider()
                ForEach(folders.sorted { $0.pathDescription.localizedStandardCompare($1.pathDescription) == .orderedAscending }) { folder in
                    Button(folder.pathDescription) { workspace.move(note, to: folder) }
                }
            }
            Button("Dupliquer") { workspace.duplicate(note) }
            Divider()
            Button("Mettre à la corbeille", role: .destructive) { workspace.trash(note) }
        }
    }
}

struct NoteRow: View {
    let note: Note
    let showsFolder: Bool
    let snippet: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: Spacing.xs) {
                Text(note.displayTitle)
                    .font(.headline)
                    .lineLimit(1)
                Spacer(minLength: Spacing.xs)
                if note.hasRecording {
                    Image(systemName: "waveform").font(.caption).foregroundStyle(.secondary)
                }
                if note.isPinned {
                    Image(systemName: "pin.fill").font(.caption).foregroundStyle(.secondary)
                }
            }
            HStack(alignment: .firstTextBaseline, spacing: Spacing.s) {
                Text(note.updatedAt.noteListFormatted)
                    .foregroundStyle(.secondary)
                    .fixedSize()
                Text(snippet ?? (note.preview.isEmpty ? String(localized: "Aucun contenu") : note.preview))
                    .foregroundStyle(.tertiary)
                    .lineLimit(2)
            }
            .font(.callout)
            if showsFolder, let folder = note.folder {
                Label {
                    Text(folder.pathDescription)
                } icon: {
                    Image(systemName: folder.symbolName).foregroundStyle(folder.color.color)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .labelStyle(.titleAndIcon)
            }
        }
        .padding(.vertical, Spacing.xs)
    }
}
