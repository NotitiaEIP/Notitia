import Foundation
import Testing
@testable import NotitiaKit

private func segment(_ text: String, _ start: TimeInterval, _ end: TimeInterval) -> TranscriptSegment {
    TranscriptSegment(text: text, start: start, end: end)
}

@Suite("Annotations de transcription")
struct TranscriptAnnotationTests {
    @Test func typeInconnuLuSansErreur() throws {
        let json = #"[{"id":"\#(UUID().uuidString)","kind":"sigh","source":"futureEngine","start":1,"end":2,"confidence":0.9,"isHidden":false}]"#
        let annotations = [TranscriptAnnotation].decode(Data(json.utf8))
        #expect(annotations.count == 1)
        #expect(annotations.first?.kind == .unknown)
        #expect(annotations.first?.source == .unknown)
    }

    @Test func ancienEnregistrementSansAnnotations() {
        #expect([TranscriptAnnotation].decode(nil).isEmpty)
        #expect([TranscriptAnnotation].decode(Data()).isEmpty)
    }

    @Test func fusionDesChevauchementsDuMemeType() {
        let merged = [
            TranscriptAnnotation(kind: .raisedVoice, source: .soundAnalysis, start: 10, end: 12, confidence: 0.7),
            TranscriptAnnotation(kind: .raisedVoice, source: .audioLevel, start: 11.5, end: 14, confidence: 0.9),
            TranscriptAnnotation(kind: .laughter, source: .soundAnalysis, start: 11, end: 12),
            TranscriptAnnotation(kind: .raisedVoice, source: .audioLevel, start: 30, end: 31),
        ].mergingOverlaps()
        let raised = merged.filter { $0.kind == .raisedVoice }
        #expect(raised.count == 2)
        #expect(raised[0].start == 10 && raised[0].end == 14 && raised[0].confidence == 0.9)
        #expect(merged.contains { $0.kind == .laughter }, "les types différents ne fusionnent pas")
    }

    @Test func texteDuTon() {
        #expect(TranscriptAnnotation(kind: .tone, source: .foundationModels, start: 0, end: 5, label: "échange tendu sur le budget").text
                == "échange tendu sur le budget")
        #expect(TranscriptAnnotation(kind: .tone, source: .naturalLanguage, start: 0, end: 5, polarity: -0.8).text == "passage au ton négatif")
        #expect(TranscriptAnnotation(kind: .laughter, source: .soundAnalysis, start: 0, end: 1).text == "rires")
        #expect(TranscriptAnnotation(kind: .tone, source: .foundationModels, start: 0, end: 5, label: "désaccord sur le budget").exportText
                == "ton du passage : désaccord sur le budget")
        #expect(TranscriptAnnotation(kind: .laughter, source: .soundAnalysis, start: 0, end: 1).exportText == "rires")
    }
}

@Suite("Analyse des sons et du volume")
struct SoundAndLevelTests {
    @Test func etiquettesAppleVersTypes() {
        let confidences = SoundEventAggregator.confidences([
            ("giggling", 0.4), ("laughter", 0.8), ("applause", 0.3), ("speech", 0.99), ("dog", 0.9),
        ])
        #expect(confidences == [.laughter: 0.8, .applause: 0.3])
    }

    @Test func rireAvecHysterese() {
        var aggregator = SoundEventAggregator()
        var events: [TranscriptAnnotation] = []
        // Fenêtres de 1,5 s, pas de 0,75 s. Le rire démarre fort, faiblit sans disparaître, puis s'arrête.
        let confidences: [Double] = [0.1, 0.8, 0.5, 0.4, 0.1, 0.0, 0.0, 0.0]
        for (index, confidence) in confidences.enumerated() {
            let start = Double(index) * 0.75
            events += aggregator.add(start: start, end: start + 1.5, confidences: [.laughter: confidence])
        }
        events += aggregator.finish()
        #expect(events.count == 1)
        let laugh = events.first
        #expect(laugh?.kind == .laughter)
        #expect(laugh?.source == .soundAnalysis)
        #expect(laugh?.start == 0.75)
        #expect(laugh?.end == 2.25 + 1.5)
        #expect(laugh?.confidence == 0.8)
    }

    @Test func detectionFaibleIgnoree() {
        var aggregator = SoundEventAggregator()
        var events = aggregator.add(start: 0, end: 1.5, confidences: [.applause: 0.55])
        events += aggregator.finish()
        #expect(events.isEmpty)
    }

    @Test func deuxRiresProchesFormentUnSeulEvenement() {
        var aggregator = SoundEventAggregator()
        var events: [TranscriptAnnotation] = []
        events += aggregator.add(start: 0, end: 1.5, confidences: [.laughter: 0.9])
        events += aggregator.add(start: 1.5, end: 3, confidences: [.laughter: 0.1]) // encore dans l'écart toléré
        events += aggregator.add(start: 2.25, end: 3.75, confidences: [.laughter: 0.9])
        events += aggregator.add(start: 6, end: 7.5, confidences: [.laughter: 0])
        events += aggregator.finish()
        #expect(events.count == 1)
        #expect(events.first?.end == 3.75)
    }

    /// Simule des blocs audio de 0,1 s.
    func run(_ analyzer: inout LevelAnalyzer, level: Float, seconds: Double, from time: inout Double) -> [TranscriptAnnotation] {
        var events: [TranscriptAnnotation] = []
        for _ in 0..<Int((seconds / 0.1).rounded()) {
            events += analyzer.add(level: level, time: time, duration: 0.1)
            time += 0.1
        }
        return events
    }

    @Test func longSilenceRepere() {
        var analyzer = LevelAnalyzer()
        var time = 0.0
        var events = run(&analyzer, level: 0.5, seconds: 5, from: &time)
        events += run(&analyzer, level: 0.05, seconds: 8, from: &time)
        events += run(&analyzer, level: 0.5, seconds: 2, from: &time)
        events += run(&analyzer, level: 0.05, seconds: 3, from: &time) // trop court
        events += run(&analyzer, level: 0.5, seconds: 1, from: &time)
        events += analyzer.finish()
        let silences = events.filter { $0.kind == .longSilence }
        #expect(silences.count == 1)
        #expect(abs((silences.first?.start ?? 0) - 5) < 0.01)
        #expect(abs((silences.first?.duration ?? 0) - 8) < 0.01)
        #expect(silences.first?.source == .audioLevel)
    }

    @Test func volumeEleveApresEchauffement() {
        var analyzer = LevelAnalyzer()
        var time = 0.0
        // Pendant l'échauffement, un passage fort n'est pas signalé : le niveau habituel est inconnu.
        var events = run(&analyzer, level: 0.9, seconds: 2, from: &time)
        events += run(&analyzer, level: 0.5, seconds: 20, from: &time)
        events += run(&analyzer, level: 0.85, seconds: 3, from: &time)
        events += run(&analyzer, level: 0.5, seconds: 5, from: &time)
        events += run(&analyzer, level: 0.75, seconds: 0.5, from: &time) // trop court
        events += run(&analyzer, level: 0.5, seconds: 2, from: &time)
        events += analyzer.finish()
        let raised = events.filter { $0.kind == .raisedVoice }
        #expect(raised.count == 1)
        #expect(abs((raised.first?.start ?? 0) - 22) < 0.01)
        #expect(abs((raised.first?.duration ?? 0) - 3) < 0.01)
    }
}

@Suite("Chronologie et citations")
struct TranscriptTimelineTests {
    let segments = [
        segment("Bonjour à tous.", 0, 2),
        segment("Le budget a doublé.", 2, 5),
        segment("C'est une blague ?", 5, 7),
        segment("Non, c'est sérieux.", 7, 9),
    ]

    @Test func ordreTonTexteSons() {
        let laugh = TranscriptAnnotation(kind: .laughter, source: .soundAnalysis, start: 5, end: 6)
        let tone = TranscriptAnnotation(kind: .tone, source: .foundationModels, start: 2, end: 9, label: "échange tendu sur le budget")
        let hidden = TranscriptAnnotation(kind: .applause, source: .soundAnalysis, start: 1, end: 2, isHidden: true)
        let items = TranscriptTimeline.merge(segments: segments, annotations: [laugh, tone, hidden])
        #expect(items.map(\.id) == [segments[0].id, tone.id, segments[1].id, segments[2].id, laugh.id, segments[3].id])
        #expect(TranscriptTimeline.merge(segments: segments, annotations: [hidden], includeHidden: true).count == 5)
    }

    @Test func contexteDUneCitation() throws {
        let laugh = TranscriptAnnotation(kind: .laughter, source: .soundAnalysis, start: 5.5, end: 6)
        let far = TranscriptAnnotation(kind: .applause, source: .soundAnalysis, start: 20, end: 21)
        let context = try #require(TranscriptTimeline.context(of: segments[2].id, segments: segments, annotations: [laugh, far]))
        #expect(context.segments.map(\.text) == ["Le budget a doublé.", "C'est une blague ?", "Non, c'est sérieux."])
        #expect(context.annotations == [laugh])
        #expect(context.start == 2 && context.end == 9)

        let first = try #require(TranscriptTimeline.context(of: segments[0].id, segments: segments, annotations: []))
        #expect(first.segments.count == 2, "pas de phrase avant la première")
    }

    @Test func blocDeCitation() throws {
        let recordingID = UUID()
        let laugh = TranscriptAnnotation(kind: .laughter, source: .soundAnalysis, start: 5.5, end: 6)
        let context = try #require(TranscriptTimeline.context(of: segments[2].id, segments: segments, annotations: [laugh]))
        let blocks = TranscriptTimeline.quoteBlocks(context, recordingID: recordingID, recordedAt: .now)
        let quote = try #require(blocks.first)
        #expect(quote.kind == .quote)
        let text = quote.text.plainText
        #expect(text.contains("[00:02] Le budget a doublé."))
        #expect(text.contains("(rires, automatique)"))
        #expect(text.contains("transcription d'origine"))
        let cited = quote.text.runs.first { $0.text == "C'est une blague ?" }
        #expect(cited?.style.bold == true, "la phrase citée est en gras, le contexte non")
        #expect(!quote.text.runs.contains { $0.style.bold && $0.text.contains("Le budget") })
        let link = try #require(quote.text.runs.compactMap(\.style.link).first)
        #expect(TranscriptLink(url: try #require(URL(string: link))) == TranscriptLink(recordingID: recordingID, time: 2))
    }

    @Test func lienVersUnInstant() throws {
        let link = TranscriptLink(recordingID: UUID(), time: 75.4321)
        let parsed = try #require(TranscriptLink(url: link.url))
        #expect(parsed.recordingID == link.recordingID)
        #expect(abs(parsed.time - 75.432) < 0.001)
        #expect(TranscriptLink(url: URL(string: "https://example.com/recording/x")!) == nil)
    }
}

@Suite("Empreinte de la transcription")
struct TranscriptIntegrityTests {
    let segments = [segment("Le budget a doublé.", 2, 5), segment("Non, c'est sérieux.", 7, 9)]
    let laugh = TranscriptAnnotation(kind: .laughter, source: .soundAnalysis, start: 5.5, end: 6)

    @Test func empreinteStable() {
        let first = TranscriptIntegrity.fingerprint(segments: segments, annotations: [laugh])
        let second = TranscriptIntegrity.fingerprint(segments: segments, annotations: [laugh])
        #expect(first == second)
        #expect(first.hasPrefix("v1:"))
        #expect(first.count == 3 + 64)
    }

    @Test func modificationDuTexteDetectee() {
        let hash = TranscriptIntegrity.fingerprint(segments: segments, annotations: [laugh])
        var edited = segments
        edited[1].text = "Oui, c'est une blague."
        #expect(TranscriptIntegrity.status(expected: hash, segments: edited, annotations: [laugh]) == .modified)
        #expect(TranscriptIntegrity.status(expected: hash, segments: segments, annotations: []) == .modified, "une annotation observée supprimée se voit")
        #expect(TranscriptIntegrity.status(expected: nil, segments: segments, annotations: []) == .notSealed)
    }

    @Test func masquerOuInterpreterNeModifiePasLOriginal() {
        let hash = TranscriptIntegrity.fingerprint(segments: segments, annotations: [laugh])
        var hidden = laugh
        hidden.isHidden = true
        let tone = TranscriptAnnotation(kind: .tone, source: .foundationModels, start: 2, end: 9, label: "échange tendu")
        #expect(TranscriptIntegrity.status(expected: hash, segments: segments, annotations: [hidden, tone]) == .intact)
    }

    @Test func formatCanoniqueSansAmbiguite() {
        // Sans longueur de champ, « a|b » + « c » et « a » + « b|c » pourraient se confondre.
        let a = TranscriptIntegrity.canonical(segments: [TranscriptSegment(text: "b;c", start: 0, end: 1, speaker: "a")], annotations: [])
        let b = TranscriptIntegrity.canonical(segments: [TranscriptSegment(text: "c", start: 0, end: 1, speaker: "a;b")], annotations: [])
        #expect(a != b)
    }

    /// Sans base de données : une base en mémoire partagerait son stockage avec les autres suites lancées en parallèle.
    @Test func scelleUnEnregistrement() {
        let recording = Recording(localeIdentifier: "fr_FR", engineName: "test", consentConfirmedAt: .now)
        #expect(recording.annotations.isEmpty)
        #expect(recording.integrityStatus == .notSealed)

        recording.segments = segments
        recording.annotations = [laugh]
        recording.seal()
        #expect(recording.integrityStatus == .intact)

        var annotations = recording.annotations
        annotations[0].isHidden = true
        recording.annotations = annotations
        #expect(recording.integrityStatus == .intact)

        var edited = recording.segments
        edited[0].text = "Le budget a baissé."
        recording.segments = edited
        #expect(recording.integrityStatus == .modified)
    }
}

extension TranscriptIntegrityTests {
    @Test func renommerUnLocuteurNeModifiePasLOriginal() {
        let recording = Recording(localeIdentifier: "fr_FR", engineName: "test", consentConfirmedAt: .now)
        recording.segments = [
            TranscriptSegment(text: "Bonjour.", start: 0, end: 1, speaker: "Locuteur 1"),
            TranscriptSegment(text: "Salut.", start: 1, end: 2, speaker: "Locuteur 2"),
            TranscriptSegment(text: "On commence.", start: 2, end: 3, speaker: "Locuteur 1"),
        ]
        recording.seal()

        recording.rename(speaker: "Locuteur 1", to: "  Lucas ")
        #expect(recording.integrityStatus == .intact)
        #expect(recording.segments.map(\.speaker) == ["Locuteur 1", "Locuteur 2", "Locuteur 1"], "l'original garde le libellé neutre")
        #expect(recording.namedSegments.map(\.speaker) == ["Lucas", "Locuteur 2", "Lucas"])
        #expect(recording.segments.speakers == ["Locuteur 1", "Locuteur 2"])

        recording.rename(speaker: "Locuteur 1", to: "")
        #expect(recording.speakerNames.isEmpty, "un nom vide rend le libellé d'origine")
        #expect(recording.speakerNamesData == nil)
    }
}

@Suite("Transcription Apple et ton")
struct SpeechAndToneTests {
    @Test func resultatsProvisoiresPuisDefinitifs() throws {
        var tracker = SpeechResultTracker()
        let blank = tracker.apply(text: "  ", start: 0, end: 0.5, isFinal: false)
        #expect(blank == nil)
        let results = [
            tracker.apply(text: "Bonjour", start: 0, end: 1, isFinal: false),
            tracker.apply(text: "Bonjour à tous", start: 0, end: 1.6, isFinal: false),
            tracker.apply(text: " Bonjour à tous. ", start: 0, end: 1.8, isFinal: true),
            tracker.apply(text: "On commence", start: 2, end: 3, isFinal: false),
        ]
        let first = try #require(results[0])
        let second = try #require(results[1])
        let final = try #require(results[2])
        let next = try #require(results[3])
        #expect(first.id == second.id && second.id == final.id, "le même passage est remplacé, pas dupliqué")
        #expect(!first.isFinal && final.isFinal)
        #expect(final.text == "Bonjour à tous.")
        #expect(next.id != final.id)
    }

    @Test func resultatDefinitifDecoupeEnPhrases() throws {
        var tracker = SpeechResultTracker()
        let volatile = tracker.apply(words: [TimedText(text: "Bonjour", start: 0, end: 0.5)], start: 0, end: 0.5, isFinal: false)
        let words = [
            TimedText(text: "Bonjour", start: 0, end: 0.5), TimedText(text: " "), TimedText(text: "à", start: 0.5, end: 0.6),
            TimedText(text: " "), TimedText(text: "tous.", start: 0.6, end: 1.1), TimedText(text: " "),
            TimedText(text: "Premier", start: 1.9, end: 2.3), TimedText(text: " "), TimedText(text: "sujet ?", start: 2.3, end: 2.9),
            TimedText(text: " "), TimedText(text: "Oui", start: 3.5, end: 3.8),
        ]
        let finals = tracker.apply(words: words, start: 0, end: 4, isFinal: true)
        #expect(finals.map(\.text) == ["Bonjour à tous.", "Premier sujet ?", "Oui"])
        #expect(finals.map(\.start) == [0, 1.9, 3.5])
        #expect(finals.map(\.end) == [1.1, 2.9, 3.8])
        #expect(finals.allSatisfy { $0.isFinal })
        #expect(finals.first?.id == volatile.first?.id, "la première phrase remplace le passage provisoire")
        #expect(Set(finals.map(\.id)).count == 3)
        let next = tracker.apply(words: [TimedText(text: "Suite", start: 5, end: 5.4)], start: 5, end: 5.4, isFinal: false)
        #expect(!finals.map(\.id).contains(next.first!.id), "le résultat suivant ouvre un nouveau passage")
    }

    @Test func phraseSansHorodatage() {
        let sentences = TimedText.sentences([TimedText(text: "Oui. "), TimedText(text: "Non", start: 2, end: 2.5)], start: 1, end: 3)
        #expect(sentences == [TimedText(text: "Oui.", start: 1, end: 2), TimedText(text: "Non", start: 2, end: 2.5)])
    }

    @Test func passagesDecoupesSurLesPauses() {
        let segments = [
            segment("Point sur le budget du trimestre prochain.", 0, 3),
            segment("Les dépenses ont augmenté de vingt pour cent.", 3, 6),
            segment("Passons au calendrier de livraison du projet.", 14, 17), // pause de 8 s
            segment("Ok.", 40, 41), // trop court pour être un passage
        ]
        let passages = TonePassages.make(from: segments)
        #expect(passages.count == 2)
        #expect(passages[0].start == 0 && passages[0].end == 6)
        #expect(passages[0].text == "Point sur le budget du trimestre prochain. Les dépenses ont augmenté de vingt pour cent.")
        #expect(passages[1].start == 14)
    }

    @Test func passagesLimitesEnDuree() {
        let segments = (0..<20).map { segment("Phrase numéro \($0) de la réunion de ce matin.", Double($0) * 5, Double($0) * 5 + 4.5) }
        let passages = TonePassages.make(from: segments, maxDuration: 45)
        #expect(passages.count > 1)
        #expect(passages.allSatisfy { $0.end - $0.start <= 45 })
    }

    @Test func tonParNaturalLanguage() async {
        let segments = [
            segment("C'est inacceptable, ce retard est une catastrophe et ce travail est bâclé.", 0, 5),
            segment("La réunion reprend à quatorze heures dans la salle B du deuxième étage.", 30, 35),
        ]
        let annotations = await ToneAnalyzer(engine: .naturalLanguage).analyze(segments, locale: Locale(identifier: "fr_FR"))
        #expect(annotations.count == 1, "seul le passage nettement marqué est retenu")
        #expect(annotations.first?.kind == .tone)
        #expect(annotations.first?.source == .naturalLanguage)
        #expect((annotations.first?.polarity ?? 0) < 0)
        #expect(annotations.first?.start == 0)
    }

    @Test func libelleDuTonConstruitParNotitia() {
        #expect(ToneAnalyzer.ToneCategory.tendu.label(subject: "« Le budget. »") == "échange tendu sur le budget")
        #expect(ToneAnalyzer.ToneCategory.desaccord.label(subject: "le calendrier de livraison") == "désaccord sur le calendrier de livraison")
        #expect(ToneAnalyzer.ToneCategory.positif.label(subject: "") == "échange positif")
        #expect(ToneAnalyzer.ToneCategory.hesitant.label(subject: "un sujet beaucoup trop long pour être un vrai sujet") == "hésitation")
    }

    @Test func catalogueChoisitSelonLIdentifiant() {
        #expect(EngineCatalog.transcriptionEngine(id: "demo").descriptor.id == "demo")
        #expect(EngineCatalog.transcriptionEngine(id: "apple.speechanalyzer").descriptor.id == "apple.speechanalyzer")
        #expect(EngineCatalog.transcriptionEngine(id: "").descriptor.id == EngineCatalog.defaultTranscriptionEngineID)
        #expect(EngineCatalog.transcriptionEngines.first { $0.id == "apple.speechanalyzer" }?.isAvailable == true)
    }
}

@Suite("Export des annotations")
struct AnnotationExportTests {
    @Test func markdownAvecAnnotations() {
        let recordingID = UUID()
        let segments = [segment("Le budget a doublé.", 2, 5), segment("Non, c'est sérieux.", 7, 9)]
        let laugh = TranscriptAnnotation(kind: .laughter, source: .soundAnalysis, start: 5.5, end: 6)
        let hidden = TranscriptAnnotation(kind: .applause, source: .soundAnalysis, start: 8, end: 9, isHidden: true)
        let markdown = MarkdownRenderer.render(
            blocks: [Block(kind: .transcript, recordingID: recordingID)],
            segmentsByRecording: [recordingID: segments],
            annotationsByRecording: [recordingID: [laugh, hidden]]
        )
        // Deux espaces en fin de ligne : saut de ligne Markdown.
        #expect(markdown == [
            "### Transcription",
            "`[00:02]` Le budget a doublé.  ",
            "`[00:05]` *(rires, automatique)*  ",
            "`[00:07]` Non, c'est sérieux.  ",
        ].joined(separator: "\n"))
    }
}
