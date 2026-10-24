import Foundation
import Testing
@testable import NotitiaKit

/// Séparation des locuteurs sur de vraies voix de synthèse : les répliques de la réunion de test
/// (`scripts/reunion-test/script.tsv`), produites par `scripts/reunion-test/generer.sh` dans `.phrases/`.
/// Désactivé par défaut : dépend des voix installées sur la machine.
///
///     scripts/reunion-test/generer.sh
///     NOTITIA_INTEGRATION=1 swift test --filter SpeakerDiarizationIntegrationTests
///
/// Les répliques ne sont pas synthétisées ici : lancé depuis `swift test`, `say` remplace sans prévenir la plupart
/// des voix (Flo, Amélie…) par la voix par défaut, et le test comparerait deux fois la même voix.
@Suite("Locuteurs (intégration)", .enabled(if: ProcessInfo.processInfo.environment["NOTITIA_INTEGRATION"] == "1"), .serialized)
struct SpeakerDiarizationIntegrationTests {
    struct Line {
        var voice: String
        var text: String
        var audio: URL
    }

    static let folder = URL(filePath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "scripts/reunion-test")

    /// Répliques dans l'ordre du script ; le fichier `NN.aiff` correspond à la ligne `NN` (les silences comptent).
    static var script: [Line] {
        get throws {
            let rows = try String(contentsOf: folder.appending(path: "script.tsv"), encoding: .utf8).split(separator: "\n")
            var lines: [Line] = []
            for (index, row) in rows.enumerated() {
                let columns = row.split(separator: "\t", maxSplits: 1).map(String.init)
                guard columns.count == 2, columns[0] != "SILENCE" else { continue }
                let audio = folder.appending(path: ".phrases").appending(path: String(format: "%02d.aiff", index + 1))
                try #require(FileManager.default.fileExists(atPath: audio.path), "lancer d'abord scripts/reunion-test/generer.sh")
                lines.append(Line(voice: columns[0], text: columns[1], audio: audio))
            }
            return lines
        }
    }

    /// Réunion reconstituée : chaque phrase devient un segment, avec sa vraie voix en référence.
    static func meeting(_ lines: [Line]) throws -> (voice: [VoiceBlock], segments: [TranscriptSegment], truth: [String]) {
        var extractor = VoiceFeatureExtractor()
        var segments: [TranscriptSegment] = []
        var truth: [String] = []
        var time: TimeInterval = 0
        for line in lines {
            let chunks = try AudioFileReader.chunks(of: line.audio).chunks
            for chunk in chunks {
                extractor.add(AudioChunk(samples: chunk.samples, sampleRate: chunk.sampleRate, time: time + chunk.time))
            }
            let duration = chunks.last.map { $0.time + $0.duration } ?? 0
            // SpeechAnalyzer découpe en phrases : on fait de même, en répartissant la durée selon la longueur.
            let sentences = line.text.split(whereSeparator: { ".?!".contains($0) })
                .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            let total = Double(sentences.reduce(0) { $0 + $1.count })
            var start = time
            for sentence in sentences {
                let length = duration * Double(sentence.count) / total
                segments.append(TranscriptSegment(text: sentence, start: start, end: start + length))
                truth.append(line.voice)
                start += length
            }
            time += duration
        }
        return (extractor.finish(), segments, truth)
    }

    /// Part des segments dont le groupe est majoritairement celui de leur vraie voix.
    static func purity(_ labeled: [TranscriptSegment], truth: [String]) -> Double {
        var counts: [String: [String: Int]] = [:]
        for (segment, voice) in zip(labeled, truth) {
            counts[segment.speaker ?? "—", default: [:]][voice, default: 0] += 1
        }
        let correct = counts.values.reduce(0) { $0 + ($1.values.max() ?? 0) }
        return Double(correct) / Double(labeled.count)
    }

    @Test func troisVoixDeSynthese() throws {
        let (voice, segments, truth) = try Self.meeting(Self.script)
        let labeled = SpeakerDiarization.label(segments, voice: voice)
        let speakers = Set(labeled.compactMap(\.speaker))
        let purity = Self.purity(labeled, truth: truth)
        for (segment, voice) in zip(labeled, truth) {
            print(segment.speaker ?? "—", "\t", voice, "\t", segment.text.prefix(40))
        }
        print("Locuteurs :", speakers.count, "— pureté :", purity)
        #expect(speakers.count == 3)
        #expect(purity >= 0.9)

        let forced = SpeakerDiarization.label(segments, voice: voice, options: .init(expectedSpeakers: 3))
        #expect(Set(forced.compactMap(\.speaker)).count == 3)
    }

    @Test func uneSeuleVoixResteSansLocuteur() throws {
        let (voice, segments, _) = try Self.meeting(Self.script.filter { $0.voice == "Thomas" })
        let labeled = SpeakerDiarization.label(segments, voice: voice)
        print("Une voix :", labeled.map { $0.speaker ?? "—" })
        #expect(labeled.allSatisfy { $0.speaker == nil })
    }
}
