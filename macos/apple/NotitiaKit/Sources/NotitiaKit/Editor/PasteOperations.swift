import Foundation

/// Collage d'un texte de plusieurs lignes : une ligne par bloc, en reconnaissant la syntaxe Markdown courante.
public enum PastedText {
    /// Découpe `text` en blocs : titres, listes (y compris « 2. », « 10) »), cases à cocher, citations, séparateurs
    /// et blocs de code entre « ``` ». L'indentation vient des tabulations ou des espaces (l'unité est le plus petit
    /// retrait rencontré). Les lignes vides séparent les blocs sans en créer.
    public static func blocks(from text: String) -> [Block] {
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
        let unit = indentUnit(lines)
        var blocks: [Block] = []
        var code: [String]?

        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if var open = code {
                if trimmed.hasPrefix("```") {
                    blocks.append(Block(kind: .code, text: RichText(open.joined(separator: "\n"))))
                    code = nil
                } else {
                    open.append(line)
                    code = open
                }
                continue
            }
            if trimmed.hasPrefix("```") {
                code = []
                continue
            }
            guard !trimmed.isEmpty else { continue }

            var block = parse(trimmed)
            if block.kind != .divider { block.indent = min(leadingWidth(line) / unit, BlockOperations.maxIndent) }
            // Pas plus d'un niveau sous le bloc précédent (même règle que Tab).
            block.indent = min(block.indent, (blocks.last?.indent ?? -1) + 1)
            blocks.append(block)
        }
        if let open = code { blocks.append(Block(kind: .code, text: RichText(open.joined(separator: "\n")))) }
        return blocks
    }

    /// Bloc d'une ligne sans son retrait.
    static func parse(_ line: String) -> Block {
        if let match = MarkdownShortcut.detect(in: line, currentKind: .paragraph), match.kind != .code {
            let text = match.kind.hasText ? String(line.utf16.dropFirst(match.prefixLength)) ?? "" : ""
            return Block(kind: match.kind, text: RichText(text), isChecked: match.isChecked)
        }
        // Liste numérotée quelconque : « 2. », « 10) ».
        let digits = line.prefix { $0.isNumber }
        if !digits.isEmpty {
            let rest = line.dropFirst(digits.count)
            if rest.hasPrefix(". ") || rest.hasPrefix(") ") {
                return Block(kind: .numbered, text: RichText(String(rest.dropFirst(2))))
            }
        }
        return Block(text: RichText(line))
    }

    /// Largeur du retrait : une tabulation vaut quatre espaces.
    static func leadingWidth(_ line: String) -> Int {
        var width = 0
        for character in line {
            if character == " " { width += 1 } else if character == "\t" { width += 4 } else { break }
        }
        return width
    }

    static func indentUnit(_ lines: [String]) -> Int {
        let widths = lines.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.map(leadingWidth).filter { $0 > 0 }
        return max(widths.min() ?? 4, 1)
    }
}

extension BlockOperations {
    /// Colle `text` à la place de `selection` dans le bloc `id`.
    ///
    /// Sur une seule ligne (ou dans un bloc de code), le texte est inséré dans le bloc. Sinon : la première ligne
    /// complète le bloc, les suivantes deviennent des blocs, et la fin du bloc d'origine suit la dernière ligne.
    /// Le curseur se place à la fin du texte collé.
    public static func paste(_ text: String, into blocks: [Block], blockID id: UUID, selection: Range<Int>) -> EditResult {
        guard let index = blocks.firstIndex(where: { $0.id == id }), blocks[index].kind.hasText else {
            return EditResult(blocks: blocks, caret: nil)
        }
        var blocks = blocks
        let block = blocks[index]
        let (head, rest) = block.text.split(at: selection.lowerBound)
        let tail = rest.droppingPrefix(selection.count)
        let style = block.text.style(at: selection.lowerBound)

        var pasted = PastedText.blocks(from: text)
        let isSingleLine = !text.contains(where: \.isNewline)
        if isSingleLine || block.kind == .code || pasted.isEmpty {
            let inserted = RichText(isSingleLine || block.kind == .code ? text : pasted.map(\.text.plainText).joined(separator: " "), style: style)
            blocks[index].text = head.appending(inserted).appending(tail)
            return EditResult(blocks: blocks, caret: Caret(blockID: id, offset: head.length + inserted.length))
        }

        // Retraits relatifs au bloc d'origine.
        for position in pasted.indices where pasted[position].kind != .divider {
            pasted[position].indent = min(pasted[position].indent + block.indent, maxIndent)
        }

        // Première ligne : dans le bloc courant. Un paragraphe vide prend le type de la ligne collée.
        let first = pasted.removeFirst()
        var current = block
        if block.kind == .paragraph, block.text.isEmpty {
            current.kind = first.kind
            current.isChecked = first.isChecked
            current.indent = first.indent
            current.text = RichText(first.text.plainText, style: style)
        } else if first.kind.hasText {
            current.text = head.appending(RichText(first.text.plainText, style: style))
        } else {
            current.text = head
            pasted.insert(first, at: 0)
        }

        var newBlocks = [current]
        newBlocks += pasted
        // La fin du bloc d'origine suit la dernière ligne collée (ou forme un bloc si la dernière n'a pas de texte).
        var caret = Caret(blockID: current.id, offset: current.text.length)
        if let last = newBlocks.indices.last {
            if newBlocks[last].kind.hasText {
                caret = Caret(blockID: newBlocks[last].id, offset: newBlocks[last].text.length)
                newBlocks[last].text = newBlocks[last].text.appending(tail)
            } else if !tail.isEmpty {
                let after = Block(kind: block.kind, text: tail, indent: block.indent)
                newBlocks.append(after)
                caret = Caret(blockID: after.id, offset: 0)
            }
        }
        blocks.replaceSubrange(index...index, with: newBlocks)
        return EditResult(blocks: blocks, caret: caret)
    }
}
