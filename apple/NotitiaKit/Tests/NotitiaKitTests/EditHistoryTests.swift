import Foundation
import Testing
@testable import NotitiaKit

@Suite("Historique d'annulation")
struct EditHistoryTests {
    let start = Date(timeIntervalSinceReferenceDate: 0)

    func snapshot(_ texts: String...) -> EditHistory.Snapshot {
        EditHistory.Snapshot(blocks: texts.map { Block(text: RichText($0)) }, caret: nil)
    }

    @Test func annulerPuisRetablir() {
        var history = EditHistory()
        let a = snapshot("a"), b = snapshot("a", "b"), c = snapshot("b")
        history.record(a)          // a → b
        history.record(b)          // b → c
        #expect(history.undo(current: c) == b)
        #expect(history.undo(current: b) == a)
        #expect(history.undo(current: a) == nil)
        #expect(history.redo(current: a) == b)
        #expect(history.redo(current: b) == c)
        #expect(!history.canRedo)
    }

    @Test func uneNouvelleModificationEffaceLesEtapesARetablir() {
        var history = EditHistory()
        history.record(snapshot("a"))
        _ = history.undo(current: snapshot("b"))
        #expect(history.canRedo)
        history.record(snapshot("a"))
        #expect(!history.canRedo)
    }

    @Test func laFrappeContinueFormeUneSeuleEtape() {
        var history = EditHistory()
        let before = snapshot("")
        history.record(before, key: "saisie:1", at: start)
        history.record(snapshot("B"), key: "saisie:1", at: start.addingTimeInterval(0.4))
        history.record(snapshot("Bo"), key: "saisie:1", at: start.addingTimeInterval(0.8))
        #expect(history.undo(current: snapshot("Bon")) == before)
        #expect(!history.canUndo)
    }

    @Test func unePauseOuUnAutreBlocOuvreUneNouvelleEtape() {
        var history = EditHistory()
        history.record(snapshot(""), key: "saisie:1", at: start)
        history.record(snapshot("Bon"), key: "saisie:1", at: start.addingTimeInterval(3)) // pause
        history.record(snapshot("Bonjour"), key: "saisie:2", at: start.addingTimeInterval(3.2)) // autre bloc
        history.record(snapshot("Bonjour", "x"), at: start.addingTimeInterval(3.3)) // opération structurelle
        history.record(snapshot("Bonjour", "y"), key: "saisie:2", at: start.addingTimeInterval(3.4))
        var count = 0
        var current = snapshot("fin")
        while let previous = history.undo(current: current) {
            count += 1
            current = previous
        }
        #expect(count == 5)
    }

    @Test func finDeFrappeExplicite() {
        var history = EditHistory()
        history.record(snapshot(""), key: "saisie:1", at: start)
        history.endTyping()
        history.record(snapshot("a"), key: "saisie:1", at: start.addingTimeInterval(0.2))
        _ = history.undo(current: snapshot("ab"))
        #expect(history.canUndo)
    }

    @Test func historiqueLimite() {
        var history = EditHistory()
        for index in 0..<(EditHistory.limit + 50) { history.record(snapshot("\(index)")) }
        var count = 0
        var current = snapshot("fin")
        while let previous = history.undo(current: current) {
            count += 1
            current = previous
        }
        #expect(count == EditHistory.limit)
        #expect(current.blocks.map(\.text.plainText) == ["50"], "les étapes les plus anciennes sont oubliées")
    }
}
