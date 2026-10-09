import Foundation
import Testing
@testable import NotitiaKit

@Suite("IA locale")
struct LocalAITests {
    let service = LocalExtractiveAIService()
    var source: AISource {
        AISource(title: "Point d'avancement", text: DemoTranscriptionEngine.defaultScript.joined(separator: " "))
    }

    @Test func motsClesDeterministes() async throws {
        let first = try await service.keywords(source, limit: 6)
        let second = try await service.keywords(source, limit: 6)
        #expect(first == second)
        #expect(first.count == 6)
        #expect(!first.contains { ["bonjour", "merci", "pour"].contains($0.lowercased()) })
    }

    @Test func resumeContientLesSections() async throws {
        let blocks = try await service.summarize(source, focus: [])
        let headings = blocks.filter { $0.kind == .heading3 }.map(\.text.plainText)
        #expect(blocks.first?.text.plainText == "Résumé")
        #expect(headings.contains("Décisions"))
        #expect(headings.contains("Actions"))
        #expect(headings.contains("Questions ouvertes"))
        #expect(blocks.contains { $0.kind == .todo })
        #expect(blocks.last?.kind == .callout)
    }

    @Test func resumeCentreSurLesMotsClesDeLUtilisateur() async throws {
        let blocks = try await service.summarize(source, focus: ["design", "licorne"])
        let headings = blocks.filter { $0.kind == .heading3 }.map(\.text.plainText)
        #expect(headings == ["Design", "Licorne"])
        #expect(blocks.contains { $0.text.plainText == "Aucune mention dans la source." })
    }

    @Test func carteMentaleRelieeALaSource() async throws {
        let map = try await service.mindMap(source, focus: [])
        #expect(map.root.kind == .root)
        #expect(map.root.label == "Point d'avancement")
        #expect(map.root.children.count >= 3)
        let leaves = map.root.children.flatMap(\.children).filter { $0.kind != .person && $0.kind != .date }
        #expect(!leaves.isEmpty)
        // Règle « sourceText » : chaque feuille cite un passage exact de la source.
        for leaf in leaves {
            let quoted = try #require(leaf.sourceText)
            #expect(source.text.contains(quoted))
        }
    }

    @Test func sourceTropCourteRefusee() async {
        await #expect(throws: AIError.self) {
            _ = try await service.summarize(AISource(title: "", text: "court"), focus: [])
        }
    }

    @Test func libelleCondense() {
        #expect(TextAnalysis.condensed("Alors, il faut finir les listes de tâches et le menu des blocs avant vendredi.")
                == "Il faut finir les listes de tâches et…")
    }
}

@Suite("Transcription de démonstration")
struct DemoTranscriptionTests {
    @Test func dicteAuRythmeDeLAudio() {
        var dictation = DemoTranscriptionEngine.Dictation(sentences: ["un deux trois quatre", "cinq six"], wordsPerSecond: 2)
        var segments = dictation.advance(by: 1) // 2 mots
        #expect(segments.last?.text == "un deux")
        #expect(segments.last?.isFinal == false)

        segments = dictation.advance(by: 1.5) // 5 mots au total
        let finals = segments.filter(\.isFinal)
        #expect(finals.map(\.text) == ["un deux trois quatre"])
        #expect(finals.first?.start == 0)
        #expect(finals.first?.end == 2)
        #expect(segments.last?.text == "cinq")

        let last = dictation.finish()
        #expect(last?.text == "cinq")
        #expect(last?.isFinal == true)
        #expect(last?.start == 2)
    }

    @Test func fluxCompletAvecMoteur() async throws {
        let engine = DemoTranscriptionEngine(script: ["Bonjour à tous.", "Merci."], wordsPerSecond: 10)
        let (audio, continuation) = AsyncStream.makeStream(of: AudioChunk.self)
        let silence = AudioChunk(samples: Array(repeating: 0, count: 4_800), sampleRate: 48_000) // 0,1 s
        for _ in 0..<5 { continuation.yield(silence) }
        continuation.finish()

        var latest: [UUID: TranscriptSegment] = [:]
        for try await segment in engine.transcribe(audio: audio, locale: Locale(identifier: "fr_FR")) {
            latest[segment.id] = segment
        }
        let texts = latest.values.filter(\.isFinal).sorted { $0.start < $1.start }.map(\.text)
        #expect(texts == ["Bonjour à tous.", "Merci."])
    }

    @Test func niveauAudio() {
        #expect(AudioChunk(samples: Array(repeating: 0, count: 100), sampleRate: 48_000).level == 0)
        #expect(AudioChunk(samples: Array(repeating: 0.5, count: 100), sampleRate: 48_000).level > 0.8)
    }
}

@Suite("Carte mentale")
struct MindMapLayoutTests {
    func sample(collapsed: Bool = false) -> MindMapNode {
        MindMapNode(label: "Racine", kind: .root, children: (1...4).map { i in
            MindMapNode(label: "Thème \(i)", kind: .topic, isCollapsed: collapsed && i == 1, children: (1...3).map { j in
                MindMapNode(label: "Idée \(i).\(j)", kind: .idea)
            })
        })
    }

    @Test func aucunChevauchement() {
        let layout = MindMapLayout(root: sample())
        let frames = Array(layout.frames.values)
        #expect(frames.count == 17)
        for i in frames.indices {
            for j in frames.indices where j > i {
                #expect(!frames[i].intersects(frames[j]))
            }
        }
        #expect(layout.edges.count == 16)
    }

    @Test func brancheARepartiesDesDeuxCotes() {
        let root = sample()
        let layout = MindMapLayout(root: root)
        let rootFrame = layout.frames[root.id]!
        #expect(layout.frames[root.children[0].id]!.minX > rootFrame.maxX)
        #expect(layout.frames[root.children[3].id]!.maxX < rootFrame.minX)
        #expect(layout.bounds.contains(layout.frames.values.reduce(rootFrame) { $0.union($1) }))
    }

    @Test func brancheRepliee() {
        let layout = MindMapLayout(root: sample(collapsed: true))
        #expect(layout.frames.count == 14)
    }

    @Test func miseAJourDUnNoeud() {
        var root = sample()
        let target = root.children[2].children[1].id
        #expect(root.update(target) { $0.label = "Modifié" })
        #expect(root.find(target)?.label == "Modifié")
    }
}

@Suite("Recherche")
struct SearchTests {
    @Test func insensibleALaCasseEtAuxAccents() {
        #expect(NoteSearch.score(title: "Dérivées usuelles", body: "", query: "derivees") != nil)
        #expect(NoteSearch.score(title: "", body: "Le CHINOIS est tonal", query: "chinois tonal") != nil)
        #expect(NoteSearch.score(title: "Maths", body: "dérivées", query: "chinois") == nil)
    }

    @Test func leTitrePesePlusQueLeContenu() throws {
        let inTitle = try #require(NoteSearch.score(title: "Chinois", body: "", query: "chinois"))
        let inBody = try #require(NoteSearch.score(title: "Cours", body: "chinois", query: "chinois"))
        #expect(inTitle > inBody)
    }

    @Test func extrait() {
        let body = String(repeating: "x ", count: 100) + "le mot recherché ici " + String(repeating: "y ", count: 100)
        let snippet = NoteSearch.snippet(body: body, query: "RECHERCHE")
        #expect(snippet?.contains("recherché") == true)
        #expect(snippet?.hasPrefix("…") == true)
    }
}

@Suite("Analyse de texte")
struct TextAnalysisTests {
    @Test func indicesEnDebutDeMot() {
        #expect(TextAnalysis.containsCue("il faut finir", "faut"))
        #expect(!TextAnalysis.containsCue("un defaut mineur", "faut"))
        #expect(TextAnalysis.containsCue("c'est decide", "decid"))
    }

    @Test func elisions() {
        #expect(TextAnalysis.stripElision("l'appareil") == "appareil")
        #expect(TextAnalysis.stripElision("D'avancement") == "avancement")
        #expect(TextAnalysis.stripElision("qu’on") == "on")
        #expect(TextAnalysis.stripElision("aujourd'hui") == "aujourd'hui")
    }
}
