import AppKit
import NotitiaKit
import SwiftUI

/// Pont SwiftUI ↔ `BlockTextView` pour un bloc de texte.
struct RichTextView: NSViewRepresentable {
    let block: Block
    let model: NoteEditorModel
    let placeholder: String
    var showsPlaceholderWhenInactive = false

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeNSView(context: Context) -> BlockTextView {
        let view = BlockTextView.make()
        view.delegate = context.coordinator
        view.isEditable = !model.isReadOnly
        configure(view, coordinator: context.coordinator, forceRender: true)
        return view
    }

    func updateNSView(_ view: BlockTextView, context: Context) {
        context.coordinator.parent = self
        configure(view, coordinator: context.coordinator, forceRender: false)
    }

    /// Taille pour une proposition donnée. Les réponses aux sondages (largeur absente, nulle
    /// ou infinie) ne doivent jamais dépendre du cadre actuel de la vue : sinon les contraintes
    /// de taille de la fenêtre dépendent d'elles-mêmes et la mise en page boucle.
    func sizeThatFits(_ proposal: ProposedViewSize, nsView view: BlockTextView, context: Context) -> CGSize? {
        let idealWidth: CGFloat = 400
        switch proposal.width {
        case nil:
            return CGSize(width: idealWidth, height: view.fittingHeight(forWidth: idealWidth))
        case let width? where width <= 1:
            return CGSize(width: 0, height: RichTextConverter.lineHeight(for: block.kind))
        case let width? where !width.isFinite:
            return CGSize(width: idealWidth, height: view.fittingHeight(forWidth: idealWidth))
        case let width?:
            return CGSize(width: width, height: view.fittingHeight(forWidth: width))
        }
    }

    private func configure(_ view: BlockTextView, coordinator: Coordinator, forceRender: Bool) {
        let appearance = RichTextConverter.BlockAppearance(kind: block.kind, isChecked: block.isChecked)
        let appearanceChanged = view.blockAppearance != appearance
        view.blockAppearance = appearance
        view.placeholder = placeholder
        view.showsPlaceholderWhenInactive = showsPlaceholderWhenInactive
        view.isAutomaticQuoteSubstitutionEnabled = block.kind != .code
        view.isAutomaticTextReplacementEnabled = block.kind != .code
        view.isContinuousSpellCheckingEnabled = block.kind != .code
        let blockID = block.id
        view.onFocus = { [weak model] in model?.didFocus(blockID) }
        view.onBlockDrop = { [weak model] dragged in model?.move(dragged, before: blockID) }
        let isCode = block.kind == .code
        view.onMultilinePaste = { [weak model] text, range in
            guard let model, !isCode else { return false }
            model.paste(text, in: blockID, selection: range.location..<range.upperBound)
            return true
        }
        view.onBlocksPaste = { [weak model] copied, range in
            guard let model, !isCode else { return false }
            model.pasteBlocks(copied, in: blockID, selection: range.location..<range.upperBound)
            return true
        }
        view.onSelectAllBlocks = { [weak model] in model?.selectAllBlocks() }

        // Re-rendu uniquement si le texte a changé hors de la saisie (mise en forme,
        // fusion de blocs, raccourci Markdown…) : sinon le curseur sauterait à chaque frappe.
        if forceRender || appearanceChanged || block.text != coordinator.lastText {
            coordinator.render(block.text, in: view)
        }

        if let request = model.focusRequest, request.blockID == block.id, request.token != coordinator.lastFocusToken {
            coordinator.lastFocusToken = request.token
            DispatchQueue.main.async {
                guard let window = view.window else { return }
                window.makeFirstResponder(view)
                let length = view.textStorage?.length ?? 0
                let offset = request.offset.map { min(max(0, $0), length) } ?? length
                view.setSelectedRange(NSRange(location: offset, length: 0))
                view.scrollRangeToVisible(view.selectedRange())
            }
        }
    }

    // MARK: - Coordinator

    final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: RichTextView
        var lastText: RichText
        var lastFocusToken: UUID?
        private var isRendering = false

        init(parent: RichTextView) {
            self.parent = parent
            self.lastText = parent.block.text
        }

        private var model: NoteEditorModel { parent.model }
        private var blockID: UUID { parent.block.id }

        func render(_ text: RichText, in view: BlockTextView) {
            isRendering = true
            defer { isRendering = false }
            let selection = view.selectedRange()
            view.textStorage?.setAttributedString(RichTextConverter.render(text, appearance: view.blockAppearance))
            let length = view.textStorage?.length ?? 0
            view.setSelectedRange(NSRange(location: min(selection.location, length), length: 0))
            let typingStyle = text.style(at: min(selection.location, length))
            view.typingAttributes = RichTextConverter.attributes(for: typingStyle, appearance: view.blockAppearance)
            lastText = text
            view.invalidateHeightIfNeeded()
        }

        func textDidChange(_ notification: Notification) {
            guard !isRendering, let view = notification.object as? BlockTextView, let storage = view.textStorage else { return }
            let text = RichTextConverter.parse(storage)
            lastText = text
            model.updateText(blockID, text: text, caret: view.selectedRange().location)
        }

        func textViewDidChangeSelection(_ notification: Notification) {
            guard !isRendering, let view = notification.object as? BlockTextView, view.isFocused else { return }
            let range = view.selectedRange()
            model.selectionChanged(blockID, range: range.location..<range.upperBound)
        }

        /// Les horodatages des citations (`notitia://recording/…`) rejouent l'audio au lieu d'ouvrir une URL.
        func textView(_ textView: NSTextView, clickedOnLink link: Any, at charIndex: Int) -> Bool {
            let url = (link as? URL) ?? (link as? String).flatMap(URL.init(string:))
            guard let url, let transcriptLink = TranscriptLink(url: url) else { return false }
            model.open(transcriptLink)
            return true
        }

        func textView(_ textView: NSTextView, doCommandBy selector: Selector) -> Bool {
            guard let view = textView as? BlockTextView else { return false }
            let range = view.selectedRange()
            let length = view.textStorage?.length ?? 0
            let kind = parent.block.kind

            // Menu « / » ouvert : il capture les flèches, Entrée et Échap.
            if model.slashMenu?.blockID == blockID {
                switch selector {
                case #selector(NSResponder.moveUp(_:)): model.moveSlashSelection(by: -1); return true
                case #selector(NSResponder.moveDown(_:)): model.moveSlashSelection(by: 1); return true
                case #selector(NSResponder.insertNewline(_:)), #selector(NSResponder.insertTab(_:)):
                    model.commitSlashMenu(); return true
                case #selector(NSResponder.cancelOperation(_:)): model.closeSlashMenu(); return true
                default: break
                }
            }

            switch selector {
            case #selector(NSResponder.insertNewline(_:)):
                let shift = NSApp.currentEvent?.modifierFlags.contains(.shift) ?? false
                if shift || kind == .code { return false } // saut de ligne dans le bloc
                model.split(blockID, at: range.location)
                return true

            case #selector(NSResponder.deleteBackward(_:)):
                guard range.location == 0, range.length == 0 else { return false }
                model.backspaceAtStart(blockID)
                return true

            case #selector(NSResponder.deleteForward(_:)):
                guard range.location == length, range.length == 0 else { return false }
                model.deleteForwardAtEnd(blockID)
                return true

            case #selector(NSResponder.insertTab(_:)):
                if kind == .code { return false }
                model.indent(blockID)
                return true

            case #selector(NSResponder.insertBacktab(_:)):
                model.outdent(blockID)
                return true

            case #selector(NSResponder.cancelOperation(_:)):
                // Échap : sélectionne le bloc entier (puis ⇧↑ / ⇧↓ pour en ajouter).
                model.selectBlock(blockID)
                return true

            case #selector(NSResponder.moveUpAndModifySelection(_:)):
                guard view.isCaretOnFirstLine, range.location == 0 else { return false }
                model.beginBlockSelection(from: blockID, by: -1)
                return true

            case #selector(NSResponder.moveDownAndModifySelection(_:)):
                guard view.isCaretOnLastLine, range.upperBound == length else { return false }
                model.beginBlockSelection(from: blockID, by: 1)
                return true

            case #selector(NSResponder.moveUp(_:)):
                guard view.isCaretOnFirstLine else { return false }
                return model.focusPrevious(from: blockID)

            case #selector(NSResponder.moveDown(_:)):
                guard view.isCaretOnLastLine else { return false }
                return model.focusNext(from: blockID, createIfNeeded: kind == .code)

            case #selector(NSResponder.moveLeft(_:)):
                guard range.location == 0, range.length == 0 else { return false }
                return model.focusPrevious(from: blockID)

            case #selector(NSResponder.moveRight(_:)):
                guard range.location == length, range.length == 0 else { return false }
                return model.focusNext(from: blockID, atStart: true)

            default:
                return false
            }
        }
    }
}
