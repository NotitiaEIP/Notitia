@preconcurrency import AVFoundation
import Foundation
import Testing
@testable import NotitiaKit

/// Tests d'intégration avec les vrais moteurs d'Apple (parole de synthèse produite par `say`).
/// Désactivés par défaut : ils dépendent de la machine (modèle de langue installé, voix française).
///
///     NOTITIA_INTEGRATION=1 swift test --filter "Moteurs Apple"
@Suite("Moteurs Apple (intégration)", .enabled(if: ProcessInfo.processInfo.environment["NOTITIA_INTEGRATION"] == "1"), .serialized)
struct AppleEnginesIntegrationTests {
    static let sentence = "Bonjour à tous. Le budget du projet a doublé ce trimestre. La démonstration est prévue vendredi."

    /// Parole de synthèse suivie de `trailingSilence` secondes de silence, découpée comme le micro (blocs de 4 096).
    static func speechChunks(trailingSilence: TimeInterval = 0) throws -> [AudioChunk] {
        let tmp = FileManager.default.temporaryDirectory.appending(path: "notitia-say-\(UUID().uuidString).aiff")
        defer { try? FileManager.default.removeItem(at: tmp) }
        let say = Process()
        say.executableURL = URL(filePath: "/usr/bin/say")
        say.arguments = ["-v", "Thomas", "-o", tmp.path, sentence]
        try say.run()
        say.waitUntilExit()
        try #require(say.terminationStatus == 0, "voix française « Thomas » introuvable")

        let file = try AVAudioFile(forReading: tmp)
        let format = try #require(AVAudioFormat(standardFormatWithSampleRate: file.processingFormat.sampleRate, channels: 1))
        let buffer = try #require(AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)))
        try file.read(into: buffer)
        var samples = Array(UnsafeBufferPointer(start: try #require(buffer.floatChannelData?[0]), count: Int(buffer.frameLength)))
        samples += [Float](repeating: 0, count: Int(trailingSilence * format.sampleRate))

        let size = 4096
        return stride(from: 0, to: samples.count, by: size).map { offset in
            AudioChunk(samples: Array(samples[offset..<min(offset + size, samples.count)]),
                       sampleRate: format.sampleRate,
                       time: Double(offset) / format.sampleRate)
        }
    }

    static func stream(_ chunks: [AudioChunk]) -> AsyncStream<AudioChunk> {
        AsyncStream { continuation in
            chunks.forEach { continuation.yield($0) }
            continuation.finish()
        }
    }

    @Test func transcriptionReelleEnFrancais() async throws {
        let status = await AppleSpeechTranscriptionEngine.modelStatus(for: Locale(identifier: "fr_FR"))
        try #require(status == .installed || status == .notInstalled, "modèle indisponible : \(status)")

        let chunks = try Self.speechChunks()
        var segments: [UUID: TranscriptSegment] = [:]
        for try await segment in AppleSpeechTranscriptionEngine().transcribe(audio: Self.stream(chunks), locale: Locale(identifier: "fr_FR")) {
            segments[segment.id] = segment
        }
        let finals = segments.values.filter(\.isFinal).sorted { $0.start < $1.start }
        let text = finals.map(\.text).joined(separator: " ").lowercased()
        print("Transcription :", text)
        #expect(!finals.isEmpty)
        #expect(text.contains("budget"))
        #expect(text.contains("vendredi"))
        let duration = chunks.last.map { $0.time + $0.duration } ?? 0
        #expect(finals.allSatisfy { $0.start >= 0 && $0.end <= duration + 0.5 }, "horodatages sur l'horloge de l'enregistrement")
    }

    @Test func sonsEtVolumeSurDeLaParole() async throws {
        let chunks = try Self.speechChunks(trailingSilence: 8)
        var annotations: [TranscriptAnnotation] = []
        for try await annotation in SoundEventDetector().detect(audio: Self.stream(chunks)) {
            annotations.append(annotation)
        }
        for try await annotation in LevelAnnotationDetector().detect(audio: Self.stream(chunks)) {
            annotations.append(annotation)
        }
        print("Annotations :", annotations.map { "\($0.kind.rawValue) \($0.start)–\($0.end)" })
        #expect(!annotations.contains { $0.kind == .laughter || $0.kind == .applause }, "pas de faux rire sur de la parole")
        #expect(annotations.contains { $0.kind == .longSilence }, "les 8 s de silence finales sont repérées")
    }

    @Test func tonAvecFoundationModels() async throws {
        let locale = Locale(identifier: "fr_FR")
        try #require(ToneAnalyzer.isFoundationModelAvailable(for: locale), "Apple Intelligence indisponible sur ce Mac")
        let segments = [
            TranscriptSegment(text: "Ce retard est inacceptable, on avait promis la livraison au client depuis un mois.", start: 0, end: 5),
            TranscriptSegment(text: "Je ne suis pas d'accord, l'équipe a fait ce qu'elle pouvait avec ce budget ridicule.", start: 5, end: 10),
            TranscriptSegment(text: "La salle de réunion du deuxième étage est réservée pour jeudi à quatorze heures.", start: 30, end: 35),
            TranscriptSegment(text: "Le compte rendu sera envoyé par courriel à toute l'équipe en fin de journée.", start: 35, end: 40),
        ]
        let annotations = await ToneAnalyzer(engine: .foundationModels).analyze(segments, locale: locale)
        print("Ton :", annotations.map { "\($0.start)–\($0.end) \($0.text) (\($0.polarity ?? 0))" })
        #expect(annotations.allSatisfy { $0.kind == .tone && $0.source == .foundationModels })
        let tense = try #require(annotations.first { $0.start == 0 }, "le passage conflictuel est annoté")
        #expect((tense.polarity ?? 0) < 0)
    }
}
