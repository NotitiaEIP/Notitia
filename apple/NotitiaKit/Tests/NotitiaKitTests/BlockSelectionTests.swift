import Foundation
import Testing
@testable import NotitiaKit

@Suite("Sélection de plusieurs blocs")
struct BlockSelectionTests {
    let a = Block(text: "a"), b = Block(kind: .bulleted, text: "b"), c = Block(kind: .bulleted, text: "c"), d = Block(text: "d")
    var all: [Block] { [a, b, c, d] }

    @Test func plageDansLOrdreDuDocument() {
        let selection = BlockSelection(anchor: c.id, head: a.id)
        #expect(selection.ids(in: all) == [a.id, b.id, c.id])
        let extended = BlockSelection(anchor: b.id).moved(by: 1, extending: true, in: all)
        #expect(extended.ids(in: all) == [b.id, c.id])
        let moved = extended.moved(by: 1, extending: false, in: all)
        #expect(moved.ids(in: all) == [d.id])
        #expect(BlockSelection(anchor: a.id).moved(by: -1, extending: true, in: all).ids(in: all) == [a.id], "bornée au document")
    }

    @Test func suppressionGardeLesTranscriptions() {
        let transcript = Block(kind: .transcript, recordingID: UUID())
        let result = BlockOperations.delete([a, b, transcript, c, d], ids: [b.id, transcript.id, c.id])
        #expect(result.blocks.map(\.id) == [a.id, transcript.id, d.id])
        #expect(result.caret == Caret(blockID: a.id, offset: 1))
        let everything = BlockOperations.delete(all, ids: Set(all.map(\.id)))
        #expect(everything.blocks.count == 1 && everything.blocks[0].text.isEmpty, "une note garde toujours un bloc")
    }

    @Test func indentationEtDesindentationDuGroupe() {
        let indented = BlockOperations.indent(all, ids: [b.id, c.id])
        #expect(indented.map(\.indent) == [0, 1, 1, 0])
        let again = BlockOperations.indent(indented, ids: [b.id, c.id])
        #expect(again.map(\.indent) == [0, 1, 2, 0], "pas plus d'un niveau sous le bloc précédent")
        #expect(BlockOperations.outdent(again, ids: [b.id, c.id]).map(\.indent) == [0, 0, 1, 0])
    }

    @Test func deplacementDuGroupe() {
        #expect(BlockOperations.shift(all, ids: [b.id, c.id], by: -1).map(\.id) == [b.id, c.id, a.id, d.id])
        #expect(BlockOperations.shift(all, ids: [b.id, c.id], by: 1).map(\.id) == [a.id, d.id, b.id, c.id])
        #expect(BlockOperations.shift(all, ids: [a.id], by: -1).map(\.id) == all.map(\.id))
    }

    @Test func duplicationEtConversion() {
        let (blocks, copies) = BlockOperations.duplicate(all, ids: [b.id, c.id])
        #expect(blocks.map(\.text.plainText) == ["a", "b", "c", "b", "c", "d"])
        #expect(copies == [blocks[3].id, blocks[4].id])
        let image = Block(kind: .image)
        let converted = BlockOperations.convert([a, image, d], ids: [a.id, image.id, d.id], to: .heading2)
        #expect(converted.map(\.kind) == [.heading2, .image, .heading2])
    }

    @Test func collerDesBlocsCopiesAuCurseur() throws {
        let target = Block(text: "Avant après")
        let copied = BlockOperations.copyable([b, c, Block(kind: .transcript, recordingID: UUID())], ids: Set([b.id, c.id]))
        let result = BlockOperations.insertCopies(copied, into: [target], blockID: target.id, selection: 6..<6)
        #expect(result.blocks.map(\.text.plainText) == ["Avant ", "b", "c", "après"])
        #expect(result.blocks.map(\.kind) == [.paragraph, .bulleted, .bulleted, .paragraph])
        #expect(!result.blocks.contains { $0.id == b.id }, "les copies ont de nouveaux identifiants")
        let caret = try #require(result.caret)
        #expect(caret.blockID == result.blocks[2].id && caret.offset == 1)

        let empty = Block()
        #expect(BlockOperations.insertCopies([b], into: [empty], blockID: empty.id, selection: 0..<0).blocks.map(\.text.plainText) == ["b"])
    }

    @Test func lesTranscriptionsNeSeCopientPas() {
        let transcript = Block(kind: .transcript, recordingID: UUID())
        #expect(BlockOperations.copyable([a, transcript], ids: [a.id, transcript.id]).map(\.id) == [a.id])
    }

    @Test func uneImageColleeAPropreFichier() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "notitia-copie-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let paths = AppPaths(root: root)
        try paths.prepare()
        try Data("png".utf8).write(to: paths.attachmentURL("image.png"))
        let blocks = paths.copyingAttachments(of: [Block(kind: .image, attachment: "image.png"), a, Block(kind: .image, attachment: "absente.png")])
        let copy = try #require(blocks[0].attachment)
        #expect(copy != "image.png" && copy.hasSuffix(".png"))
        #expect(try Data(contentsOf: paths.attachmentURL(copy)) == Data("png".utf8))
        #expect(blocks[1] == a)
        #expect(blocks[2].attachment == "absente.png")
    }
}
