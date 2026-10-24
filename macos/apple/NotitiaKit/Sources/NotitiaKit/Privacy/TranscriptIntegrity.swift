import CryptoKit
import Foundation

/// Empreinte de la transcription d'origine, pour repérer une modification après l'enregistrement.
///
/// L'empreinte couvre ce qui a été **capté** : les segments de texte et les annotations observées
/// (sons, volume, silences). Elle ne couvre pas ce qui est **interprété** après coup (ton des passages)
/// ni le fait de masquer une annotation, qui ne modifie pas l'original.
///
/// Limite, à ne pas présenter autrement : l'empreinte est calculée et stockée sur l'appareil. Elle
/// **détecte** une modification de la base, mais ce n'est **pas une preuve** opposable à un tiers :
/// le propriétaire de l'appareil peut tout recalculer.
public enum TranscriptIntegrity {
    public enum Status: Equatable, Sendable {
        /// Enregistrement antérieur à cette fonctionnalité, ou en cours.
        case notSealed
        case intact
        case modified
    }

    /// Version du format canonique, incluse dans l'empreinte.
    public static let formatVersion = 1

    /// Empreinte SHA-256 en hexadécimal, préfixée par la version (`v1:…`).
    public static func fingerprint(segments: [TranscriptSegment], annotations: [TranscriptAnnotation]) -> String {
        let digest = SHA256.hash(data: Data(canonical(segments: segments, annotations: annotations).utf8))
        return "v\(formatVersion):" + digest.map { String(format: "%02x", $0) }.joined()
    }

    public static func status(expected: String?, segments: [TranscriptSegment], annotations: [TranscriptAnnotation]) -> Status {
        guard let expected else { return .notSealed }
        return fingerprint(segments: segments, annotations: annotations) == expected ? .intact : .modified
    }

    /// Représentation canonique, portable d'une plateforme à l'autre :
    /// chaque champ est écrit `<longueur en octets UTF-8>:<valeur>;` (aucune ambiguïté possible),
    /// les temps en millisecondes entières, segments dans l'ordre puis annotations observées triées.
    static func canonical(segments: [TranscriptSegment], annotations: [TranscriptAnnotation]) -> String {
        func field(_ value: String) -> String { "\(value.utf8.count):\(value);" }
        func millis(_ seconds: TimeInterval) -> String { String(Int((seconds * 1000).rounded())) }

        var output = field("notitia-transcript-v\(formatVersion)")
        for segment in segments where segment.isFinal && !segment.text.isEmpty {
            output += field("S") + field(millis(segment.start)) + field(millis(segment.end))
                + field(segment.speaker ?? "") + field(segment.text)
        }
        let observed = annotations
            .filter(\.kind.isObserved)
            .sorted { ($0.start, $0.end, $0.kind.rawValue) < ($1.start, $1.end, $1.kind.rawValue) }
        for annotation in observed {
            output += field("A") + field(annotation.kind.rawValue) + field(annotation.source.rawValue)
                + field(millis(annotation.start)) + field(millis(annotation.end))
        }
        return output
    }
}
