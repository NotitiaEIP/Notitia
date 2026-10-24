import Foundation

/// Palette sémantique partagée par les dossiers, les tags et le texte.
///
/// On stocke un nom de couleur et non une valeur hexadécimale : chaque plateforme
/// le traduit vers sa couleur système, qui s'adapte aux modes clair et sombre.
public enum NoteColor: String, Codable, CaseIterable, Sendable, Identifiable {
    case red, orange, yellow, green, mint, teal, cyan, blue, indigo, purple, pink, brown, gray

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .red: "Rouge"
        case .orange: "Orange"
        case .yellow: "Jaune"
        case .green: "Vert"
        case .mint: "Menthe"
        case .teal: "Sarcelle"
        case .cyan: "Cyan"
        case .blue: "Bleu"
        case .indigo: "Indigo"
        case .purple: "Violet"
        case .pink: "Rose"
        case .brown: "Brun"
        case .gray: "Gris"
        }
    }
}
