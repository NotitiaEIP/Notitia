import Foundation

/// Position du curseur après une opération : bloc et décalage UTF-16.
public struct Caret: Hashable, Sendable {
    public var blockID: UUID
    public var offset: Int

    public init(blockID: UUID, offset: Int) {
        self.blockID = blockID
        self.offset = offset
    }
}

/// Résultat d'une opération structurelle.
public struct EditResult: Sendable {
    public var blocks: [Block]
    public var caret: Caret?

    public init(blocks: [Block], caret: Caret?) {
        self.blocks = blocks
        self.caret = caret
    }
}

/// Opérations structurelles de l'éditeur par blocs.
///
/// Ce sont des fonctions pures sur `[Block]` : l'interface se contente de les appeler
/// et d'appliquer le résultat. Elles sont entièrement couvertes par les tests unitaires.
public enum BlockOperations {
    public static let maxIndent = 6

    // MARK: Entrée

    /// Comportement de la touche Entrée à `offset` dans le bloc `id`.
    ///
    /// - Liste vide : on sort de la liste (le bloc redevient du texte, ou est désindenté).
    /// - Sinon le bloc est coupé ; la suite part dans un nouveau bloc du type de continuation.
    public static func split(_ blocks: [Block], blockID id: UUID, at offset: Int) -> EditResult {
        guard let index = blocks.firstIndex(where: { $0.id == id }) else {
            return EditResult(blocks: blocks, caret: nil)
        }
        var blocks = blocks
        let block = blocks[index]

        if block.kind.isList || block.kind == .quote || block.kind == .callout, block.text.isEmpty {
            if block.indent > 0 {
                blocks[index].indent -= 1
            } else {
                blocks[index].kind = .paragraph
            }
            return EditResult(blocks: blocks, caret: Caret(blockID: id, offset: 0))
        }

        let (head, tail) = block.text.split(at: offset)
        // Entrée au tout début d'un bloc non vide : on insère un bloc vide au-dessus,
        // le bloc courant garde son identité (et son type, par exemple un titre).
        if offset == 0, !block.text.isEmpty {
            let above = Block(kind: block.kind.isList ? block.kind : .paragraph, indent: block.indent)
            blocks.insert(above, at: index)
            return EditResult(blocks: blocks, caret: Caret(blockID: id, offset: 0))
        }

        blocks[index].text = head
        let next = Block(kind: block.kind.continuation, text: tail, indent: block.indent)
        blocks.insert(next, at: index + 1)
        return EditResult(blocks: blocks, caret: Caret(blockID: next.id, offset: 0))
    }

    // MARK: Retour arrière

    /// Retour arrière au début du bloc `id`.
    ///
    /// Dans l'ordre : un bloc stylé redevient du texte, un bloc indenté est désindenté,
    /// sinon le bloc est fusionné avec le bloc de texte précédent.
    public static func backspaceAtStart(_ blocks: [Block], blockID id: UUID) -> EditResult {
        guard let index = blocks.firstIndex(where: { $0.id == id }) else {
            return EditResult(blocks: blocks, caret: nil)
        }
        var blocks = blocks
        let block = blocks[index]

        if block.kind != .paragraph, block.kind.hasText {
            blocks[index].kind = .paragraph
            blocks[index].isChecked = false
            return EditResult(blocks: blocks, caret: Caret(blockID: id, offset: 0))
        }
        if block.indent > 0 {
            blocks[index].indent -= 1
            return EditResult(blocks: blocks, caret: Caret(blockID: id, offset: 0))
        }
        guard index > 0 else {
            return EditResult(blocks: blocks, caret: Caret(blockID: id, offset: 0))
        }

        let previous = blocks[index - 1]
        if previous.kind.requiresConfirmedDeletion {
            // Une transcription au-dessus : un retour arrière de trop ne doit pas effacer une réunion.
            return EditResult(blocks: blocks, caret: Caret(blockID: id, offset: 0))
        }
        if !previous.kind.hasText {
            // Bloc non textuel au-dessus (séparateur, image…) : on le supprime.
            blocks.remove(at: index - 1)
            return EditResult(blocks: blocks, caret: Caret(blockID: id, offset: 0))
        }

        let joinOffset = previous.text.length
        blocks[index - 1].text = previous.text.appending(block.text)
        blocks.remove(at: index)
        return EditResult(blocks: blocks, caret: Caret(blockID: previous.id, offset: joinOffset))
    }

    /// Suppression vers l'avant à la fin du bloc : absorbe le bloc suivant.
    public static func deleteForwardAtEnd(_ blocks: [Block], blockID id: UUID) -> EditResult {
        guard let index = blocks.firstIndex(where: { $0.id == id }), index + 1 < blocks.count else {
            return EditResult(blocks: blocks, caret: nil)
        }
        var blocks = blocks
        let offset = blocks[index].text.length
        let next = blocks[index + 1]
        if next.kind.requiresConfirmedDeletion {
            return EditResult(blocks: blocks, caret: Caret(blockID: id, offset: offset))
        }
        if next.kind.hasText {
            blocks[index].text = blocks[index].text.appending(next.text)
        }
        blocks.remove(at: index + 1)
        return EditResult(blocks: blocks, caret: Caret(blockID: id, offset: offset))
    }

    // MARK: Indentation

    public static func indent(_ blocks: [Block], blockID id: UUID) -> [Block] {
        guard let index = blocks.firstIndex(where: { $0.id == id }), index > 0 else { return blocks }
        var blocks = blocks
        // On ne peut pas être plus d'un niveau sous le bloc précédent.
        let limit = min(maxIndent, blocks[index - 1].indent + 1)
        blocks[index].indent = min(blocks[index].indent + 1, limit)
        return blocks
    }

    public static func outdent(_ blocks: [Block], blockID id: UUID) -> [Block] {
        guard let index = blocks.firstIndex(where: { $0.id == id }) else { return blocks }
        var blocks = blocks
        blocks[index].indent = max(0, blocks[index].indent - 1)
        return blocks
    }

    // MARK: Conversion, insertion, suppression, déplacement

    public static func convert(_ blocks: [Block], blockID id: UUID, to kind: BlockKind) -> [Block] {
        guard let index = blocks.firstIndex(where: { $0.id == id }), !blocks[index].kind.requiresConfirmedDeletion else { return blocks }
        var blocks = blocks
        blocks[index].kind = kind
        if kind != .todo { blocks[index].isChecked = false }
        if !kind.hasText { blocks[index].text = RichText() }
        return blocks
    }

    /// Insère `block` après `id` (ou à la fin si `id` est absent).
    public static func insert(_ block: Block, after id: UUID?, in blocks: [Block]) -> [Block] {
        var blocks = blocks
        if let id, let index = blocks.firstIndex(where: { $0.id == id }) {
            blocks.insert(block, at: index + 1)
        } else {
            blocks.append(block)
        }
        return blocks
    }

    /// Insère plusieurs blocs ; si le bloc d'ancrage est un paragraphe vide, il est remplacé.
    public static func insert(_ newBlocks: [Block], after id: UUID?, in blocks: [Block]) -> [Block] {
        guard !newBlocks.isEmpty else { return blocks }
        var blocks = blocks
        if let id, let index = blocks.firstIndex(where: { $0.id == id }) {
            if blocks[index].kind == .paragraph, blocks[index].text.isEmpty {
                blocks.replaceSubrange(index...index, with: newBlocks)
            } else {
                blocks.insert(contentsOf: newBlocks, at: index + 1)
            }
        } else if blocks.count == 1, blocks[0].kind == .paragraph, blocks[0].text.isEmpty {
            blocks = newBlocks
        } else {
            blocks.append(contentsOf: newBlocks)
        }
        return blocks
    }

    /// Supprime un bloc ; une note garde toujours au moins un bloc.
    public static func delete(_ blocks: [Block], blockID id: UUID) -> EditResult {
        guard let index = blocks.firstIndex(where: { $0.id == id }) else {
            return EditResult(blocks: blocks, caret: nil)
        }
        var blocks = blocks
        blocks.remove(at: index)
        if blocks.isEmpty {
            let empty = Block()
            return EditResult(blocks: [empty], caret: Caret(blockID: empty.id, offset: 0))
        }
        let focusIndex = max(0, index - 1)
        let focus = blocks[focusIndex]
        return EditResult(blocks: blocks, caret: Caret(blockID: focus.id, offset: focus.text.length))
    }

    /// Déplace le bloc `id` juste avant `targetID` (ou à la fin si `targetID` est `nil`).
    public static func move(_ blocks: [Block], blockID id: UUID, before targetID: UUID?) -> [Block] {
        guard id != targetID, let from = blocks.firstIndex(where: { $0.id == id }) else { return blocks }
        var blocks = blocks
        let block = blocks.remove(at: from)
        if let targetID, let to = blocks.firstIndex(where: { $0.id == targetID }) {
            blocks.insert(block, at: to)
        } else {
            blocks.append(block)
        }
        return blocks
    }

    /// Déplace d'une position vers le haut (`-1`) ou le bas (`+1`).
    public static func shift(_ blocks: [Block], blockID id: UUID, by delta: Int) -> [Block] {
        guard let from = blocks.firstIndex(where: { $0.id == id }) else { return blocks }
        let to = from + delta
        guard blocks.indices.contains(to) else { return blocks }
        var blocks = blocks
        blocks.swapAt(from, to)
        return blocks
    }

    /// Duplique un bloc (avec un nouvel identifiant).
    public static func duplicate(_ blocks: [Block], blockID id: UUID) -> [Block] {
        guard let index = blocks.firstIndex(where: { $0.id == id }) else { return blocks }
        var copy = blocks[index]
        copy.id = UUID()
        var blocks = blocks
        blocks.insert(copy, at: index + 1)
        return blocks
    }

    // MARK: Listes numérotées

    /// Numéro affiché pour chaque bloc d'une liste numérotée.
    /// La numérotation repart à 1 dès qu'un autre bloc de même niveau (ou moins indenté) l'interrompt.
    public static func listNumbers(_ blocks: [Block]) -> [UUID: Int] {
        var numbers: [UUID: Int] = [:]
        var counters: [Int: Int] = [:] // indentation -> compteur courant
        for block in blocks {
            if block.kind == .numbered {
                counters = counters.filter { $0.key <= block.indent }
                let value = (counters[block.indent] ?? 0) + 1
                counters[block.indent] = value
                numbers[block.id] = value
            } else {
                // Tout autre bloc interrompt les listes de son niveau et des niveaux plus profonds.
                counters = counters.filter { $0.key < block.indent }
            }
        }
        return numbers
    }
}
