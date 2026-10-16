import Foundation
import Testing
@testable import NotitiaKit

@Suite("Coller plusieurs lignes")
struct PasteOperationsTests {
    @Test func lignesMarkdownReconnues() {
        let text = """
        # Réunion
        - premier point
          - détail
        2. étape
        - [x] fait
        > citation

        ---
        Texte libre
        """
        let blocks = PastedText.blocks(from: text)
        #expect(blocks.map(\.kind) == [.heading1, .bulleted, .bulleted, .numbered, .todo, .quote, .divider, .paragraph])
        #expect(blocks.map(\.text.plainText) == ["Réunion", "premier point", "détail", "étape", "fait", "citation", "", "Texte libre"])
        #expect(blocks[2].indent == 1)
        #expect(blocks[4].isChecked)
    }

    @Test func blocDeCodeEntreTroisAccents() {
        let blocks = PastedText.blocks(from: "Avant\n```\nlet a = 1\n  print(a)\n```\nAprès")
        #expect(blocks.map(\.kind) == [.paragraph, .code, .paragraph])
        #expect(blocks[1].text.plainText == "let a = 1\n  print(a)")
    }

    @Test func retraitParTabulationsEtPasPlusDUnNiveau() {
        let blocks = PastedText.blocks(from: "- a\n\t\t- trop loin\n\t- b")
        #expect(blocks.map(\.indent) == [0, 1, 1])
    }

    @Test func collageAuMilieuDUnBloc() throws {
        let block = Block(text: "Début fin")
        let result = BlockOperations.paste("un\n- deux\ntrois", into: [block], blockID: block.id, selection: 6..<6)
        #expect(result.blocks.map(\.text.plainText) == ["Début un", "deux", "troisfin"])
        #expect(result.blocks.map(\.kind) == [.paragraph, .bulleted, .paragraph])
        #expect(result.blocks[0].id == block.id, "le bloc d'origine garde son identité")
        let caret = try #require(result.caret)
        #expect(caret.blockID == result.blocks[2].id && caret.offset == 5, "curseur à la fin du texte collé")
    }

    @Test func unParagrapheVidePrendLeTypeDeLaPremiereLigne() {
        let empty = Block()
        let result = BlockOperations.paste("- un\n- deux\n", into: [empty], blockID: empty.id, selection: 0..<0)
        #expect(result.blocks.map(\.kind) == [.bulleted, .bulleted])
        #expect(result.blocks.map(\.text.plainText) == ["un", "deux"])
    }

    @Test func laSelectionEstRemplacee() {
        let block = Block(text: "Garder REMPLACER garder")
        let result = BlockOperations.paste("a\nb", into: [block], blockID: block.id, selection: 7..<16)
        #expect(result.blocks.map(\.text.plainText) == ["Garder a", "b garder"])
    }

    @Test func uneSeuleLigneOuUnBlocDeCodeRestentDansLeBloc() {
        let block = Block(text: "ab")
        #expect(BlockOperations.paste("X", into: [block], blockID: block.id, selection: 1..<1).blocks.map(\.text.plainText) == ["aXb"])
        let code = Block(kind: .code, text: "")
        let result = BlockOperations.paste("l1\nl2", into: [code], blockID: code.id, selection: 0..<0)
        #expect(result.blocks.count == 1)
        #expect(result.blocks[0].text.plainText == "l1\nl2")
    }

    @Test func retraitRelatifAuBlocDOrigine() {
        let item = Block(kind: .bulleted, text: "", indent: 1)
        let other = Block(kind: .bulleted, text: "x")
        let result = BlockOperations.paste("- a\n  - b", into: [other, item], blockID: item.id, selection: 0..<0)
        #expect(result.blocks.map(\.indent) == [0, 1, 2])
    }

    @Test func separateurEnFinDeCollageAvecUneSuite() {
        let block = Block(text: "avantaprès")
        let result = BlockOperations.paste("x\n---", into: [block], blockID: block.id, selection: 5..<5)
        #expect(result.blocks.map(\.kind) == [.paragraph, .divider, .paragraph])
        #expect(result.blocks.map(\.text.plainText) == ["avantx", "", "après"])
    }
}
