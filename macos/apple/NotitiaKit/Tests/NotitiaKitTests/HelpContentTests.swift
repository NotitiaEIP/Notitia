import Foundation
import SwiftData
import Testing
@testable import NotitiaKit

@MainActor
@Suite("Note d'aide", .serialized)
struct HelpContentTests {
    /// Le conteneur doit rester en vie tant que son contexte est utilisé.
    func makeContainer() throws -> ModelContainer {
        try NotitiaSchema.makeContainer(inMemory: true)
    }

    @Test func montreTousLesTypesDeBlocsDeLEditeur() {
        let kinds = Set(HelpContent.document.blocks.map(\.kind))
        for kind in BlockKind.insertable where kind != .image {
            #expect(kinds.contains(kind), "le type \(kind) doit être illustré dans l'aide")
        }
        #expect(kinds.contains(.mindMap))
    }

    @Test func montreTousLesStyles() {
        let styles = HelpContent.document.blocks.flatMap(\.text.runs).map(\.style)
        #expect(styles.contains { $0.bold })
        #expect(styles.contains { $0.italic })
        #expect(styles.contains { $0.underline })
        #expect(styles.contains { $0.strikethrough })
        #expect(styles.contains { $0.code })
        #expect(styles.contains { $0.color != nil })
        #expect(styles.contains { $0.highlight != nil })
        #expect(styles.contains { $0.link != nil })
    }

    @Test func documentValide() {
        let document = HelpContent.document
        #expect(NoteDocument.decode(document.encoded()) == document)
        #expect(Set(document.blocks.map(\.id)).count == document.blocks.count, "identifiants de blocs uniques")
        let prose = document.blocks.filter { $0.kind != .code }.map(\.text.plainText).joined()
        #expect(!prose.contains("`"), "hors bloc de code, les accents graves sont convertis en style code")
    }

    @Test func installationIdempotente() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let first = HelpContent.install(in: context)
        let second = HelpContent.install(in: context)
        #expect(first.uid == HelpContent.noteID)
        #expect(first === second)
        #expect(first.isPinned)
        #expect(try context.fetchCount(FetchDescriptor<Note>()) == 1)
    }

    @Test func restaureeDepuisLaCorbeille() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let note = HelpContent.install(in: context)
        note.trashedAt = .now
        #expect(HelpContent.install(in: context).isTrashed == false)
    }

    @Test func recreeeApresSuppression() throws {
        let container = try makeContainer()
        let context = container.mainContext
        context.delete(HelpContent.install(in: context))
        try context.save()
        #expect(try context.fetchCount(FetchDescriptor<Note>()) == 0)
        HelpContent.install(in: context)
        #expect(try context.fetchCount(FetchDescriptor<Note>()) == 1)
    }
}
