import Foundation
import Testing
@testable import NotitiaKit

@Suite("Séparation des locuteurs")
struct SpeakerDiarizationTests {
    static let rate = 16_000.0

    /// Voix synthétique : harmoniques de `pitch`, modelées par trois formants (l'empreinte du conduit vocal).
    static func voice(pitch: Double, formants: [Double], seconds: Double, seed: Double = 0) -> [Float] {
        (0..<Int(seconds * rate)).map { n in
            let t = Double(n) / rate
            // Légère intonation, pour ne pas avoir un son parfaitement stable.
            let f0 = pitch * (1 + 0.04 * sin(2 * .pi * 0.7 * t + seed))
            var sample = 0.0
            var harmonic = 1
            while Double(harmonic) * f0 < 4000 {
                let frequency = Double(harmonic) * f0
                let gain = formants.reduce(0.0) { $0 + exp(-pow((frequency - $1) / 120, 2)) } + 0.02
                sample += gain * sin(2 * .pi * frequency * t + Double(harmonic) * seed)
                harmonic += 1
            }
            return Float(0.05 * sample)
        }
    }

    static let grave = (pitch: 110.0, formants: [500.0, 1500, 2500])
    static let aigue = (pitch: 210.0, formants: [800.0, 1200, 2900])

    /// Réunion synthétique : les répliques se suivent, chacune devient un segment.
    static func meeting(_ turns: [(pitch: Double, formants: [Double])], seconds: Double = 2.5) -> (voice: [VoiceBlock], segments: [TranscriptSegment]) {
        var extractor = VoiceFeatureExtractor()
        var segments: [TranscriptSegment] = []
        var time = 0.0
        for (index, turn) in turns.enumerated() {
            let samples = voice(pitch: turn.pitch, formants: turn.formants, seconds: seconds, seed: Double(index))
            for offset in stride(from: 0, to: samples.count, by: 4096) {
                let slice = Array(samples[offset..<min(offset + 4096, samples.count)])
                extractor.add(AudioChunk(samples: slice, sampleRate: rate, time: time + Double(offset) / rate))
            }
            segments.append(TranscriptSegment(text: "Réplique \(index + 1)", start: time, end: time + seconds))
            time += seconds
        }
        return (extractor.finish(), segments)
    }

    @Test func mesureLaHauteurDeLaVoix() {
        let samples = Self.voice(pitch: 150, formants: [600, 1400, 2600], seconds: 0.05)
        let pitch = samples.withUnsafeBufferPointer {
            VoiceFeatureExtractor.pitch(of: UnsafeBufferPointer(rebasing: $0[0..<400]), sampleRate: Self.rate)
        }
        #expect(pitch.map { abs($0 - 150) < 8 } == true, "hauteur mesurée : \(String(describing: pitch))")
    }

    @Test func leSilenceNeProduitAucuneMesure() {
        var extractor = VoiceFeatureExtractor()
        extractor.add(AudioChunk(samples: [Float](repeating: 0, count: 32_000), sampleRate: Self.rate))
        #expect(extractor.finish().isEmpty)
    }

    @Test func deuxVoixAlterneesDonnentDeuxLocuteurs() {
        let (voice, segments) = Self.meeting([Self.grave, Self.aigue, Self.grave, Self.aigue, Self.aigue, Self.grave])
        let labeled = SpeakerDiarization.label(segments, voice: voice)
        #expect(labeled.map(\.speaker) == ["Locuteur 1", "Locuteur 2", "Locuteur 1", "Locuteur 2", "Locuteur 2", "Locuteur 1"])
        #expect(labeled.map(\.text) == segments.map(\.text), "seul le locuteur change")
    }

    @Test func uneSeuleVoixResteSansLocuteur() {
        let (voice, segments) = Self.meeting([Self.grave, Self.grave, Self.grave, Self.grave])
        #expect(SpeakerDiarization.label(segments, voice: voice).allSatisfy { $0.speaker == nil })
    }

    @Test func leNombreDeParticipantsIndiqueEstRespecte() {
        let (voice, segments) = Self.meeting([Self.grave, Self.aigue, Self.grave, Self.aigue])
        let labeled = SpeakerDiarization.label(segments, voice: voice, options: .init(expectedSpeakers: 2))
        #expect(Set(labeled.compactMap(\.speaker)) == ["Locuteur 1", "Locuteur 2"])
    }

    @Test func tropPeuDeParoleNeConclutRien() {
        let (voice, segments) = Self.meeting([Self.grave, Self.aigue], seconds: 0.3)
        #expect(SpeakerDiarization.label(segments, voice: voice) == segments)
    }

    @Test func unSegmentTresCourtPrendLeLocuteurLePlusProche() {
        let (voice, initial) = Self.meeting([Self.grave, Self.aigue, Self.grave, Self.aigue])
        // « Oui. » de 0,3 s dans la deuxième réplique : trop court pour le regroupement, rattaché à la voix aiguë.
        let segments = initial + [TranscriptSegment(text: "Oui.", start: 3.0, end: 3.3)]
        let labeled = SpeakerDiarization.label(segments, voice: voice)
        #expect(labeled.last?.speaker == labeled[1].speaker)
    }

    @Test func regroupementHierarchique() {
        let points: [[Float]] = [[0, 0], [0.2, 0], [5, 5], [5.1, 5.2], [0.1, 0.1]]
        let weights = [Float](repeating: 1, count: points.count)
        let groups = AgglomerativeClustering.run(points: points, weights: weights, targetCount: nil, threshold: 1, maximumCount: 8)
        #expect(groups[0] == groups[1] && groups[1] == groups[4])
        #expect(groups[2] == groups[3])
        #expect(groups[0] != groups[2])
        let one = AgglomerativeClustering.run(points: points, weights: weights, targetCount: 1, threshold: 1, maximumCount: 8)
        #expect(Set(one).count == 1)
    }

    @Test func unPetitGroupeEstRattacheAuPlusProche() {
        let points: [[Float]] = [[0, 0], [0.1, 0], [5, 5], [5.1, 5], [2, 2]]
        let weights: [Float] = [10, 10, 10, 10, 0.5]
        let groups = AgglomerativeClustering.absorbSmallGroups([0, 0, 2, 2, 4], points: points, weights: weights, minimumShare: 0.05)
        #expect(groups == [0, 0, 2, 2, 0])
    }
}
