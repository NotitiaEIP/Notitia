import AppKit
import NotitiaKit
import SwiftUI

/// Jetons de design de Notitia.
///
/// Identité « Natif Apple » : pas de couleur de marque, on s'appuie sur la couleur
/// d'accent choisie par l'utilisateur, les couleurs système (qui s'adaptent au mode sombre),
/// SF Pro et SF Symbols. Voir docs/DESIGN.md.
enum Spacing {
    static let xxs: CGFloat = 2
    static let xs: CGFloat = 4
    static let s: CGFloat = 8
    static let m: CGFloat = 12
    static let l: CGFloat = 16
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 40
}

enum Radius {
    static let s: CGFloat = 6
    static let m: CGFloat = 10
    static let l: CGFloat = 14
}

enum Metrics {
    /// Largeur de lecture confortable du contenu d'une note.
    static let readableWidth: CGFloat = 760
    /// Largeur d'un niveau d'indentation de bloc.
    static let indentWidth: CGFloat = 24
    /// Gouttière à gauche des blocs (poignée de déplacement).
    static let gutterWidth: CGFloat = 28
}

extension NoteColor {
    /// Couleur système correspondante (adaptée aux modes clair et sombre).
    var nsColor: NSColor {
        switch self {
        case .red: .systemRed
        case .orange: .systemOrange
        case .yellow: .systemYellow
        case .green: .systemGreen
        case .mint: .systemMint
        case .teal: .systemTeal
        case .cyan: .systemCyan
        case .blue: .systemBlue
        case .indigo: .systemIndigo
        case .purple: .systemPurple
        case .pink: .systemPink
        case .brown: .systemBrown
        case .gray: .systemGray
        }
    }

    var color: Color { Color(nsColor: nsColor) }
}

/// Icônes proposées pour les dossiers.
enum FolderSymbols {
    static let all = [
        "folder", "graduationcap", "book.closed", "character.book.closed", "globe.europe.africa",
        "function", "flask", "atom", "leaf", "building.columns", "paintpalette", "music.note",
        "laptopcomputer", "chevron.left.forwardslash.chevron.right", "chart.bar", "briefcase",
        "person.2", "person.crop.circle", "lightbulb", "star", "heart", "tray", "archivebox", "airplane",
    ]
}

extension Date {
    /// « Aujourd'hui 14:02 », « Hier », « 3 oct. »…
    var noteListFormatted: String {
        let calendar = Calendar.current
        if calendar.isDateInToday(self) { return formatted(date: .omitted, time: .shortened) }
        if calendar.isDateInYesterday(self) { return String(localized: "Hier") }
        if let days = calendar.dateComponents([.day], from: self, to: .now).day, days < 7 {
            return formatted(.dateTime.weekday(.wide))
        }
        return formatted(date: .abbreviated, time: .omitted)
    }
}
