import Foundation
import NotitiaKit

/// Clés des préférences utilisateur (`@AppStorage`).
enum Preferences {
    enum Keys {
        static let hasCompletedWelcome = "hasCompletedWelcome"
        static let askConsentBeforeRecording = "askConsentBeforeRecording"
        static let audioRetentionDays = "audioRetentionDays" // 0 = conserver
        static let transcriptionLocale = "transcriptionLocale"
        static let transcriptionEngine = "transcriptionEngine"
        static let aiService = "aiService"
        static let noteSortOrder = "noteSortOrder"
        /// Annoter les sons (rires, applaudissements) et le volume pendant l'enregistrement. Activé par défaut.
        static let annotateSounds = "annotateSounds"
        /// Analyser le ton des passages (texte seulement) après l'enregistrement. Désactivé par défaut (opt-in).
        static let analyzeTone = "analyzeTone"
    }

    static var annotateSounds: Bool {
        UserDefaults.standard.object(forKey: Keys.annotateSounds) as? Bool ?? true
    }

    static var analyzeTone: Bool {
        UserDefaults.standard.bool(forKey: Keys.analyzeTone)
    }

    static var transcriptionEngineID: String {
        UserDefaults.standard.string(forKey: Keys.transcriptionEngine) ?? EngineCatalog.defaultTranscriptionEngineID
    }

    static var retentionPolicy: RetentionPolicy {
        let days = UserDefaults.standard.integer(forKey: Keys.audioRetentionDays)
        return RetentionPolicy(audioRetentionDays: days > 0 ? days : nil)
    }

    static var transcriptionLocale: String {
        UserDefaults.standard.string(forKey: Keys.transcriptionLocale) ?? "fr_FR"
    }

    static let transcriptionLocales: [(id: String, name: String)] = [
        ("fr_FR", "Français"),
        ("en_US", "Anglais (États-Unis)"),
        ("en_GB", "Anglais (Royaume-Uni)"),
        ("es_ES", "Espagnol"),
        ("de_DE", "Allemand"),
        ("zh_CN", "Chinois (mandarin)"),
    ]
}

/// Ordre de tri de la liste des notes.
enum NoteSortOrder: String, CaseIterable, Identifiable {
    case modified, created, title

    var id: String { rawValue }

    var label: String {
        switch self {
        case .modified: String(localized: "Date de modification")
        case .created: String(localized: "Date de création")
        case .title: String(localized: "Titre")
        }
    }
}
