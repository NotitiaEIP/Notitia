import AppKit
import NotitiaKit
import SwiftUI

/// Presse-papiers des blocs : un format interne (JSON, styles et images compris) et du Markdown pour les autres apps.
enum BlockPasteboard {
    static let type = NSPasteboard.PasteboardType("com.notitia.blocks")

    static func write(_ blocks: [Block], markdown: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(markdown, forType: .string)
        if !blocks.isEmpty, let data = try? JSONEncoder().encode(blocks) {
            pasteboard.setData(data, forType: type)
        }
    }

    static func readBlocks() -> [Block]? {
        guard let data = NSPasteboard.general.data(forType: type),
              let blocks = try? JSONDecoder().decode([Block].self, from: data), !blocks.isEmpty else { return nil }
        return blocks
    }
}

/// Prend le focus clavier pendant une sélection de blocs (le texte des blocs ne l'a plus) et traduit les touches
/// en opérations sur la sélection.
struct BlockSelectionResponder: NSViewRepresentable {
    let model: NoteEditorModel

    func makeNSView(context: Context) -> ResponderView {
        let view = ResponderView()
        view.model = model
        return view
    }

    func updateNSView(_ view: ResponderView, context: Context) {
        view.model = model
        if let token = model.blockSelectionFocusToken, token != view.lastToken {
            view.lastToken = token
            DispatchQueue.main.async { view.window?.makeFirstResponder(view) }
        }
    }

    final class ResponderView: NSView, NSMenuItemValidation {
        weak var model: NoteEditorModel?
        var lastToken: UUID?

        override var acceptsFirstResponder: Bool { model?.blockSelection != nil }

        override func resignFirstResponder() -> Bool {
            // Clic ailleurs (barre latérale, inspecteur) : la sélection s'efface.
            DispatchQueue.main.async { [weak self] in
                guard let self, self.window?.firstResponder !== self else { return }
                self.model?.clearBlockSelection()
            }
            return true
        }

        override func keyDown(with event: NSEvent) {
            interpretKeyEvents([event])
        }

        override func doCommand(by selector: Selector) {
            guard let model, model.blockSelection != nil else { return super.doCommand(by: selector) }
            switch selector {
            case #selector(moveUp(_:)): model.moveBlockSelection(by: -1, extending: false)
            case #selector(moveDown(_:)): model.moveBlockSelection(by: 1, extending: false)
            case #selector(moveUpAndModifySelection(_:)): model.moveBlockSelection(by: -1, extending: true)
            case #selector(moveDownAndModifySelection(_:)): model.moveBlockSelection(by: 1, extending: true)
            case #selector(deleteBackward(_:)), #selector(deleteForward(_:)): model.deleteSelectedBlocks()
            case #selector(insertTab(_:)): model.indentSelectedBlocks()
            case #selector(insertBacktab(_:)): model.outdentSelectedBlocks()
            case #selector(insertNewline(_:)): model.editBlockSelection()
            case #selector(cancelOperation(_:)): model.editBlockSelection()
            default: NSSound.beep()
            }
        }

        // Commandes du menu Édition, envoyées au premier répondeur.

        @objc func copy(_ sender: Any?) {
            guard let copied = model?.copySelectedBlocks() else { return }
            BlockPasteboard.write(copied.blocks, markdown: copied.markdown)
        }

        @objc func cut(_ sender: Any?) {
            copy(sender)
            model?.deleteSelectedBlocks()
        }

        @objc func paste(_ sender: Any?) {
            if let blocks = BlockPasteboard.readBlocks() {
                model?.pasteBlocksAfterSelection(blocks)
            } else if let text = NSPasteboard.general.string(forType: .string) {
                model?.pasteBlocksAfterSelection(PastedText.blocks(from: text))
            }
        }

        @objc func delete(_ sender: Any?) { model?.deleteSelectedBlocks() }

        override func selectAll(_ sender: Any?) { model?.selectAllBlocks() }

        func validateMenuItem(_ item: NSMenuItem) -> Bool {
            guard let model, model.blockSelection != nil else { return false }
            switch item.action {
            case #selector(copy(_:)), #selector(cut(_:)), #selector(delete(_:)), #selector(selectAll(_:)): return true
            case #selector(paste(_:)):
                return NSPasteboard.general.availableType(from: [BlockPasteboard.type, .string]) != nil
            default: return true
            }
        }
    }
}
