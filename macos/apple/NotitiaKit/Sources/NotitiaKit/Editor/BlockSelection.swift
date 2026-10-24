import Foundation

/// Sélection de plusieurs blocs consécutifs : de `anchor` (où elle a commencé) à `head` (qui bouge avec ⇧↑ / ⇧↓).
public struct BlockSelection: Equatable, Sendable {
    public var anchor: UUID
    public var head: UUID

    public init(anchor: UUID, head: UUID? = nil) {
        self.anchor = anchor
        self.head = head ?? anchor
    }

    /// Identifiants sélectionnés, dans l'ordre du document.
    public func ids(in blocks: [Block]) -> [UUID] {
        guard let a = blocks.firstIndex(where: { $0.id == anchor }),
              let h = blocks.firstIndex(where: { $0.id == head }) else { return [] }
        return blocks[min(a, h)...max(a, h)].map(\.id)
    }

    /// Déplace la tête d'un bloc ; sans `extending`, la sélection se réduit à ce bloc.
    public func moved(by delta: Int, extending: Bool, in blocks: [Block]) -> BlockSelection {
        guard let h = blocks.firstIndex(where: { $0.id == head }) else { return self }
        let target = blocks[min(max(h + delta, 0), blocks.count - 1)].id
        return extending ? BlockSelection(anchor: anchor, head: target) : BlockSelection(anchor: target)
    }
}

/// Opérations sur plusieurs blocs à la fois (sélection de blocs).
extension BlockOperations {
    /// Supprime les blocs ; les transcriptions sont gardées (leur suppression efface un enregistrement et se
    /// confirme à part). Le curseur va au bloc de texte qui précédait la sélection.
    public static func delete(_ blocks: [Block], ids: Set<UUID>) -> EditResult {
        guard let first = blocks.firstIndex(where: { ids.contains($0.id) }) else { return EditResult(blocks: blocks, caret: nil) }
        var remaining = blocks.filter { !ids.contains($0.id) || $0.kind.requiresConfirmedDeletion }
        if remaining.isEmpty { remaining = [Block()] }
        let kept = Set(remaining.map(\.id))
        let before = blocks[..<first].last { $0.kind.hasText && kept.contains($0.id) }
        let focus = before ?? remaining.first { $0.kind.hasText }
        return EditResult(blocks: remaining, caret: focus.map { Caret(blockID: $0.id, offset: $0.text.length) })
    }

    /// Indente chaque bloc d'un niveau, sans jamais dépasser d'un niveau le bloc précédent.
    public static func indent(_ blocks: [Block], ids: Set<UUID>) -> [Block] {
        var blocks = blocks
        for index in blocks.indices where ids.contains(blocks[index].id) && index > 0 {
            blocks[index].indent = min(blocks[index].indent + 1, min(maxIndent, blocks[index - 1].indent + 1))
        }
        return blocks
    }

    public static func outdent(_ blocks: [Block], ids: Set<UUID>) -> [Block] {
        blocks.map { block in
            var block = block
            if ids.contains(block.id) { block.indent = max(0, block.indent - 1) }
            return block
        }
    }

    /// Monte (`-1`) ou descend (`+1`) le groupe de blocs d'une position : le bloc voisin passe de l'autre côté.
    public static func shift(_ blocks: [Block], ids: Set<UUID>, by delta: Int) -> [Block] {
        let positions = blocks.indices.filter { ids.contains(blocks[$0].id) }
        guard let first = positions.first, let last = positions.last, delta != 0 else { return blocks }
        var blocks = blocks
        if delta < 0, first > 0 {
            let neighbor = blocks.remove(at: first - 1)
            blocks.insert(neighbor, at: last)
        } else if delta > 0, last + 1 < blocks.count {
            let neighbor = blocks.remove(at: last + 1)
            blocks.insert(neighbor, at: first)
        }
        return blocks
    }

    /// Duplique les blocs à la suite de la sélection ; renvoie aussi les identifiants des copies.
    public static func duplicate(_ blocks: [Block], ids: Set<UUID>) -> (blocks: [Block], copies: [UUID]) {
        let selected = blocks.filter { ids.contains($0.id) && !$0.kind.requiresConfirmedDeletion }
        guard let last = blocks.lastIndex(where: { ids.contains($0.id) }), !selected.isEmpty else { return (blocks, []) }
        let copies = selected.map { block -> Block in
            var copy = block
            copy.id = UUID()
            return copy
        }
        var blocks = blocks
        blocks.insert(contentsOf: copies, at: last + 1)
        return (blocks, copies.map(\.id))
    }

    /// Change le type des blocs de texte sélectionnés (les autres ne changent pas).
    public static func convert(_ blocks: [Block], ids: Set<UUID>, to kind: BlockKind) -> [Block] {
        blocks.map { block in
            guard ids.contains(block.id), block.kind.hasText else { return block }
            return convert([block], blockID: block.id, to: kind)[0]
        }
    }

    /// Blocs à copier : les transcriptions en sont exclues (elles désignent un enregistrement, qui ne se copie pas).
    public static func copyable(_ blocks: [Block], ids: Set<UUID>) -> [Block] {
        blocks.filter { ids.contains($0.id) && $0.kind != .transcript }
    }

    /// Insère des blocs copiés à la place de `selection` dans le bloc `id` : le bloc est coupé au curseur et les
    /// copies (avec de nouveaux identifiants) se placent entre les deux moitiés. Un paragraphe vide est remplacé.
    public static func insertCopies(_ copied: [Block], into blocks: [Block], blockID id: UUID, selection: Range<Int>) -> EditResult {
        guard let index = blocks.firstIndex(where: { $0.id == id }), !copied.isEmpty else { return EditResult(blocks: blocks, caret: nil) }
        let copies = copied.map { block -> Block in
            var copy = block
            copy.id = UUID()
            return copy
        }
        var blocks = blocks
        let block = blocks[index]
        var replacement: [Block] = []
        if block.kind.hasText {
            let (head, rest) = block.text.split(at: selection.lowerBound)
            let tail = rest.droppingPrefix(selection.count)
            if !(head.isEmpty && block.kind == .paragraph) {
                var kept = block
                kept.text = head
                replacement.append(kept)
            }
            replacement += copies
            if !tail.isEmpty { replacement.append(Block(kind: block.kind, text: tail, indent: block.indent)) }
        } else {
            replacement = [block] + copies
        }
        blocks.replaceSubrange(index...index, with: replacement)
        let last = copies.last { $0.kind.hasText }
        return EditResult(blocks: blocks, caret: last.map { Caret(blockID: $0.id, offset: $0.text.length) })
    }

    /// Insère des blocs copiés après le bloc `id` (sélection de blocs).
    public static func insertCopies(_ copied: [Block], into blocks: [Block], after id: UUID) -> (blocks: [Block], copies: [UUID]) {
        let copies = copied.map { block -> Block in
            var copy = block
            copy.id = UUID()
            return copy
        }
        return (insert(copies, after: id, in: blocks), copies.map(\.id))
    }
}
