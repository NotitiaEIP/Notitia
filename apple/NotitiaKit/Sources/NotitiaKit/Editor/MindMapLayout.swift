import CoreGraphics
import Foundation

/// Disposition d'une carte mentale : la racine au centre, les branches réparties
/// à droite puis à gauche, chaque sous-arbre empilé verticalement sans chevauchement.
public struct MindMapLayout: Sendable {
    public struct Edge: Hashable, Sendable {
        public var from: UUID
        public var to: UUID
    }

    public var frames: [UUID: CGRect]
    public var edges: [Edge]
    /// Côté de chaque nœud : +1 à droite de la racine, -1 à gauche, 0 pour la racine.
    public var sides: [UUID: Int]
    public var bounds: CGRect

    public struct Metrics: Sendable {
        public var nodeHeight: CGFloat = 34
        public var rootHeight: CGFloat = 46
        public var horizontalGap: CGFloat = 56
        public var verticalGap: CGFloat = 12
        public var characterWidth: CGFloat = 7.2
        public var minWidth: CGFloat = 70
        public var maxWidth: CGFloat = 260
        public var padding: CGFloat = 40

        public init() {}

        public func size(for node: MindMapNode) -> CGSize {
            let isRoot = node.kind == .root
            let width = min(maxWidth, max(minWidth, CGFloat(node.label.count) * characterWidth * (isRoot ? 1.25 : 1) + 66))
            return CGSize(width: width, height: isRoot ? rootHeight : nodeHeight)
        }
    }

    public init(root: MindMapNode, metrics: Metrics = Metrics()) {
        var frames: [UUID: CGRect] = [:]
        var edges: [Edge] = []
        var sides: [UUID: Int] = [:]

        let visibleChildren = root.isCollapsed ? [] : root.children
        let rightCount = Int((Double(visibleChildren.count) / 2).rounded(.up))
        let right = Array(visibleChildren.prefix(rightCount))
        let left = Array(visibleChildren.dropFirst(rightCount))

        func subtreeHeight(_ node: MindMapNode) -> CGFloat {
            let own = metrics.size(for: node).height
            guard !node.isCollapsed, !node.children.isEmpty else { return own }
            let children = node.children.map(subtreeHeight).reduce(0, +)
                + metrics.verticalGap * CGFloat(node.children.count - 1)
            return max(own, children)
        }

        func stackHeight(_ nodes: [MindMapNode]) -> CGFloat {
            guard !nodes.isEmpty else { return 0 }
            return nodes.map(subtreeHeight).reduce(0, +) + metrics.verticalGap * CGFloat(nodes.count - 1)
        }

        // Place `node` dont le bord intérieur est à `x`, centré verticalement dans sa bande [top, top + hauteur].
        func place(_ node: MindMapNode, parent: UUID, x: CGFloat, top: CGFloat, side: Int) {
            let size = metrics.size(for: node)
            let height = subtreeHeight(node)
            let originX = side > 0 ? x : x - size.width
            frames[node.id] = CGRect(x: originX, y: top + (height - size.height) / 2, width: size.width, height: size.height)
            sides[node.id] = side
            edges.append(Edge(from: parent, to: node.id))
            guard !node.isCollapsed else { return }
            let childX = side > 0 ? originX + size.width + metrics.horizontalGap : originX - metrics.horizontalGap
            var childTop = top + (height - stackHeight(node.children)) / 2
            for child in node.children {
                place(child, parent: node.id, x: childX, top: childTop, side: side)
                childTop += subtreeHeight(child) + metrics.verticalGap
            }
        }

        let rootSize = metrics.size(for: root)
        let rootFrame = CGRect(x: -rootSize.width / 2, y: -rootSize.height / 2, width: rootSize.width, height: rootSize.height)
        frames[root.id] = rootFrame
        sides[root.id] = 0

        for (nodes, side) in [(right, 1), (left, -1)] {
            var top = -stackHeight(nodes) / 2
            let x = side > 0 ? rootFrame.maxX + metrics.horizontalGap : rootFrame.minX - metrics.horizontalGap
            for node in nodes {
                place(node, parent: root.id, x: x, top: top, side: side)
                top += subtreeHeight(node) + metrics.verticalGap
            }
        }

        // On translate tout pour que le coin haut-gauche soit à (padding, padding).
        let union = frames.values.reduce(rootFrame) { $0.union($1) }
        let dx = metrics.padding - union.minX
        let dy = metrics.padding - union.minY
        self.frames = frames.mapValues { $0.offsetBy(dx: dx, dy: dy) }
        self.edges = edges
        self.sides = sides
        self.bounds = CGRect(x: 0, y: 0, width: union.width + metrics.padding * 2, height: union.height + metrics.padding * 2)
    }
}
