import NotitiaKit
import SwiftData
import SwiftUI

/// Ouverture rapide d'une note (⌘K).
struct QuickOpenView: View {
    @Environment(Workspace.self) private var workspace
    @Environment(\.dismiss) private var dismiss
    @Query(filter: #Predicate<Note> { $0.trashedAt == nil }, sort: \Note.updatedAt, order: .reverse) private var notes: [Note]
    @State private var query = ""
    @State private var selection = 0
    @FocusState private var isFocused: Bool

    private var results: [Note] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return Array(notes.prefix(8)) }
        return notes
            .compactMap { note in NoteSearch.score(title: note.title, body: note.plainText, query: trimmed).map { (note, $0) } }
            .sorted { $0.1 > $1.1 }
            .prefix(8)
            .map(\.0)
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Ouvrir une note…", text: $query)
                    .textFieldStyle(.plain)
                    .font(.title3)
                    .focused($isFocused)
                    .onSubmit(open)
                    .onKeyPress(.downArrow) { selection = min(selection + 1, max(0, results.count - 1)); return .handled }
                    .onKeyPress(.upArrow) { selection = max(selection - 1, 0); return .handled }
            }
            .padding(Spacing.l)
            Divider()
            VStack(spacing: 0) {
                ForEach(Array(results.enumerated()), id: \.element.uid) { index, note in
                    HStack {
                        Image(systemName: note.folder?.symbolName ?? "note.text")
                            .foregroundStyle(note.folder?.color.color ?? .secondary)
                            .frame(width: 20)
                        VStack(alignment: .leading) {
                            Text(note.displayTitle)
                            if let path = note.folder?.pathDescription {
                                Text(path).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                    }
                    .padding(.horizontal, Spacing.l)
                    .padding(.vertical, Spacing.s)
                    .background(index == selection ? Color.accentColor.opacity(0.18) : .clear)
                    .contentShape(Rectangle())
                    .onTapGesture { selection = index; open() }
                }
                if results.isEmpty {
                    Text("Aucune note").foregroundStyle(.secondary).padding(Spacing.l)
                }
            }
            .padding(.vertical, Spacing.xs)
        }
        .frame(width: 520)
        .onAppear { isFocused = true }
        .onChange(of: query) { selection = 0 }
        .onExitCommand { dismiss() }
    }

    private func open() {
        guard results.indices.contains(selection) else { return }
        let note = results[selection]
        workspace.searchText = ""
        if let folder = note.folder { workspace.sidebarSelection = .folder(folder.uid) } else { workspace.sidebarSelection = .allNotes }
        workspace.selectedNoteID = note.uid
        dismiss()
    }
}
