import Foundation

/// Règles de conservation des données (RGPD : limitation de la durée de conservation).
public struct RetentionPolicy: Equatable, Sendable {
    /// Durée de conservation de l'audio, en jours. `nil` = conserver jusqu'à suppression manuelle.
    public var audioRetentionDays: Int?
    /// Durée de séjour dans la corbeille avant suppression définitive.
    public var trashRetentionDays: Int

    public static let defaultTrashRetentionDays = 30
    public static let audioRetentionChoices: [Int?] = [nil, 7, 30, 90, 365]

    public init(audioRetentionDays: Int? = nil, trashRetentionDays: Int = defaultTrashRetentionDays) {
        self.audioRetentionDays = audioRetentionDays
        self.trashRetentionDays = trashRetentionDays
    }

    public func isAudioExpired(recordedAt: Date, now: Date = .now) -> Bool {
        guard let days = audioRetentionDays else { return false }
        return now.timeIntervalSince(recordedAt) >= Double(days) * 86_400
    }

    public func isTrashExpired(trashedAt: Date, now: Date = .now) -> Bool {
        now.timeIntervalSince(trashedAt) >= Double(trashRetentionDays) * 86_400
    }

    public static func label(forAudioRetention days: Int?) -> String {
        switch days {
        case nil: "Jusqu'à suppression manuelle"
        case 7: "7 jours"
        case 30: "30 jours"
        case 90: "3 mois"
        case 365: "1 an"
        case let value?: "\(value) jours"
        }
    }
}
