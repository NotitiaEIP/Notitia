import Foundation

/// Nature d'un nœud de carte mentale. La taxonomie vient de l'application Flutter
/// (`lib/models/mind_map.dart`) : on garde les mêmes catégories, mais avec des
/// SF Symbols et des couleurs système à la place des emojis et des couleurs néon.
public enum MindMapNodeKind: String, Codable, CaseIterable, Sendable {
    case root, topic, subtopic, idea, action, question, decision, person, date, location, reference

    public var displayName: String {
        switch self {
        case .root: "Sujet"
        case .topic: "Thème"
        case .subtopic: "Sous-thème"
        case .idea: "Idée"
        case .action: "Action"
        case .question: "Question"
        case .decision: "Décision"
        case .person: "Personne"
        case .date: "Date"
        case .location: "Lieu"
        case .reference: "Référence"
        }
    }

    public var symbolName: String {
        switch self {
        case .root: "circle.hexagongrid"
        case .topic: "tag"
        case .subtopic: "tag.circle"
        case .idea: "lightbulb"
        case .action: "checkmark.circle"
        case .question: "questionmark.circle"
        case .decision: "checkmark.seal"
        case .person: "person"
        case .date: "calendar"
        case .location: "mappin"
        case .reference: "link"
        }
    }

    public var color: NoteColor {
        switch self {
        case .root: .gray
        case .topic: .blue
        case .subtopic: .indigo
        case .idea: .yellow
        case .action: .green
        case .question: .orange
        case .decision: .purple
        case .person: .teal
        case .date: .red
        case .location: .mint
        case .reference: .brown
        }
    }
}

public struct MindMapNode: Identifiable, Codable, Hashable, Sendable {
    public var id: UUID
    public var label: String
    public var detail: String?
    public var kind: MindMapNodeKind
    /// Passage exact de la source dont le nœud est tiré (règle « sourceText » de l'ancienne app).
    public var sourceText: String?
    public var isCollapsed: Bool
    public var children: [MindMapNode]

    public init(
        id: UUID = UUID(),
        label: String,
        detail: String? = nil,
        kind: MindMapNodeKind,
        sourceText: String? = nil,
        isCollapsed: Bool = false,
        children: [MindMapNode] = []
    ) {
        self.id = id
        self.label = label
        self.detail = detail
        self.kind = kind
        self.sourceText = sourceText
        self.isCollapsed = isCollapsed
        self.children = children
    }

    public var nodeCount: Int { 1 + children.reduce(0) { $0 + $1.nodeCount } }

    /// Trouve un nœud par identifiant et le modifie.
    @discardableResult
    public mutating func update(_ id: UUID, _ transform: (inout MindMapNode) -> Void) -> Bool {
        if self.id == id {
            transform(&self)
            return true
        }
        for index in children.indices where children[index].update(id, transform) {
            return true
        }
        return false
    }

    public func find(_ id: UUID) -> MindMapNode? {
        if self.id == id { return self }
        for child in children {
            if let found = child.find(id) { return found }
        }
        return nil
    }
}

public struct MindMapDocument: Codable, Hashable, Sendable {
    public var title: String
    public var root: MindMapNode
    public var generatedAt: Date

    public init(title: String, root: MindMapNode, generatedAt: Date = .now) {
        self.title = title
        self.root = root
        self.generatedAt = generatedAt
    }

    public var allLabels: [String] {
        var labels: [String] = []
        func visit(_ node: MindMapNode) {
            labels.append(node.label)
            node.children.forEach(visit)
        }
        visit(root)
        return labels
    }
}
