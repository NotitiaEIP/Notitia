import NotitiaKit
import SwiftData
import SwiftUI

struct SidebarView: View {
    @Environment(Workspace.self) private var workspace
    @Query(sort: [SortDescriptor<Folder>(\.sortIndex), SortDescriptor<Folder>(\.name)]) private var folders: [Folder]
    @Query(sort: \NotitiaKit.Tag.name) private var tags: [NotitiaKit.Tag]
    @Query(filter: #Predicate<Note> { $0.trashedAt == nil }) private var activeNotes: [Note]
    @Query(filter: #Predicate<Note> { $0.trashedAt != nil }) private var trashedNotes: [Note]
    @State private var folderToDelete: Folder?
    @State private var isRootDropTargeted = false

    var body: some View {
        @Bindable var workspace = workspace
        let rootFolders = folders.filter { $0.parent == nil }
        List(selection: $workspace.sidebarSelection) {
            Section {
                Label("Toutes les notes", systemImage: "note.text")
                    .badge(activeNotes.count)
                    .tag(SidebarItem.allNotes)
                Label("Épinglées", systemImage: "pin")
                    .badge(activeNotes.filter(\.isPinned).count)
                    .tag(SidebarItem.pinned)
                Label("Récentes", systemImage: "clock")
                    .tag(SidebarItem.recent)
                Label("Enregistrements", systemImage: "waveform")
                    .badge(activeNotes.filter(\.hasRecording).count)
                    .tag(SidebarItem.recordings)
            }

            Section {
                OutlineGroup(rootFolders, children: \.sortedChildrenOrNil) { folder in
                    FolderRow(folder: folder, onDelete: { folderToDelete = folder })
                        .tag(SidebarItem.folder(folder.uid))
                }
            } header: {
                HStack {
                    Text("Dossiers")
                    Spacer()
                    Button {
                        workspace.requestNewFolder(parent: nil)
                    } label: {
                        Image(systemName: "plus")
                    }
                    .buttonStyle(.plain)
                    .help("Nouveau dossier (⇧⌘N)")
                }
                .dropDestination(for: String.self) { items, _ in
                    workspace.handleDrop(items, onto: nil)
                }
            }

            if !tags.isEmpty {
                Section("Tags") {
                    ForEach(tags) { tag in
                        Label {
                            Text(tag.name)
                        } icon: {
                            Image(systemName: "number").foregroundStyle(tag.color.color)
                        }
                        .badge(tag.notes.filter { !$0.isTrashed }.count)
                        .tag(SidebarItem.tag(tag.uid))
                        .contextMenu {
                            Menu("Couleur") {
                                ForEach(NoteColor.allCases) { color in
                                    Button(color.displayName) { tag.color = color }
                                }
                            }
                            Button("Supprimer le tag", role: .destructive) {
                                if workspace.sidebarSelection == .tag(tag.uid) { workspace.sidebarSelection = .allNotes }
                                tag.modelContext?.delete(tag)
                            }
                        }
                    }
                }
            }

            Section {
                Label("Corbeille", systemImage: "trash")
                    .badge(trashedNotes.count)
                    .tag(SidebarItem.trash)
                    .dropDestination(for: String.self) { items, _ in
                        var handled = false
                        for item in items {
                            if case .note(let uid) = DragPayload(item), let note = workspace.note(uid) {
                                workspace.trash(note)
                                handled = true
                            }
                        }
                        return handled
                    }
            }
        }
        .listStyle(.sidebar)
        .safeAreaInset(edge: .bottom) {
            HStack {
                Button {
                    workspace.requestNewFolder(parent: workspace.currentFolder)
                } label: {
                    Label("Nouveau dossier", systemImage: "folder.badge.plus")
                }
                .buttonStyle(.borderless)
                Spacer()
            }
            .padding(Spacing.m)
        }
        .confirmationDialog(
            "Supprimer le dossier « \(folderToDelete?.name ?? "") » ?",
            isPresented: Binding(get: { folderToDelete != nil }, set: { if !$0 { folderToDelete = nil } }),
            presenting: folderToDelete
        ) { folder in
            Button("Supprimer", role: .destructive) { workspace.deleteFolder(folder) }
        } message: { folder in
            let count = folder.subtree.reduce(0) { $0 + $1.notes.filter { !$0.isTrashed }.count }
            Text("Ses sous-dossiers seront supprimés et ses \(count) note(s) placée(s) dans la corbeille.")
        }
    }
}

struct FolderRow: View {
    let folder: Folder
    let onDelete: () -> Void
    @Environment(Workspace.self) private var workspace
    @State private var isTargeted = false

    var body: some View {
        Label {
            Text(folder.name)
        } icon: {
            Image(systemName: folder.symbolName)
                .foregroundStyle(folder.color.color)
        }
        .badge(folder.notes.filter { !$0.isTrashed }.count)
        .draggable(DragPayload.folder(folder.uid)) {
            Label(folder.name, systemImage: folder.symbolName)
                .padding(Spacing.s)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Radius.s))
        }
        .dropDestination(for: String.self) { items, _ in
            workspace.handleDrop(items, onto: folder)
        } isTargeted: { isTargeted = $0 }
        .listRowBackground(isTargeted ? RoundedRectangle(cornerRadius: Radius.s).fill(Color.accentColor.opacity(0.2)) : nil)
        .contextMenu {
            Button("Nouvelle note ici") {
                workspace.sidebarSelection = .folder(folder.uid)
                workspace.createNote()
            }
            Button("Nouveau sous-dossier") { workspace.requestNewFolder(parent: folder) }
            Divider()
            Button("Renommer et personnaliser…") {
                workspace.folderEditor = FolderEditorRequest(folder: folder, parent: folder.parent)
            }
            if folder.parent != nil {
                Button("Déplacer à la racine") { _ = workspace.handleDrop([DragPayload.folder(folder.uid)], onto: nil) }
            }
            Divider()
            Button("Supprimer le dossier…", role: .destructive, action: onDelete)
        }
    }
}

// MARK: - Création / édition d'un dossier

struct FolderEditorSheet: View {
    let request: FolderEditorRequest
    @Environment(Workspace.self) private var workspace
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var symbol = "folder"
    @State private var color = NoteColor.blue

    private var isNew: Bool { request.folder == nil }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            HStack(spacing: Spacing.m) {
                Image(systemName: symbol)
                    .font(.system(size: 26))
                    .foregroundStyle(color.color)
                    .frame(width: 44, height: 44)
                    .background(color.color.opacity(0.12), in: RoundedRectangle(cornerRadius: Radius.m))
                VStack(alignment: .leading, spacing: 2) {
                    Text(isNew ? "Nouveau dossier" : "Modifier le dossier").font(.title3.bold())
                    if let parent = request.parent {
                        Text("Dans \(parent.pathDescription)").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }

            TextField("Nom (par exemple : Chinois, Réunions d'équipe…)", text: $name)
                .textFieldStyle(.roundedBorder)
                .onSubmit(save)

            VStack(alignment: .leading, spacing: Spacing.s) {
                Text("Couleur").font(.subheadline.weight(.semibold))
                HStack(spacing: Spacing.s) {
                    ForEach(NoteColor.allCases) { option in
                        ColorSwatch(color: option, isSelected: option == color)
                            .onTapGesture { color = option }
                    }
                }
            }

            VStack(alignment: .leading, spacing: Spacing.s) {
                Text("Icône").font(.subheadline.weight(.semibold))
                LazyVGrid(columns: Array(repeating: GridItem(.fixed(34), spacing: Spacing.s), count: 8), spacing: Spacing.s) {
                    ForEach(FolderSymbols.all, id: \.self) { option in
                        Image(systemName: option)
                            .frame(width: 34, height: 30)
                            .foregroundStyle(option == symbol ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
                            .background(option == symbol ? AnyShapeStyle(color.color) : AnyShapeStyle(.quaternary.opacity(0.5)),
                                        in: RoundedRectangle(cornerRadius: Radius.s))
                            .onTapGesture { symbol = option }
                    }
                }
            }

            HStack {
                Spacer()
                Button("Annuler", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(isNew ? "Créer" : "Enregistrer", action: save)
                    .keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(Spacing.xl)
        .frame(width: 420)
        .onAppear {
            if let folder = request.folder {
                name = folder.name
                symbol = folder.symbolName
                color = folder.color
            }
        }
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        if let folder = request.folder {
            folder.name = trimmed
            folder.symbolName = symbol
            folder.color = color
        } else {
            let siblings = request.parent?.children.count ?? 0
            let folder = Folder(name: trimmed, symbolName: symbol, color: color, parent: request.parent, sortIndex: siblings + 100)
            workspace.context.insert(folder)
            try? workspace.context.save()
            workspace.sidebarSelection = .folder(folder.uid)
        }
        try? workspace.context.save()
        dismiss()
    }
}
