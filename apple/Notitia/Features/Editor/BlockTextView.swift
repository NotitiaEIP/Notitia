import AppKit
import NotitiaKit

/// Vue de texte d'un bloc (TextKit 1, `NSTextView`).
///
/// On utilise AppKit plutôt que `TextEditor` : l'éditeur par blocs doit intercepter
/// Entrée, Retour arrière, Tab et les flèches (`doCommandBy`), ce que seule `NSTextView`
/// permet de manière fiable. Voir docs/ARCHITECTURE.md.
final class BlockTextView: NSTextView {
    var blockAppearance = RichTextConverter.BlockAppearance(kind: .paragraph) {
        didSet { needsDisplay = true }
    }
    var placeholder = ""
    var showsPlaceholderWhenInactive = false
    /// Appelé quand la vue prend le focus.
    var onFocus: (() -> Void)?
    /// Appelé quand un bloc est déposé sur ce bloc (réorganisation).
    var onBlockDrop: ((UUID) -> Void)?

    static func make() -> BlockTextView {
        let storage = NSTextStorage()
        let layoutManager = NSLayoutManager()
        storage.addLayoutManager(layoutManager)
        let container = NSTextContainer(size: NSSize(width: 400, height: CGFloat.greatestFiniteMagnitude))
        container.widthTracksTextView = true
        container.heightTracksTextView = false
        container.lineFragmentPadding = 0
        layoutManager.addTextContainer(container)

        let view = BlockTextView(frame: NSRect(x: 0, y: 0, width: 400, height: 20), textContainer: container)
        view.isRichText = true
        view.importsGraphics = false
        view.allowsUndo = true
        view.drawsBackground = false
        view.textContainerInset = .zero
        view.isVerticallyResizable = false
        view.isHorizontallyResizable = false
        view.autoresizingMask = [.width]
        view.usesFindBar = true
        view.isIncrementalSearchingEnabled = true
        view.isContinuousSpellCheckingEnabled = true // correcteur local
        view.isGrammarCheckingEnabled = false
        view.isAutomaticLinkDetectionEnabled = false
        view.isAutomaticDashSubstitutionEnabled = false
        view.smartInsertDeleteEnabled = true
        view.linkTextAttributes = [
            .foregroundColor: NSColor.linkColor,
            .underlineStyle: NSUnderlineStyle.single.rawValue,
            .cursor: NSCursor.pointingHand,
        ]
        // Les Outils d'écriture d'Apple peuvent envoyer le texte vers le cloud privé d'Apple :
        // désactivés pour garantir que rien ne quitte l'appareil (docs/PRIVACY.md).
        view.writingToolsBehavior = .none
        view.unregisterDraggedTypes()
        view.registerForDraggedTypes([.string])
        return view
    }

    var isFocused: Bool { window?.firstResponder === self }

    // MARK: Focus et placeholder

    override func becomeFirstResponder() -> Bool {
        let accepted = super.becomeFirstResponder()
        if accepted {
            needsDisplay = true
            onFocus?()
        }
        return accepted
    }

    override func resignFirstResponder() -> Bool {
        let accepted = super.resignFirstResponder()
        if accepted { needsDisplay = true }
        return accepted
    }

    override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)
        guard textStorage?.length == 0, !placeholder.isEmpty, isFocused || showsPlaceholderWhenInactive else { return }
        var attributes = RichTextConverter.attributes(for: .plain, appearance: blockAppearance)
        attributes[.foregroundColor] = NSColor.placeholderTextColor
        attributes.removeValue(forKey: .notitiaStyle)
        (placeholder as NSString).draw(at: NSPoint(x: textContainerInset.width, y: textContainerInset.height), withAttributes: attributes)
    }

    // MARK: Taille

    /// Mise en page de mesure, branchée sur le même texte mais indépendante de la vue :
    /// mesurer ne modifie jamais la vue affichée (ce qui provoquerait une boucle de mise en page).
    private lazy var measuringContainer: NSTextContainer = {
        let container = NSTextContainer(size: NSSize(width: 400, height: CGFloat.greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        return container
    }()

    private lazy var measuringLayoutManager: NSLayoutManager = {
        let manager = NSLayoutManager()
        manager.addTextContainer(measuringContainer)
        textStorage?.addLayoutManager(manager)
        return manager
    }()

    /// Hauteur nécessaire pour une largeur donnée.
    func fittingHeight(forWidth width: CGFloat) -> CGFloat {
        let minimum = RichTextConverter.lineHeight(for: blockAppearance.kind)
        guard width.isFinite, width > 1 else { return minimum }
        let manager = measuringLayoutManager
        if abs(measuringContainer.containerSize.width - width) > 0.5 {
            measuringContainer.containerSize = NSSize(width: width, height: CGFloat.greatestFiniteMagnitude)
        }
        manager.ensureLayout(for: measuringContainer)
        let used = manager.usedRect(for: measuringContainer).height
        return max(ceil(used), minimum)
    }

    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        lastReportedHeight = newSize.height
    }

    /// Pas de taille intrinsèque : SwiftUI mesure via `sizeThatFits`. Une taille intrinsèque
    /// dépendant de la largeur créerait une boucle de mise en page avec Auto Layout.
    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: NSView.noIntrinsicMetric)
    }

    private var lastReportedHeight: CGFloat = 0

    /// Demande à SwiftUI de remesurer, seulement si la hauteur a vraiment changé.
    func invalidateHeightIfNeeded() {
        guard bounds.width > 1 else { return }
        let height = fittingHeight(forWidth: bounds.width)
        if abs(height - lastReportedHeight) > 0.5 {
            lastReportedHeight = height
            invalidateIntrinsicContentSize()
        }
    }

    override func didChangeText() {
        super.didChangeText()
        invalidateHeightIfNeeded()
    }

    // MARK: Collage : texte brut uniquement (le style du point d'insertion s'applique)

    override var readablePasteboardTypes: [NSPasteboard.PasteboardType] { [.string] }

    // MARK: Réorganisation des blocs par glisser-déposer

    private func draggedBlockID(_ info: any NSDraggingInfo) -> UUID? {
        guard let string = info.draggingPasteboard.string(forType: .string),
              case .block(let uid) = DragPayload(string) else { return nil }
        return uid
    }

    override func draggingEntered(_ sender: any NSDraggingInfo) -> NSDragOperation {
        draggedBlockID(sender) != nil ? .move : super.draggingEntered(sender)
    }

    override func draggingUpdated(_ sender: any NSDraggingInfo) -> NSDragOperation {
        draggedBlockID(sender) != nil ? .move : super.draggingUpdated(sender)
    }

    override func performDragOperation(_ sender: any NSDraggingInfo) -> Bool {
        if let uid = draggedBlockID(sender) {
            onBlockDrop?(uid)
            return true
        }
        return super.performDragOperation(sender)
    }

    // MARK: Position du curseur

    private func lineRect(atCharacter location: Int) -> NSRect {
        guard let layoutManager, let storage = textStorage else { return .zero }
        if location >= storage.length {
            if !layoutManager.extraLineFragmentRect.isEmpty { return layoutManager.extraLineFragmentRect }
            guard layoutManager.numberOfGlyphs > 0 else { return .zero }
            return layoutManager.lineFragmentRect(forGlyphAt: layoutManager.numberOfGlyphs - 1, effectiveRange: nil)
        }
        let glyph = layoutManager.glyphIndexForCharacter(at: location)
        return layoutManager.lineFragmentRect(forGlyphAt: glyph, effectiveRange: nil)
    }

    var isCaretOnFirstLine: Bool {
        guard let storage = textStorage, storage.length > 0 else { return true }
        return lineRect(atCharacter: selectedRange().location).minY <= lineRect(atCharacter: 0).minY + 1
    }

    var isCaretOnLastLine: Bool {
        guard let storage = textStorage, storage.length > 0 else { return true }
        let caret = lineRect(atCharacter: selectedRange().upperBound)
        return caret.maxY >= lineRect(atCharacter: storage.length).maxY - 1
    }

    // MARK: Mise en forme (actions envoyées par les menus et la barre d'outils)

    @objc func notitiaToggleBold(_ sender: Any?) { toggle(.bold) }
    @objc func notitiaToggleItalic(_ sender: Any?) { toggle(.italic) }
    @objc func notitiaToggleUnderline(_ sender: Any?) { toggle(.underline) }
    @objc func notitiaToggleStrikethrough(_ sender: Any?) { toggle(.strikethrough) }
    @objc func notitiaToggleCode(_ sender: Any?) { toggle(.code) }

    @objc func notitiaSetColor(_ sender: Any?) {
        let color = (sender as? ColorCommandSender)?.color
        applyStyle { $0.color = color }
    }

    @objc func notitiaSetHighlight(_ sender: Any?) {
        let color = (sender as? ColorCommandSender)?.color
        applyStyle { $0.highlight = color }
    }

    @objc func notitiaEditLink(_ sender: Any?) {
        let range = selectedRange()
        guard range.length > 0, let window else { NSSound.beep(); return }
        let current = RichTextConverter.parse(attributedString()).style(at: range.location + 1).link
        let alert = NSAlert()
        alert.messageText = String(localized: "Lien")
        alert.informativeText = String(localized: "Saisissez l'adresse du lien. Laissez vide pour le retirer.")
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 300, height: 24))
        field.placeholderString = "https://"
        field.stringValue = current ?? ""
        alert.accessoryView = field
        alert.addButton(withTitle: String(localized: "Valider"))
        alert.addButton(withTitle: String(localized: "Annuler"))
        alert.window.initialFirstResponder = field
        alert.beginSheetModal(for: window) { [weak self] response in
            guard response == .alertFirstButtonReturn, let self else { return }
            let value = field.stringValue.trimmingCharacters(in: .whitespaces)
            self.setSelectedRange(range)
            self.applyStyle { $0.link = value.isEmpty ? nil : value }
        }
    }

    func toggle(_ attribute: ToggleableTextAttribute) {
        let range = selectedRange()
        let current = RichTextConverter.parse(attributedString())
        let newValue: Bool
        if range.length > 0 {
            newValue = !current.allHave(attribute, in: range.location..<range.upperBound)
        } else {
            let typing = RichTextConverter.style(in: typingAttributes)
            newValue = !typing[keyPath: attribute.keyPath]
        }
        applyStyle { $0[keyPath: attribute.keyPath] = newValue }
    }

    /// Applique une transformation de style à la sélection, ou au style de saisie si elle est vide.
    func applyStyle(_ transform: (inout TextStyle) -> Void) {
        let range = selectedRange()
        guard range.length > 0 else {
            var style = RichTextConverter.style(in: typingAttributes)
            transform(&style)
            typingAttributes = RichTextConverter.attributes(for: style, appearance: blockAppearance)
            return
        }
        let current = RichTextConverter.parse(attributedString())
        let updated = current.applying(in: range.location..<range.upperBound, transform)
        let replacement = RichTextConverter.render(updated.substring(range.location..<range.upperBound), appearance: blockAppearance)
        guard shouldChangeText(in: range, replacementString: nil), let storage = textStorage else { return }
        storage.replaceCharacters(in: range, with: replacement)
        didChangeText()
        setSelectedRange(range)
    }
}
