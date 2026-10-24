import Foundation
import Testing
@testable import NotitiaKit

@Suite("Texte riche")
struct RichTextTests {
    let bold = TextStyle(bold: true)

    @Test func normalisationFusionneLesFragmentsIdentiques() {
        let text = RichText(runs: [TextRun("a"), TextRun("b"), TextRun(""), TextRun("c", style: bold)])
        #expect(text.runs == [TextRun("ab"), TextRun("c", style: bold)])
    }

    @Test func coupureAuMilieuDUnFragment() {
        let text = RichText(runs: [TextRun("Hello "), TextRun("world", style: bold)])
        let (head, tail) = text.split(at: 8)
        #expect(head.plainText == "Hello wo")
        #expect(tail.plainText == "rld")
        #expect(head.runs.last?.style == bold)
        #expect(tail.runs.first?.style == bold)
    }

    @Test func coupureAuxBornes() {
        let text: RichText = "abc"
        #expect(text.split(at: 0).0.isEmpty)
        #expect(text.split(at: 3).1.isEmpty)
        #expect(text.split(at: 99).0.plainText == "abc")
        #expect(text.split(at: -4).1.plainText == "abc")
    }

    @Test func decalagesEnUTF16() {
        // « 😀 » occupe deux unités UTF-16, comme dans NSString.
        let text: RichText = "a😀b"
        #expect(text.length == 4)
        let (head, tail) = text.split(at: 3)
        #expect(head.plainText == "a😀")
        #expect(tail.plainText == "b")
        // Une coupure au milieu de la paire de substitution est ramenée avant l'emoji.
        let (h2, t2) = text.split(at: 2)
        #expect(h2.plainText == "a")
        #expect(t2.plainText == "😀b")
    }

    @Test func basculerLeGras() {
        let text: RichText = "Bonjour le monde"
        let bolded = text.toggling(.bold, in: 8..<10)
        #expect(bolded.runs.count == 3)
        #expect(bolded.runs[1] == TextRun("le", style: bold))
        #expect(bolded.allHave(.bold, in: 8..<10))
        #expect(!bolded.allHave(.bold, in: 0..<10))

        // Rebasculer sur une sélection entièrement en gras retire le gras.
        let unbolded = bolded.toggling(.bold, in: 8..<10)
        #expect(unbolded == text)

        // Sélection partiellement en gras : le gras est appliqué partout.
        let extended = bolded.toggling(.bold, in: 0..<10)
        #expect(extended.allHave(.bold, in: 0..<10))
    }

    @Test func styleALaPosition() {
        let text = RichText(runs: [TextRun("ab"), TextRun("cd", style: bold)])
        #expect(text.style(at: 2) == .plain)
        #expect(text.style(at: 3) == bold)
        #expect(text.style(at: 0) == .plain)
        #expect(text.style(at: 4) == bold)
    }

    @Test func encodageCompactEtAllerRetour() throws {
        let text = RichText(runs: [TextRun("plain"), TextRun("styled", style: TextStyle(italic: true, color: .red, link: "https://epitech.eu"))])
        let data = try JSONEncoder().encode(text)
        let json = String(decoding: data, as: UTF8.self)
        // Les attributs par défaut ne sont pas écrits.
        #expect(!json.contains("bold"))
        #expect(try JSONDecoder().decode(RichText.self, from: data) == text)
    }

    @Test func sousChaineEtSuppressionDePrefixe() {
        let text = RichText(runs: [TextRun("# "), TextRun("Titre", style: bold)])
        #expect(text.droppingPrefix(2).runs == [TextRun("Titre", style: bold)])
        #expect(text.substring(1..<4).plainText == " Ti")
    }
}
