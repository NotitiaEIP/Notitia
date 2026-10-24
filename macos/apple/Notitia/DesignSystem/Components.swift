import NotitiaKit
import SwiftUI

/// Pastille de couleur sélectionnable (dossiers, tags, texte).
struct ColorSwatch: View {
    let color: NoteColor
    var isSelected = false
    var size: CGFloat = 18

    var body: some View {
        Circle()
            .fill(color.color.gradient)
            .frame(width: size, height: size)
            .overlay {
                if isSelected {
                    Circle().strokeBorder(.primary.opacity(0.8), lineWidth: 2).padding(-4)
                }
            }
            .accessibilityLabel(color.displayName)
    }
}

/// Étiquette de tag compacte.
struct TagChip: View {
    let name: String
    let color: NoteColor
    var onRemove: (() -> Void)?

    var body: some View {
        HStack(spacing: Spacing.xs) {
            Circle().fill(color.color).frame(width: 7, height: 7)
            Text(name).font(.caption)
            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark").font(.system(size: 8, weight: .bold))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel("Retirer \(name)")
            }
        }
        .padding(.horizontal, Spacing.s)
        .padding(.vertical, 3)
        .background(.quaternary, in: Capsule())
    }
}

/// Ligne « libellé : valeur » de l'inspecteur.
struct InfoRow: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        LabeledContent(label) {
            Text(value).foregroundStyle(.secondary).monospacedDigit()
        }
        .font(.callout)
    }
}

/// Mise en page en flux (les tags passent à la ligne).
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineHeight: CGFloat = 0, maxX: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += lineHeight + spacing
                lineHeight = 0
            }
            x += size.width + spacing
            maxX = max(maxX, x - spacing)
            lineHeight = max(lineHeight, size.height)
        }
        // On occupe toute la largeur proposée : une largeur qui dépendrait du contenu
        // ferait osciller la mise en page de la colonne (inspecteur).
        let resultWidth = width.isFinite ? width : maxX
        return CGSize(width: resultWidth, height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += lineHeight + spacing
                lineHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}
