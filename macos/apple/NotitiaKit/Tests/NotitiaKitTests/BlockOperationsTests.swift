import Foundation
import Testing
@testable import NotitiaKit

@Suite("Opérations sur les blocs")
struct BlockOperationsTests {
    @Test func entreeCoupeLeParagraphe() {
        let block = Block(text: "Bonjour monde")
        let result = BlockOperations.split([block], blockID: block.id, at: 7)
        #expect(result.blocks.count == 2)
        #expect(result.blocks[0].text.plainText == "Bonjour")
        #expect(result.blocks[1].text.plainText == " monde")
        #expect(result.caret == Caret(blockID: result.blocks[1].id, offset: 0))
    }

    @Test func entreeContinueLaListe() {
        let item = Block(kind: .todo, text: "Acheter du thé", indent: 1, isChecked: true)
        let result = BlockOperations.split([item], blockID: item.id, at: item.text.length)
        #expect(result.blocks[1].kind == .todo)
        #expect(result.blocks[1].indent == 1)
        #expect(result.blocks[1].isChecked == false)
    }

    @Test func entreeApresUnTitreDonneUnParagraphe() {
        let title = Block(kind: .heading1, text: "Titre")
        let result = BlockOperations.split([title], blockID: title.id, at: 5)
        #expect(result.blocks[1].kind == .paragraph)
    }

    @Test func entreeAuDebutInsereAuDessus() {
        let title = Block(kind: .heading1, text: "Titre")
        let result = BlockOperations.split([title], blockID: title.id, at: 0)
        #expect(result.blocks.count == 2)
        #expect(result.blocks[1].id == title.id)
        #expect(result.blocks[1].kind == .heading1)
        #expect(result.blocks[0].kind == .paragraph)
        #expect(result.caret?.blockID == title.id)
    }

    @Test func entreeSurUnElementDeListeVideSortDeLaListe() {
        let empty = Block(kind: .bulleted)
        let result = BlockOperations.split([empty], blockID: empty.id, at: 0)
        #expect(result.blocks.count == 1)
        #expect(result.blocks[0].kind == .paragraph)

        let nested = Block(kind: .bulleted, indent: 2)
        let outdented = BlockOperations.split([nested], blockID: nested.id, at: 0)
        #expect(outdented.blocks[0].kind == .bulleted)
        #expect(outdented.blocks[0].indent == 1)
    }

    @Test func retourArriereConvertitPuisDesindentePuisFusionne() {
        let first = Block(text: "Premier")
        let second = Block(kind: .bulleted, text: "second", indent: 1)

        var result = BlockOperations.backspaceAtStart([first, second], blockID: second.id)
        #expect(result.blocks[1].kind == .paragraph)

        result = BlockOperations.backspaceAtStart(result.blocks, blockID: second.id)
        #expect(result.blocks[1].indent == 0)

        result = BlockOperations.backspaceAtStart(result.blocks, blockID: second.id)
        #expect(result.blocks.count == 1)
        #expect(result.blocks[0].text.plainText == "Premiersecond")
        #expect(result.caret == Caret(blockID: first.id, offset: 7))
    }

    @Test func retourArriereSupprimeUnSeparateurPrecedent() {
        let divider = Block(kind: .divider)
        let text = Block(text: "Texte")
        let result = BlockOperations.backspaceAtStart([divider, text], blockID: text.id)
        #expect(result.blocks.map(\.id) == [text.id])
    }

    @Test func leClavierNeSupprimeJamaisUneTranscription() {
        let transcript = Block(kind: .transcript, recordingID: UUID())
        let above = Block(text: "Avant")
        let below = Block(text: "Après")
        let blocks = [above, transcript, below]

        let backspace = BlockOperations.backspaceAtStart(blocks, blockID: below.id)
        #expect(backspace.blocks == blocks)
        #expect(backspace.caret == Caret(blockID: below.id, offset: 0))

        let deleteForward = BlockOperations.deleteForwardAtEnd(blocks, blockID: above.id)
        #expect(deleteForward.blocks == blocks)
        #expect(deleteForward.caret == Caret(blockID: above.id, offset: 5))

        #expect(BlockOperations.convert(blocks, blockID: transcript.id, to: .paragraph) == blocks)
    }

    @Test func retourArriereSurLePremierBlocNeFaitRien() {
        let only = Block(text: "Seul")
        let result = BlockOperations.backspaceAtStart([only], blockID: only.id)
        #expect(result.blocks == [only])
    }

    @Test func indentationLimiteeAuBlocPrecedent() {
        let a = Block(kind: .bulleted, text: "a")
        let b = Block(kind: .bulleted, text: "b")
        var blocks = BlockOperations.indent([a, b], blockID: b.id)
        #expect(blocks[1].indent == 1)
        blocks = BlockOperations.indent(blocks, blockID: b.id)
        #expect(blocks[1].indent == 1, "pas plus d'un niveau sous le bloc précédent")
        blocks = BlockOperations.indent(blocks, blockID: a.id)
        #expect(blocks[0].indent == 0, "le premier bloc ne s'indente pas")
        blocks = BlockOperations.outdent(blocks, blockID: b.id)
        #expect(blocks[1].indent == 0)
    }

    @Test func suppressionGardeToujoursUnBloc() {
        let only = Block(text: "x")
        let result = BlockOperations.delete([only], blockID: only.id)
        #expect(result.blocks.count == 1)
        #expect(result.blocks[0].id != only.id)
    }

    @Test func deplacementEtDecalage() {
        let a = Block(text: "a"), b = Block(text: "b"), c = Block(text: "c")
        #expect(BlockOperations.move([a, b, c], blockID: c.id, before: a.id).map(\.id) == [c.id, a.id, b.id])
        #expect(BlockOperations.move([a, b, c], blockID: a.id, before: nil).map(\.id) == [b.id, c.id, a.id])
        #expect(BlockOperations.shift([a, b, c], blockID: b.id, by: 1).map(\.id) == [a.id, c.id, b.id])
        #expect(BlockOperations.shift([a, b, c], blockID: a.id, by: -1).map(\.id) == [a.id, b.id, c.id])
    }

    @Test func insertionRemplaceUnParagrapheVide() {
        let empty = Block()
        let generated = [Block(kind: .heading2, text: "Résumé"), Block(text: "…")]
        let blocks = BlockOperations.insert(generated, after: empty.id, in: [empty])
        #expect(blocks.map(\.id) == generated.map(\.id))
    }

    @Test func numerotationDesListes() {
        let blocks = [
            Block(kind: .numbered, text: "un"),
            Block(kind: .numbered, text: "deux"),
            Block(kind: .bulleted, text: "sous-puce", indent: 1),
            Block(kind: .numbered, text: "trois"),
            Block(kind: .paragraph, text: "coupure"),
            Block(kind: .numbered, text: "un bis"),
            Block(kind: .numbered, text: "imbriqué", indent: 1),
        ]
        let numbers = BlockOperations.listNumbers(blocks)
        #expect(numbers[blocks[0].id] == 1)
        #expect(numbers[blocks[1].id] == 2)
        #expect(numbers[blocks[3].id] == 3)
        #expect(numbers[blocks[5].id] == 1)
        #expect(numbers[blocks[6].id] == 1)
    }

    @Test func conversionVersUnBlocNonTextuelVideLeTexte() {
        let block = Block(text: "x")
        let blocks = BlockOperations.convert([block], blockID: block.id, to: .divider)
        #expect(blocks[0].text.isEmpty)
    }
}

@Suite("Raccourcis Markdown")
struct MarkdownShortcutTests {
    @Test(arguments: [
        ("# ", BlockKind.heading1), ("## ", .heading2), ("### ", .heading3),
        ("- ", .bulleted), ("* ", .bulleted), ("1. ", .numbered),
        ("[] ", .todo), ("> ", .quote), ("```", .code), ("---", .divider),
    ])
    func detection(prefix: String, kind: BlockKind) {
        #expect(MarkdownShortcut.detect(in: prefix, currentKind: .paragraph)?.kind == kind)
    }

    @Test func appliqueEtRetireLePrefixe() {
        let block = Block(text: RichText(runs: [TextRun("## "), TextRun("Titre", style: TextStyle(bold: true))]))
        let converted = MarkdownShortcut.apply(to: block)
        #expect(converted?.kind == .heading2)
        #expect(converted?.text.plainText == "Titre")
        #expect(converted?.id == block.id)
    }

    @Test func tacheCochee() {
        let converted = MarkdownShortcut.apply(to: Block(text: "[x] fait"))
        #expect(converted?.kind == .todo)
        #expect(converted?.isChecked == true)
    }

    @Test func ignoreHorsParagraphe() {
        #expect(MarkdownShortcut.detect(in: "# ", currentKind: .code) == nil)
        #expect(MarkdownShortcut.detect(in: "--- suite", currentKind: .paragraph) == nil)
        #expect(MarkdownShortcut.detect(in: "#hashtag", currentKind: .paragraph) == nil)
    }
}

@Suite("Document de note")
struct NoteDocumentTests {
    @Test func allerRetourJSON() {
        let map = MindMapDocument(title: "T", root: MindMapNode(label: "Racine", kind: .root, children: [MindMapNode(label: "Enfant", kind: .idea)]))
        let document = NoteDocument(blocks: [
            Block(kind: .heading1, text: "Titre"),
            Block(kind: .todo, text: "Tâche", indent: 2, isChecked: true),
            Block(kind: .image, attachment: "img.png"),
            Block(kind: .transcript, recordingID: UUID()),
            Block(kind: .mindMap, mindMap: map),
        ])
        #expect(NoteDocument.decode(document.encoded()) == document)
    }

    @Test func donneesInvalidesDonnentUnDocumentVide() {
        #expect(NoteDocument.decode(Data("pas du json".utf8)).blocks.count == 1)
        #expect(NoteDocument.decode(nil).blocks.count == 1)
    }

    @Test func typeInconnuLuCommeParagraphe() throws {
        let json = #"{"version":2,"blocks":[{"id":"\#(UUID().uuidString)","kind":"tableau","text":[{"text":"futur"}]}]}"#
        let document = NoteDocument.decode(Data(json.utf8))
        #expect(document.blocks[0].kind == .paragraph)
        #expect(document.blocks[0].text.plainText == "futur")
    }

    @Test func texteBrutIncluantLaCarteMentale() {
        let document = NoteDocument(blocks: [
            Block(text: "Bonjour"),
            Block(kind: .divider),
            Block(kind: .mindMap, mindMap: MindMapDocument(title: "M", root: MindMapNode(label: "Racine", kind: .root))),
        ])
        #expect(document.plainText == "Bonjour\nRacine")
    }
}
