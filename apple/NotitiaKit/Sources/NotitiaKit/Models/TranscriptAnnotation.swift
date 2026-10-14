import Foundation

/// Annotation horodatée d'une transcription : ce qu'on **entend** (rires, applaudissements, volume, silence)
/// ou le **ton d'un passage de texte**.
///
/// Règle de conception : une annotation décrit un moment ou un passage, **jamais l'état d'une personne**.
/// Notitia ne déduit pas d'émotions à partir de la voix (AI Act, art. 5 §1 f, voir docs/PRIVACY.md),
/// et n'attribue pas d'annotation à un locuteur.
public struct TranscriptAnnotation: Identifiable, Codable, Hashable, Sendable {
    public enum Kind: String, Codable, CaseIterable, Sendable {
        case laughter
        case applause
        /// Volume nettement plus fort que le reste de la réunion (fait mesuré, pas une humeur).
        case raisedVoice
        case longSilence
        /// Ton d'un passage, déduit du **texte** uniquement.
        case tone
        /// Type ajouté par une version plus récente : lu sans erreur, affiché de façon générique.
        case unknown

        public init(from decoder: any Decoder) throws {
            let raw = try decoder.singleValueContainer().decode(String.self)
            self = Kind(rawValue: raw) ?? .unknown
        }

        public var displayName: String {
            switch self {
            case .laughter: "rires"
            case .applause: "applaudissements"
            case .raisedVoice: "volume élevé"
            case .longSilence: "silence"
            case .tone: "ton du passage"
            case .unknown: "annotation"
            }
        }

        /// Ce qui a été capté (sons, volume) par opposition à ce qui est interprété (ton du texte).
        public var isObserved: Bool { self != .tone }
    }

    /// Origine de l'annotation, affichée pour qu'une erreur automatique ne passe jamais pour un fait.
    public enum Source: String, Codable, CaseIterable, Sendable {
        case soundAnalysis
        case audioLevel
        case naturalLanguage
        case foundationModels
        case unknown

        public init(from decoder: any Decoder) throws {
            let raw = try decoder.singleValueContainer().decode(String.self)
            self = Source(rawValue: raw) ?? .unknown
        }

        public var displayName: String {
            switch self {
            case .soundAnalysis: "analyse des sons"
            case .audioLevel: "mesure du volume"
            case .naturalLanguage: "analyse du texte"
            case .foundationModels: "modèle de langage sur l'appareil"
            case .unknown: "automatique"
            }
        }
    }

    public var id: UUID
    public var kind: Kind
    public var source: Source
    /// Début et fin en secondes depuis le début de l'enregistrement (même horloge que les segments).
    public var start: TimeInterval
    public var end: TimeInterval
    /// Confiance entre 0 et 1.
    public var confidence: Double
    /// Libellé court et factuel (ton d'un passage), par exemple « échange tendu sur le budget ».
    public var label: String?
    /// Polarité du ton, de -1 (négatif) à 1 (positif).
    public var polarity: Double?
    /// Masquée par l'utilisateur : l'original reste conservé (voir `TranscriptIntegrity`).
    public var isHidden: Bool

    public init(
        id: UUID = UUID(),
        kind: Kind,
        source: Source,
        start: TimeInterval,
        end: TimeInterval,
        confidence: Double = 1,
        label: String? = nil,
        polarity: Double? = nil,
        isHidden: Bool = false
    ) {
        self.id = id
        self.kind = kind
        self.source = source
        self.start = start
        self.end = max(start, end)
        self.confidence = confidence
        self.label = label
        self.polarity = polarity
        self.isHidden = isHidden
    }

    public var duration: TimeInterval { end - start }

    /// Texte affiché et exporté, par exemple « rires » ou « ton du passage : échange tendu sur le budget ».
    public var text: String {
        guard kind == .tone else { return kind.displayName }
        if let label, !label.isEmpty { return label }
        guard let polarity else { return kind.displayName }
        return polarity >= 0 ? "passage au ton positif" : "passage au ton négatif"
    }

    /// Texte complet hors de l'interface (citations, Markdown) : précise qu'il s'agit du ton du passage.
    public var exportText: String {
        kind == .tone && text != kind.displayName ? "ton du passage : \(text)" : text
    }
}

public extension Array where Element == TranscriptAnnotation {
    func encoded() -> Data {
        (try? JSONEncoder().encode(self)) ?? Data()
    }

    static func decode(_ data: Data?) -> [TranscriptAnnotation] {
        guard let data, !data.isEmpty else { return [] }
        return (try? JSONDecoder().decode([TranscriptAnnotation].self, from: data)) ?? []
    }

    /// Fusionne les annotations **du même type** qui se chevauchent ou se touchent (à `gap` secondes près),
    /// par exemple un cri repéré à la fois par l'analyse des sons et par la mesure du volume.
    func mergingOverlaps(gap: TimeInterval = 0.5) -> [TranscriptAnnotation] {
        var merged: [TranscriptAnnotation] = []
        for kind in TranscriptAnnotation.Kind.allCases {
            let sameKind = filter { $0.kind == kind }.sorted { $0.start < $1.start }
            // Le ton porte un libellé propre à chaque passage : on ne le fusionne pas.
            guard kind != .tone else { merged += sameKind; continue }
            var current: TranscriptAnnotation?
            for annotation in sameKind {
                if var open = current, annotation.start <= open.end + gap {
                    open.end = Swift.max(open.end, annotation.end)
                    open.confidence = Swift.max(open.confidence, annotation.confidence)
                    current = open
                } else {
                    if let open = current { merged.append(open) }
                    current = annotation
                }
            }
            if let open = current { merged.append(open) }
        }
        return merged.sorted { ($0.start, $0.kind.rawValue) < ($1.start, $1.kind.rawValue) }
    }
}
