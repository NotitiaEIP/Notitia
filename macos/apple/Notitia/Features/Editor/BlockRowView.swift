import NotitiaKit
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

/// Une ligne de l'éditeur : gouttière (poignée), indentation, marqueur et contenu.
struct BlockRowView: View {
    let block: Block
    let model: NoteEditorModel
    let number: Int?
    /// Bloc compris dans une sélection de blocs.
    var isSelected = false
    @Environment(Workspace.self) private var workspace
    @Environment(RecordingController.self) private var recorder
    @State private var isHovering = false
    @State private var confirmDeleteRecording = false
    @State private var isDropTargeted = false

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            gutter
            Spacer().frame(width: CGFloat(block.indent) * Metrics.indentWidth)
            content
        }
        .padding(.top, topPadding)
        .padding(.bottom, bottomPadding)
        .background {
            if isSelected {
                RoundedRectangle(cornerRadius: Radius.s)
                    .fill(Color.accentColor.opacity(0.18))
                    .padding(.leading, Metrics.gutterWidth - Spacing.xs)
            }
        }
        // ⇧-clic (hors du texte, ou sur un bloc sans texte) : sélection de plusieurs blocs.
        .gesture(TapGesture().modifiers(.shift).onEnded { model.extendBlockSelection(to: block.id) })
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .overlay(alignment: .top) {
            if isDropTargeted {
                Rectangle().fill(Color.accentColor).frame(height: 2).padding(.leading, Metrics.gutterWidth)
            }
        }
        .overlay(alignment: .topLeading) {
            if let menu = model.slashMenu, menu.blockID == block.id {
                SlashMenuView(menu: menu, model: model)
                    .offset(x: Metrics.gutterWidth + CGFloat(block.indent) * Metrics.indentWidth,
                            y: RichTextConverter.lineHeight(for: block.kind) + 6)
            }
        }
        .onHover { isHovering = $0 }
        .dropDestination(for: String.self) { items, _ in
            guard let first = items.first, case .block(let uid) = DragPayload(first) else { return false }
            model.move(uid, before: block.id)
            return true
        } isTargeted: { isDropTargeted = $0 }
    }

    private var topPadding: CGFloat {
        switch block.kind {
        case .heading1: 18
        case .heading2: 14
        case .heading3: 10
        case .divider: 6
        case .code, .callout, .image, .transcript, .mindMap: 6
        default: 1
        }
    }

    private var bottomPadding: CGFloat {
        switch block.kind {
        case .heading1, .heading2, .heading3: 4
        case .code, .callout, .image, .transcript, .mindMap, .divider: 6
        default: 1
        }
    }

    // MARK: Gouttière

    private var gutter: some View {
        Menu {
            // Une transcription ne se transforme pas et ne se duplique pas : elle représente un enregistrement.
            if !block.kind.requiresConfirmedDeletion {
                Section("Transformer en") {
                    ForEach(BlockKind.insertable.filter(\.hasText)) { kind in
                        Button { model.convert(block.id, to: kind) } label: { Label(kind.displayName, systemImage: kind.symbolName) }
                    }
                }
            }
            Section {
                Menu("Insérer en dessous") {
                    ForEach(BlockKind.insertable) { kind in
                        Button { model.insertBlock(kind, after: block.id) } label: { Label(kind.displayName, systemImage: kind.symbolName) }
                    }
                }
                Button("Sélectionner") { model.selectBlock(block.id) }
                Button("Monter") { model.shift(block.id, by: -1) }
                Button("Descendre") { model.shift(block.id, by: 1) }
                if !block.kind.requiresConfirmedDeletion {
                    Button("Dupliquer") { model.duplicate(block.id) }
                }
            }
            Section {
                if block.kind.requiresConfirmedDeletion {
                    Button("Supprimer l'enregistrement…", role: .destructive) { confirmDeleteRecording = true }
                        .disabled(block.recordingID != nil && recorder.recordingID == block.recordingID)
                } else {
                    Button("Supprimer le bloc", role: .destructive) { model.delete(block.id) }
                }
            }
        } label: {
            Image(systemName: "line.3.horizontal")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.tertiary)
                .frame(width: 18, height: RichTextConverter.lineHeight(for: block.kind))
                .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        .frame(width: Metrics.gutterWidth, alignment: .leading)
        .opacity(isHovering && !model.isReadOnly ? 1 : 0)
        .draggable(DragPayload.block(block.id)) {
            Label(block.text.plainText.isEmpty ? block.kind.displayName : String(block.text.plainText.prefix(40)), systemImage: block.kind.symbolName)
                .padding(Spacing.s)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Radius.s))
        }
        .help("Glisser pour déplacer, cliquer pour les options")
        .disabled(model.isReadOnly)
        .confirmationDialog("Supprimer cet enregistrement ?", isPresented: $confirmDeleteRecording) {
            Button("Supprimer l'audio et la transcription", role: .destructive) { deleteRecording() }
        } message: {
            Text("L'audio chiffré et le texte transcrit seront supprimés définitivement.")
        }
    }

    private func deleteRecording() {
        guard let uid = block.recordingID,
              let recording = try? workspace.context.fetch(FetchDescriptor<Recording>(predicate: #Predicate { $0.uid == uid })).first
        else {
            // Bloc sans enregistrement (déjà supprimé) : il n'y a plus rien à protéger.
            model.delete(block.id)
            return
        }
        workspace.deleteRecording(recording)
    }

    // MARK: Contenu

    @ViewBuilder
    private var content: some View {
        switch block.kind {
        case .divider:
            // Pas de `Divider()` : dans un HStack il serait vertical.
            Rectangle()
                .fill(.separator)
                .frame(maxWidth: .infinity)
                .frame(height: 1)
                .padding(.vertical, Spacing.m)
        case .image:
            ImageBlockView(block: block, model: model)
        case .transcript:
            TranscriptBlockView(block: block, model: model)
        case .mindMap:
            MindMapBlockView(block: block, model: model)
        default:
            HStack(alignment: .top, spacing: Spacing.s) {
                marker
                text
            }
            .modifier(BlockDecoration(kind: block.kind))
        }
    }

    private var text: some View {
        RichTextView(
            block: block,
            model: model,
            placeholder: placeholder,
            showsPlaceholderWhenInactive: block.kind.isHeading || block.kind.isList
        )
    }

    private var placeholder: String {
        switch block.kind {
        case .paragraph: String(localized: "Écrivez, ou tapez « / » pour les commandes")
        case .heading1: String(localized: "Titre 1")
        case .heading2: String(localized: "Titre 2")
        case .heading3: String(localized: "Titre 3")
        case .bulleted, .numbered: String(localized: "Élément de liste")
        case .todo: String(localized: "Tâche")
        case .quote: String(localized: "Citation")
        case .callout: String(localized: "Encadré")
        case .code: String(localized: "Code")
        default: ""
        }
    }

    @ViewBuilder
    private var marker: some View {
        let lineHeight = RichTextConverter.lineHeight(for: block.kind)
        switch block.kind {
        case .bulleted:
            Image(systemName: bulletSymbol)
                .font(.system(size: 6, weight: .bold))
                .foregroundStyle(.secondary)
                .frame(width: 16, height: lineHeight)
        case .numbered:
            Text("\(number ?? 1).")
                .font(.system(size: 15).monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(minWidth: 16, alignment: .trailing)
                .frame(height: lineHeight)
        case .todo:
            Button {
                model.toggleChecked(block.id)
            } label: {
                Image(systemName: block.isChecked ? "checkmark.square.fill" : "square")
                    .font(.system(size: 15))
                    .foregroundStyle(block.isChecked ? Color.accentColor : .secondary)
            }
            .buttonStyle(.plain)
            .frame(width: 18, height: lineHeight)
            .disabled(model.isReadOnly)
            .accessibilityLabel(block.isChecked ? "Décocher" : "Cocher")
        case .callout:
            Image(systemName: "lightbulb")
                .foregroundStyle(.secondary)
                .frame(width: 18, height: lineHeight)
        default:
            EmptyView()
        }
    }

    private var bulletSymbol: String {
        switch block.indent % 3 {
        case 0: "circle.fill"
        case 1: "circle"
        default: "square.fill"
        }
    }
}

/// Fond et bordures selon le type de bloc (citation, encadré, code).
private struct BlockDecoration: ViewModifier {
    let kind: BlockKind

    func body(content: Content) -> some View {
        switch kind {
        case .quote:
            content
                .padding(.leading, Spacing.m)
                .overlay(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 1.5).fill(.tertiary).frame(width: 3)
                }
        case .callout:
            content
                .padding(Spacing.m)
                .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: Radius.m))
        case .code:
            content
                .padding(Spacing.m)
                .background(.quaternary.opacity(0.6), in: RoundedRectangle(cornerRadius: Radius.s))
        default:
            content
        }
    }
}

// MARK: - Menu « / »

struct SlashMenuView: View {
    let menu: SlashMenuState
    let model: NoteEditorModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Blocs")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, Spacing.m)
                .padding(.vertical, Spacing.s)
            if menu.items.isEmpty {
                Text("Aucun résultat")
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, Spacing.m)
                    .padding(.bottom, Spacing.s)
            }
            ForEach(Array(menu.items.enumerated()), id: \.element) { index, kind in
                HStack(spacing: Spacing.s) {
                    Image(systemName: kind.symbolName)
                        .frame(width: 22)
                        .foregroundStyle(index == menu.selectedIndex ? .white : .secondary)
                    Text(kind.displayName)
                    Spacer()
                }
                .padding(.horizontal, Spacing.s)
                .padding(.vertical, 5)
                .foregroundStyle(index == menu.selectedIndex ? .white : .primary)
                .background {
                    if index == menu.selectedIndex {
                        RoundedRectangle(cornerRadius: Radius.s).fill(Color.accentColor)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture { model.choose(kind, for: menu.blockID); model.closeSlashMenu() }
                .padding(.horizontal, Spacing.xs)
            }
        }
        .padding(.bottom, Spacing.xs)
        .frame(width: 240)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Radius.m))
        .overlay(RoundedRectangle(cornerRadius: Radius.m).strokeBorder(.separator))
        .shadow(color: .black.opacity(0.15), radius: 12, y: 4)
    }
}

// MARK: - Image

struct ImageBlockView: View {
    let block: Block
    let model: NoteEditorModel
    @State private var isImporting = false

    var body: some View {
        Group {
            if let name = block.attachment, let image = NSImage(contentsOf: model.attachmentURL(name)) {
                Image(nsImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxWidth: .infinity, maxHeight: 480, alignment: .leading)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.m))
            } else {
                Button {
                    isImporting = true
                } label: {
                    Label("Choisir une image ou la déposer ici", systemImage: "photo.badge.plus")
                        .frame(maxWidth: .infinity, minHeight: 90)
                        .foregroundStyle(.secondary)
                        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: Radius.m))
                        .overlay(RoundedRectangle(cornerRadius: Radius.m).strokeBorder(style: StrokeStyle(lineWidth: 1, dash: [5])).foregroundStyle(.tertiary))
                }
                .buttonStyle(.plain)
                .disabled(model.isReadOnly)
            }
        }
        .dropDestination(for: URL.self) { urls, _ in
            guard let url = urls.first, UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) == true else { return false }
            model.setImage(url, for: block.id)
            return true
        }
        .fileImporter(isPresented: $isImporting, allowedContentTypes: [.image]) { result in
            if case .success(let url) = result { model.setImage(url, for: block.id) }
        }
    }
}
