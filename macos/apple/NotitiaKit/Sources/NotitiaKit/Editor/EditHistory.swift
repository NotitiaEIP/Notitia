import Foundation

/// Historique d'annulation de l'éditeur : des **photos** du document (blocs et curseur).
///
/// Une seule pile pour toute la note, pour que ⌘Z défasse aussi bien une frappe qu'une fusion de blocs, une
/// suppression, un déplacement ou une insertion de l'IA, dans l'ordre où elles ont eu lieu. La frappe continue dans
/// un même bloc forme une seule étape, jusqu'à une pause de `typingPause` secondes.
public struct EditHistory: Sendable {
    public struct Snapshot: Equatable, Sendable {
        public var blocks: [Block]
        public var caret: Caret?

        public init(blocks: [Block], caret: Caret?) {
            self.blocks = blocks
            self.caret = caret
        }
    }

    public static let limit = 200
    public static let typingPause: TimeInterval = 1.5

    private var undoStack: [Snapshot] = []
    private var redoStack: [Snapshot] = []
    /// Clé de regroupement de la dernière étape (frappe dans un bloc) et instant de la dernière modification.
    private var lastKey: String?
    private var lastChange: Date?

    public init() {}

    public var canUndo: Bool { !undoStack.isEmpty }
    public var canRedo: Bool { !redoStack.isEmpty }

    /// À appeler **avant** une modification, avec l'état d'avant.
    /// - `key` : modifications à regrouper (la frappe dans un bloc) ; `nil` pour une opération structurelle.
    public mutating func record(_ snapshot: Snapshot, key: String? = nil, at now: Date = .now) {
        defer {
            lastKey = key
            lastChange = now
            redoStack.removeAll()
        }
        if let key, key == lastKey, let lastChange, now.timeIntervalSince(lastChange) < Self.typingPause {
            return // même frappe continue : l'étape existante couvre déjà l'état d'avant
        }
        undoStack.append(snapshot)
        if undoStack.count > Self.limit { undoStack.removeFirst(undoStack.count - Self.limit) }
    }

    /// Termine l'étape de frappe en cours (changement de bloc, déplacement du curseur…).
    public mutating func endTyping() {
        lastKey = nil
    }

    /// Renvoie l'état à restaurer ; `current` devient l'étape à rétablir.
    public mutating func undo(current: Snapshot) -> Snapshot? {
        guard let previous = undoStack.popLast() else { return nil }
        redoStack.append(current)
        lastKey = nil
        return previous
    }

    public mutating func redo(current: Snapshot) -> Snapshot? {
        guard let next = redoStack.popLast() else { return nil }
        undoStack.append(current)
        lastKey = nil
        return next
    }
}
