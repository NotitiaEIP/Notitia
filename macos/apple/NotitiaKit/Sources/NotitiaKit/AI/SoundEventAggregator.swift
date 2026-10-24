import Foundation

/// Transforme les classifications brutes d'un classifieur de sons (une par fenêtre d'analyse)
/// en annotations propres : seuil d'entrée, seuil de maintien (hystérésis), fusion des fenêtres proches,
/// durée minimale.
///
/// Logique pure, indépendante de SoundAnalysis, pour être testée sans audio.
public struct SoundEventAggregator: Sendable {
    /// Étiquettes du classifieur intégré d'Apple (`SNClassifierIdentifier.version1`) retenues par Notitia.
    /// Vérifiées avec `knownClassifications` (303 étiquettes, macOS 26).
    public static let labels: [String: TranscriptAnnotation.Kind] = [
        "laughter": .laughter,
        "giggling": .laughter,
        "belly_laugh": .laughter,
        "chuckle_chortle": .laughter,
        "snicker": .laughter,
        "applause": .applause,
        "clapping": .applause,
        "cheering": .applause,
        "shout": .raisedVoice,
        "yell": .raisedVoice,
    ]

    public struct Thresholds: Sendable {
        /// Confiance pour ouvrir un événement.
        public var enter: Double = 0.6
        /// Confiance pour le prolonger.
        public var stay: Double = 0.35
        /// Deux détections séparées de moins de `gap` secondes forment un seul événement.
        public var gap: TimeInterval = 1.0
        /// Les événements plus courts sont ignorés.
        public var minimumDuration: TimeInterval = 0.4

        public init() {}
    }

    private struct Open: Sendable {
        var start: TimeInterval
        var end: TimeInterval
        var confidence: Double
    }

    public var thresholds: Thresholds
    private var open: [TranscriptAnnotation.Kind: Open] = [:]

    public init(thresholds: Thresholds = Thresholds()) {
        self.thresholds = thresholds
    }

    /// Confiance maximale par type, à partir des classifications d'une fenêtre.
    public static func confidences(_ classifications: [(identifier: String, confidence: Double)]) -> [TranscriptAnnotation.Kind: Double] {
        var result: [TranscriptAnnotation.Kind: Double] = [:]
        for (identifier, confidence) in classifications {
            guard let kind = labels[identifier] else { continue }
            result[kind] = max(result[kind] ?? 0, confidence)
        }
        return result
    }

    /// Ajoute une fenêtre d'analyse ; renvoie les événements terminés.
    public mutating func add(start: TimeInterval, end: TimeInterval, confidences: [TranscriptAnnotation.Kind: Double]) -> [TranscriptAnnotation] {
        var closed: [TranscriptAnnotation] = []
        let kinds = Set(open.keys).union(confidences.keys)
        for kind in kinds.sorted(by: { $0.rawValue < $1.rawValue }) {
            let confidence = confidences[kind] ?? 0
            if var event = open[kind] {
                if confidence >= thresholds.stay {
                    event.end = max(event.end, end)
                    event.confidence = max(event.confidence, confidence)
                    open[kind] = event
                } else if start > event.end + thresholds.gap {
                    closed += close(kind)
                }
            } else if confidence >= thresholds.enter {
                open[kind] = Open(start: start, end: end, confidence: confidence)
            }
        }
        return closed
    }

    /// Termine l'analyse et renvoie les événements encore ouverts.
    public mutating func finish() -> [TranscriptAnnotation] {
        open.keys.sorted { $0.rawValue < $1.rawValue }.flatMap { close($0) }
    }

    private mutating func close(_ kind: TranscriptAnnotation.Kind) -> [TranscriptAnnotation] {
        guard let event = open.removeValue(forKey: kind), event.end - event.start >= thresholds.minimumDuration else { return [] }
        return [TranscriptAnnotation(kind: kind, source: .soundAnalysis, start: event.start, end: event.end, confidence: event.confidence)]
    }
}

/// Repère les **longs silences** et les passages au **volume nettement plus élevé** que le reste de la
/// réunion, à partir du niveau de chaque bloc audio (`AudioChunk.level`). Mesure physique, sans modèle.
///
/// « Volume élevé » est un fait mesuré (quelqu'un parle fort, ou près du micro), pas une humeur.
public struct LevelAnalyzer: Sendable {
    public struct Thresholds: Sendable {
        /// Niveau normalisé (0…1, voir `AudioChunk.level`) sous lequel on considère qu'il y a silence (≈ -47 dB).
        public var silenceLevel: Float = 0.15
        public var minimumSilence: TimeInterval = 6
        /// Écart au niveau habituel de la parole pour parler de volume élevé (≈ +10 dB).
        public var raisedMargin: Float = 0.18
        public var minimumRaised: TimeInterval = 1.0
        /// Durée de parole à observer avant de juger du volume (le niveau habituel n'est pas encore connu).
        public var warmUp: TimeInterval = 10
        /// Constante de temps de la moyenne glissante du niveau de parole.
        public var averagingTime: TimeInterval = 30

        public init() {}
    }

    public var thresholds: Thresholds
    private var speechTime: TimeInterval = 0
    private var average: Float = 0
    private var silenceStart: TimeInterval?
    private var raisedStart: TimeInterval?
    private var raisedPeak: Float = 0
    private var lastEnd: TimeInterval = 0

    public init(thresholds: Thresholds = Thresholds()) {
        self.thresholds = thresholds
    }

    /// Ajoute le niveau d'un bloc audio ; renvoie les événements terminés.
    public mutating func add(level: Float, time: TimeInterval, duration: TimeInterval) -> [TranscriptAnnotation] {
        var closed: [TranscriptAnnotation] = []
        let end = time + duration
        defer { lastEnd = end }

        // Silence
        if level < thresholds.silenceLevel {
            if silenceStart == nil { silenceStart = time }
        } else if let start = silenceStart {
            closed += closeSilence(start: start, end: time)
            silenceStart = nil
        }

        // Volume élevé, par rapport au niveau habituel de la parole
        let isSpeech = level >= thresholds.silenceLevel
        let isRaised = isSpeech && speechTime >= thresholds.warmUp && level > average + thresholds.raisedMargin
        if isRaised {
            if raisedStart == nil { raisedStart = time; raisedPeak = 0 }
            raisedPeak = max(raisedPeak, level - average)
        } else if let start = raisedStart {
            closed += closeRaised(start: start, end: time)
            raisedStart = nil
        }
        // Les passages forts ne déplacent pas la référence : sinon un long éclat finirait par sembler normal.
        if isSpeech && !isRaised {
            let weight = Float(min(1, duration / thresholds.averagingTime))
            average = speechTime == 0 ? level : average + (level - average) * max(weight, 0.02)
            speechTime += duration
        }
        return closed
    }

    public mutating func finish() -> [TranscriptAnnotation] {
        var closed: [TranscriptAnnotation] = []
        if let start = silenceStart { closed += closeSilence(start: start, end: lastEnd) }
        if let start = raisedStart { closed += closeRaised(start: start, end: lastEnd) }
        silenceStart = nil
        raisedStart = nil
        return closed
    }

    private func closeSilence(start: TimeInterval, end: TimeInterval) -> [TranscriptAnnotation] {
        guard end - start >= thresholds.minimumSilence else { return [] }
        return [TranscriptAnnotation(kind: .longSilence, source: .audioLevel, start: start, end: end)]
    }

    private func closeRaised(start: TimeInterval, end: TimeInterval) -> [TranscriptAnnotation] {
        guard end - start >= thresholds.minimumRaised else { return [] }
        let confidence = Double(min(1, raisedPeak / (thresholds.raisedMargin * 2)))
        return [TranscriptAnnotation(kind: .raisedVoice, source: .audioLevel, start: start, end: end, confidence: confidence)]
    }
}
