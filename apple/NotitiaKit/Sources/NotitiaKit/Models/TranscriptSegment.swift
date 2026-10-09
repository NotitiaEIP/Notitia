import Foundation

/// Un segment de transcription horodaté.
public struct TranscriptSegment: Identifiable, Codable, Hashable, Sendable {
    public var id: UUID
    public var text: String
    /// Début et fin en secondes depuis le début de l'enregistrement.
    public var start: TimeInterval
    public var end: TimeInterval
    public var speaker: String?
    /// `false` tant que le moteur peut encore réviser ce segment (résultat provisoire).
    public var isFinal: Bool

    public init(
        id: UUID = UUID(),
        text: String,
        start: TimeInterval,
        end: TimeInterval,
        speaker: String? = nil,
        isFinal: Bool = true
    ) {
        self.id = id
        self.text = text
        self.start = start
        self.end = end
        self.speaker = speaker
        self.isFinal = isFinal
    }

    public static func formatTimestamp(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded(.down)))
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, s)
            : String(format: "%02d:%02d", m, s)
    }
}

public extension Array where Element == TranscriptSegment {
    var fullText: String {
        filter(\.isFinal).map(\.text).joined(separator: " ")
    }

    func encoded() -> Data {
        (try? JSONEncoder().encode(self)) ?? Data()
    }

    static func decode(_ data: Data?) -> [TranscriptSegment] {
        guard let data, !data.isEmpty else { return [] }
        return (try? JSONDecoder().decode([TranscriptSegment].self, from: data)) ?? []
    }
}
