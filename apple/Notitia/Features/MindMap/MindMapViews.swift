import NotitiaKit
import SwiftUI

/// Bloc carte mentale dans une note.
struct MindMapBlockView: View {
    let block: Block
    let model: NoteEditorModel
    @State private var isExpanded = false

    var body: some View {
        if let map = block.mindMap {
            VStack(alignment: .leading, spacing: Spacing.s) {
                HStack {
                    Label(map.title, systemImage: "point.3.connected.trianglepath.dotted")
                        .font(.headline)
                    Spacer()
                    Text("\(map.root.nodeCount) nœuds")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button {
                        isExpanded = true
                    } label: {
                        Label("Agrandir", systemImage: "arrow.up.left.and.arrow.down.right")
                    }
                    .buttonStyle(.borderless)
                }
                MindMapCanvas(document: binding(for: map), isEditable: !model.isReadOnly, onSelectSource: model.revealSource)
                    .frame(height: 380)
                    .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: Radius.m))
                    .overlay(RoundedRectangle(cornerRadius: Radius.m).strokeBorder(.separator))
            }
            .sheet(isPresented: $isExpanded) {
                MindMapSheet(document: binding(for: map), isEditable: !model.isReadOnly, onSelectSource: { text in
                    isExpanded = false
                    model.revealSource(text)
                })
            }
        }
    }

    private func binding(for map: MindMapDocument) -> Binding<MindMapDocument> {
        Binding(
            get: { block.mindMap ?? map },
            set: { newValue in model.updateBlock(block.id, coalescing: true) { $0.mindMap = newValue } }
        )
    }
}

struct MindMapSheet: View {
    @Binding var document: MindMapDocument
    let isEditable: Bool
    let onSelectSource: (String?) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(document.title).font(.title3.bold())
                Spacer()
                Text("Cliquez sur un nœud pour voir sa source · double-cliquez pour le renommer")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("Fermer") { dismiss() }
                    .keyboardShortcut(.cancelAction)
            }
            .padding(Spacing.l)
            Divider()
            MindMapCanvas(document: $document, isEditable: isEditable, onSelectSource: onSelectSource)
        }
        .frame(minWidth: 900, idealWidth: 1200, minHeight: 600, idealHeight: 800)
    }
}

/// Carte mentale interactive : zoom, repli des branches, renommage, lien vers la source.
struct MindMapCanvas: View {
    @Binding var document: MindMapDocument
    let isEditable: Bool
    let onSelectSource: (String?) -> Void
    @State private var zoom: CGFloat = 1
    @State private var hasFitted = false
    @State private var selectedID: UUID?
    @State private var editingID: UUID?
    @State private var editingLabel = ""

    private static let branchColors: [NoteColor] = [.blue, .orange, .green, .purple, .pink, .teal, .indigo, .brown]

    var body: some View {
        let layout = MindMapLayout(root: document.root)
        let colors = branchColorMap()
        ScrollView([.horizontal, .vertical]) {
            ZStack(alignment: .topLeading) {
                Canvas { context, _ in
                    for edge in layout.edges {
                        guard let from = layout.frames[edge.from], let to = layout.frames[edge.to] else { continue }
                        let side = CGFloat(layout.sides[edge.to] ?? 1)
                        let start = CGPoint(x: side > 0 ? from.maxX : from.minX, y: from.midY)
                        let end = CGPoint(x: side > 0 ? to.minX : to.maxX, y: to.midY)
                        let dx = (end.x - start.x) * 0.5
                        var path = Path()
                        path.move(to: start)
                        path.addCurve(to: end, control1: CGPoint(x: start.x + dx, y: start.y), control2: CGPoint(x: end.x - dx, y: end.y))
                        let color = (colors[edge.to] ?? .gray).color
                        context.stroke(path, with: .color(color.opacity(0.55)), lineWidth: edge.from == document.root.id ? 2.5 : 1.5)
                    }
                }
                .frame(width: layout.bounds.width, height: layout.bounds.height)

                ForEach(visibleNodes(), id: \.id) { node in
                    if let frame = layout.frames[node.id] {
                        nodeView(node, color: colors[node.id] ?? .gray)
                            .frame(width: frame.width, height: frame.height)
                            .position(x: frame.midX, y: frame.midY)
                    }
                }
            }
            .frame(width: layout.bounds.width, height: layout.bounds.height)
            .scaleEffect(zoom, anchor: .topLeading)
            .frame(width: layout.bounds.width * zoom, height: layout.bounds.height * zoom, alignment: .topLeading)
            .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity)
        }
        .defaultScrollAnchor(.center)
        // Au premier affichage, la carte est réduite pour tenir dans la largeur disponible.
        .onGeometryChange(for: CGSize.self, of: \.size) { size in
            guard !hasFitted, size.width > 0 else { return }
            hasFitted = true
            let fit = min(size.width / layout.bounds.width, size.height / layout.bounds.height)
            zoom = min(1, max(0.6, fit))
        }
        .gesture(MagnifyGesture().onChanged { value in zoom = min(2.5, max(0.4, value.magnification)) })
        .overlay(alignment: .bottomTrailing) { zoomControls.padding(Spacing.m) }
    }

    private var zoomControls: some View {
        HStack(spacing: 0) {
            Button { zoom = max(0.4, zoom - 0.15) } label: { Image(systemName: "minus").frame(width: 24, height: 20) }
            Button { zoom = 1 } label: { Text("\(Int(zoom * 100)) %").font(.caption.monospacedDigit()).frame(width: 46) }
            Button { zoom = min(2.5, zoom + 0.15) } label: { Image(systemName: "plus").frame(width: 24, height: 20) }
        }
        .buttonStyle(.borderless)
        .padding(.horizontal, Spacing.xs)
        .padding(.vertical, Spacing.xxs)
        .glassEffect(.regular, in: Capsule())
    }

    @ViewBuilder
    private func nodeView(_ node: MindMapNode, color: NoteColor) -> some View {
        let isRoot = node.kind == .root
        let isSelected = selectedID == node.id
        HStack(spacing: 6) {
            Image(systemName: node.kind.symbolName)
                .font(.system(size: isRoot ? 13 : 11, weight: .semibold))
                .foregroundStyle(isRoot ? Color.primary : node.kind.color.color)
            if editingID == node.id {
                TextField("", text: $editingLabel)
                    .textFieldStyle(.plain)
                    .onSubmit { commitEdit(node.id) }
                    .onExitCommand { editingID = nil }
            } else {
                Text(node.label)
                    .font(isRoot ? .headline : .callout)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            if !node.children.isEmpty, !isRoot {
                Button {
                    document.root.update(node.id) { $0.isCollapsed.toggle() }
                } label: {
                    Image(systemName: node.isCollapsed ? "plus.circle.fill" : "minus.circle")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .help(node.isCollapsed ? "Déplier" : "Replier")
            }
        }
        .padding(.horizontal, 10)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            RoundedRectangle(cornerRadius: isRoot ? Radius.l : Radius.m)
                .fill(isRoot ? Color.accentColor.opacity(0.16) : color.color.opacity(0.10))
        }
        .overlay {
            RoundedRectangle(cornerRadius: isRoot ? Radius.l : Radius.m)
                .strokeBorder(isSelected ? Color.accentColor : (isRoot ? Color.accentColor.opacity(0.5) : color.color.opacity(0.45)),
                              lineWidth: isSelected ? 2 : 1)
        }
        .contentShape(Rectangle())
        .help(node.detail ?? node.label)
        .onTapGesture(count: 2) {
            guard isEditable else { return }
            editingLabel = node.label
            editingID = node.id
        }
        .onTapGesture {
            selectedID = node.id
            onSelectSource(node.sourceText)
        }
    }

    private func commitEdit(_ id: UUID) {
        let label = editingLabel.trimmingCharacters(in: .whitespaces)
        if !label.isEmpty { document.root.update(id) { $0.label = label } }
        editingID = nil
    }

    private func visibleNodes() -> [MindMapNode] {
        var result: [MindMapNode] = []
        func visit(_ node: MindMapNode) {
            result.append(node)
            if !node.isCollapsed { node.children.forEach(visit) }
        }
        visit(document.root)
        return result
    }

    /// Couleur de branche : chaque branche principale a sa couleur, héritée par ses descendants.
    private func branchColorMap() -> [UUID: NoteColor] {
        var map: [UUID: NoteColor] = [document.root.id: .gray]
        func assign(_ node: MindMapNode, _ color: NoteColor) {
            map[node.id] = color
            node.children.forEach { assign($0, color) }
        }
        for (index, child) in document.root.children.enumerated() {
            assign(child, Self.branchColors[index % Self.branchColors.count])
        }
        return map
    }
}
