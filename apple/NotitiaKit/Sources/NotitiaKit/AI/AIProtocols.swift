import Foundation

/// Description d'un moteur (transcription ou IA) affichée dans les réglages.
public struct EngineDescriptor: Identifiable, Hashable, Sendable {
    public var id: String
    public var displayName: String
    public var detail: String
    /// Le moteur envoie des données sur le réseau. Les moteurs réseau sont toujours
    /// désactivés par défaut et exigent un consentement explicite (voir docs/PRIVACY.md).
    public var requiresNetwork: Bool
    /// Disponible dans cette version de l'application.
    public var isAvailable: Bool

    public init(id: String, displayName: String, detail: String, requiresNetwork: Bool, isAvailable: Bool) {
        self.id = id
        self.displayName = displayName
        self.detail = detail
        self.requiresNetwork = requiresNetwork
        self.isAvailable = isAvailable
    }
}

/// Bloc d'échantillons audio mono, copiés hors du thread audio.
public struct AudioChunk: Sendable {
    public var samples: [Float]
    public var sampleRate: Double
    /// Position du premier échantillon, en secondes depuis le début de l'enregistrement (pauses exclues).
    /// Toutes les analyses s'alignent sur cette horloge, même si un bloc est perdu en route.
    public var time: TimeInterval

    public init(samples: [Float], sampleRate: Double, time: TimeInterval = 0) {
        self.samples = samples
        self.sampleRate = sampleRate
        self.time = time
    }

    public var duration: TimeInterval { sampleRate > 0 ? Double(samples.count) / sampleRate : 0 }

    /// Niveau RMS normalisé entre 0 et 1 (pour l'indicateur de niveau).
    public var level: Float {
        guard !samples.isEmpty else { return 0 }
        let sum = samples.reduce(Float(0)) { $0 + $1 * $1 }
        let rms = (sum / Float(samples.count)).squareRoot()
        let db = 20 * log10(max(rms, 1e-7))
        return min(1, max(0, (db + 55) / 55))
    }
}

/// Moteur de transcription : reçoit le flux audio et produit des segments.
///
/// Un même segment (même `id`) peut être émis plusieurs fois : provisoire (`isFinal == false`)
/// puis définitif. Le consommateur remplace le segment à chaque émission.
public protocol TranscriptionEngine: Sendable {
    var descriptor: EngineDescriptor { get }
    func transcribe(audio: AsyncStream<AudioChunk>, locale: Locale) -> AsyncThrowingStream<TranscriptSegment, any Error>
}

/// Détecteur d'annotations : reçoit le même flux audio que le moteur de transcription et produit des
/// annotations horodatées sur la même horloge (`AudioChunk.time`). Il décrit ce qu'on entend,
/// jamais l'état d'une personne (voir `TranscriptAnnotation`).
public protocol AnnotationDetector: Sendable {
    func detect(audio: AsyncStream<AudioChunk>) -> AsyncThrowingStream<TranscriptAnnotation, any Error>
}

/// Texte source d'une action IA : contenu d'une note ou transcription.
public struct AISource: Sendable {
    public var title: String
    public var text: String
    public var localeIdentifier: String

    public init(title: String, text: String, localeIdentifier: String = "fr_FR") {
        self.title = title
        self.text = text
        self.localeIdentifier = localeIdentifier
    }
}

public enum AIError: LocalizedError {
    case emptySource
    case engineUnavailable(String)

    public var errorDescription: String? {
        switch self {
        case .emptySource: "La note ne contient pas encore assez de texte."
        case .engineUnavailable(let name): "Le moteur « \(name) » n'est pas disponible."
        }
    }
}

/// Actions IA sur les notes. Toutes renvoient des blocs ou des structures éditables :
/// l'utilisateur garde la main sur le résultat.
public protocol NoteAIService: Sendable {
    var descriptor: EngineDescriptor { get }
    /// Résumé. Avec `focus`, synthèse organisée autour des mots-clés de l'utilisateur.
    func summarize(_ source: AISource, focus: [String]) async throws -> [Block]
    func keywords(_ source: AISource, limit: Int) async throws -> [String]
    func mindMap(_ source: AISource, focus: [String]) async throws -> MindMapDocument
}

/// Catalogue des moteurs connus. Seuls les moteurs hors ligne sont disponibles ;
/// les autres sont listés pour rendre la feuille de route visible dans les réglages.
public enum EngineCatalog {
    /// Moteur utilisé quand l'utilisateur n'a rien choisi : la vraie transcription d'Apple.
    public static let defaultTranscriptionEngineID = AppleSpeechTranscriptionEngine.descriptorValue.id

    public static let transcriptionEngines: [EngineDescriptor] = [
        AppleSpeechTranscriptionEngine.descriptorValue,
        DemoTranscriptionEngine.descriptorValue,
        EngineDescriptor(
            id: "cloud.deepgram",
            displayName: "Deepgram — cloud",
            detail: "Nécessite Internet et un consentement explicite. Non activé.",
            requiresNetwork: true,
            isAvailable: false
        ),
    ]

    public static let aiServices: [EngineDescriptor] = [
        LocalExtractiveAIService.descriptorValue,
        EngineDescriptor(
            id: "apple.foundationmodels",
            displayName: "Apple Intelligence — sur l'appareil",
            detail: "Foundation Models (macOS 26). Hors ligne. Utilisé pour le ton des passages ; résumés reformulés bientôt disponibles.",
            requiresNetwork: false,
            isAvailable: false
        ),
        EngineDescriptor(
            id: "cloud.llm",
            displayName: "Modèle de langage — cloud",
            detail: "Nécessite Internet et un consentement explicite. Non activé.",
            requiresNetwork: true,
            isAvailable: false
        ),
    ]

    /// Moteur correspondant à `id` ; un identifiant inconnu ou vide donne le moteur par défaut.
    public static func transcriptionEngine(id: String, contextualStrings: [String] = []) -> any TranscriptionEngine {
        switch id {
        case DemoTranscriptionEngine.descriptorValue.id:
            DemoTranscriptionEngine()
        default:
            AppleSpeechTranscriptionEngine(contextualStrings: contextualStrings)
        }
    }

    /// Seul moteur de synthèse disponible pour l'instant : l'`id` est ignoré.
    /// Brancher Foundation Models ici quand les résumés reformulés seront prêts.
    public static func aiService(id: String) -> any NoteAIService {
        LocalExtractiveAIService()
    }
}
