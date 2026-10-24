import NotitiaKit
import SwiftUI

/// Tutoriel de présentation : au premier lancement, puis depuis le menu Aide.
struct TutorialView: View {
    enum Mode {
        /// Premier lancement : la dernière page propose de démarrer avec ou sans exemples.
        case firstLaunch
        /// Revu depuis le menu Aide (feuille).
        case replay
    }

    enum StartChoice {
        case samples, empty
    }

    let mode: Mode
    /// Fin du tutoriel. Le choix n'est renseigné qu'au premier lancement.
    var onFinish: (StartChoice?) -> Void
    var onOpenHelp: (() -> Void)?

    @State private var index: Int
    @State private var choice = StartChoice.samples
    @State private var isMovingForward = true

    init(mode: Mode, initialPage: Int = 0, onFinish: @escaping (StartChoice?) -> Void, onOpenHelp: (() -> Void)? = nil) {
        self.mode = mode
        self.onFinish = onFinish
        self.onOpenHelp = onOpenHelp
        _index = State(initialValue: initialPage)
    }

    private var pages: [TutorialPage] {
        mode == .firstLaunch ? TutorialPage.allCases : TutorialPage.allCases.filter { $0 != .start }
    }

    private var page: TutorialPage { pages[index] }
    private var isLast: Bool { index == pages.count - 1 }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                content(for: page)
                    .id(page)
                    .transition(.asymmetric(
                        insertion: .offset(x: isMovingForward ? 40 : -40).combined(with: .opacity),
                        removal: .offset(x: isMovingForward ? -40 : 40).combined(with: .opacity)
                    ))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.horizontal, Spacing.xxl)
            .padding(.top, Spacing.xxl)

            navigationBar
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .focusable()
        .focusEffectDisabled()
        .onKeyPress(.rightArrow) { next(); return .handled }
        .onKeyPress(.leftArrow) { back(); return .handled }
    }

    // MARK: Navigation

    private var navigationBar: some View {
        ZStack {
            // Points de pagination toujours centrés, quels que soient les boutons affichés.
            HStack(spacing: 7) {
                ForEach(pages.indices, id: \.self) { dot in
                    Circle()
                        .fill(dot == index ? Color.accentColor : Color.secondary.opacity(0.3))
                        .frame(width: 7, height: 7)
                        .onTapGesture { go(to: dot) }
                        .accessibilityLabel("Page \(dot + 1) sur \(pages.count)")
                }
            }
            HStack {
                if !isLast {
                    Button("Passer le tutoriel") { finish() }
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                HStack(spacing: Spacing.s) {
                    if index > 0 {
                        Button("Retour", action: back)
                            .controlSize(.large)
                    }
                    if isLast, mode == .replay, let onOpenHelp {
                        Button("Ouvrir la note d'aide") {
                            onFinish(nil)
                            onOpenHelp()
                        }
                        .controlSize(.large)
                    }
                    Button(isLast ? (mode == .firstLaunch ? "Commencer" : "Terminer") : "Continuer") {
                        isLast ? finish() : next()
                    }
                    .buttonStyle(.glassProminent)
                    .controlSize(.large)
                    .keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(.horizontal, Spacing.xxl)
        .padding(.vertical, Spacing.xl)
    }

    private func next() { go(to: min(index + 1, pages.count - 1)) }
    private func back() { go(to: max(index - 1, 0)) }

    private func go(to newIndex: Int) {
        guard newIndex != index else { return }
        isMovingForward = newIndex > index
        withAnimation(.smooth(duration: 0.35)) { index = newIndex }
    }

    private func finish() {
        onFinish(mode == .firstLaunch ? choice : nil)
    }

    // MARK: Pages

    @ViewBuilder
    private func content(for page: TutorialPage) -> some View {
        switch page {
        case .welcome:
            WelcomePage()
        case .start:
            StartPage(choice: $choice)
        default:
            FeaturePage(page: page)
        }
    }
}

/// Pages du tutoriel.
enum TutorialPage: CaseIterable, Hashable {
    case welcome, organize, write, record, intelligence, privacy, start

    var symbol: String {
        switch self {
        case .welcome: "hand.wave"
        case .organize: "folder"
        case .write: "text.badge.plus"
        case .record: "waveform"
        case .intelligence: "point.3.connected.trianglepath.dotted"
        case .privacy: "lock.shield"
        case .start: "flag.checkered"
        }
    }

    var title: LocalizedStringKey {
        switch self {
        case .welcome: "Bienvenue dans Notitia"
        case .organize: "Rangez vos notes comme vous le souhaitez"
        case .write: "Écrivez avec style, sans effort"
        case .record: "Enregistrez vos réunions et vos cours"
        case .intelligence: "Résumez et visualisez"
        case .privacy: "Vos données restent chez vous"
        case .start: "Par où commencer ?"
        }
    }

    var message: LocalizedStringKey {
        switch self {
        case .welcome: "Vos notes, vos cours et vos réunions. Sur votre Mac, et nulle part ailleurs."
        case .organize: "Créez des dossiers et des sous-dossiers pour chaque matière, projet ou équipe. Glissez une note sur un dossier pour la ranger."
        case .write: "Chaque paragraphe est un bloc : titre, liste, tâche, citation, encadré, code ou image. Tapez « / » pour choisir, ou utilisez les raccourcis Markdown."
        case .record: "Notitia transcrit la conversation en direct, sur ce Mac, et signale rires, applaudissements et silences. Cliquez ensuite sur un passage pour le réécouter."
        case .intelligence: "Transformez une transcription en résumé structuré ou en carte mentale. Chaque idée renvoie à la phrase dont elle vient."
        case .privacy: "Notitia fonctionne entièrement hors ligne. Aucun compte, aucune collecte : vous pouvez tout exporter ou tout effacer."
        case .start: ""
        }
    }

    var tips: [(symbol: String, text: LocalizedStringKey)] {
        switch self {
        case .organize: [
            ("folder.badge.plus", "⇧⌘N crée un dossier ; clic droit pour un sous-dossier, une icône ou une couleur"),
            ("pin", "Épinglez les notes importantes, ajoutez des tags pour les regrouper"),
            ("magnifyingglass", "⌥⌘F cherche dans toutes les notes, ⌘K ouvre une note par son nom"),
        ]
        case .write: [
            ("slash.circle", "« / » dans un bloc vide ouvre le menu des blocs"),
            ("number", "« # », « - », « 1. », « [] », « > » transforment le bloc pendant que vous tapez"),
            ("bold.italic.underline", "⌘B, ⌘I, ⌘U, couleurs et surlignage depuis la barre d'outils"),
        ]
        case .record: [
            ("mic", "⇧⌘R démarre et arrête l'enregistrement, ⇧⌘P le met en pause"),
            ("person.2.wave.2", "Un rappel vous invite à informer les participants"),
            ("play.circle", "Chaque phrase est horodatée : un clic relance l'audio à cet instant"),
        ]
        case .intelligence: [
            ("text.badge.star", "Résumé : points clés, décisions, actions et questions"),
            ("key", "Mots-clés, et synthèse réorganisée autour de vos propres mots-clés"),
            ("sidebar.trailing", "Tout se trouve dans l'inspecteur (⌥⌘I), section Intelligence"),
        ]
        case .privacy: [
            ("wifi.slash", "L'application n'a pas l'autorisation d'accéder à Internet"),
            ("lock", "Les enregistrements sont chiffrés sur votre Mac"),
            ("square.and.arrow.up", "Réglages › Confidentialité : durée de conservation, export, effacement"),
        ]
        default: []
        }
    }
}

// MARK: - Mise en page des pages

private struct WelcomePage: View {
    var body: some View {
        VStack(spacing: Spacing.xl) {
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 128, height: 128)
                .shadow(color: .black.opacity(0.15), radius: 12, y: 6)
            VStack(spacing: Spacing.m) {
                Text(TutorialPage.welcome.title)
                    .font(.system(size: 40, weight: .bold))
                Text(TutorialPage.welcome.message)
                    .font(.title2)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            HStack(spacing: Spacing.xxl) {
                ForEach([TutorialPage.organize, .write, .record, .intelligence, .privacy], id: \.self) { page in
                    Image(systemName: page.symbol)
                        .font(.title2)
                        .foregroundStyle(.tint)
                }
            }
            .padding(.top, Spacing.l)
            Text("Quelques pages pour découvrir l'essentiel. Utilisez les flèches ← → ou le bouton Continuer.")
                .font(.callout)
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: 640)
    }
}

private struct FeaturePage: View {
    let page: TutorialPage

    var body: some View {
        HStack(alignment: .center, spacing: Spacing.xxl) {
            VStack(alignment: .leading, spacing: Spacing.l) {
                Image(systemName: page.symbol)
                    .font(.system(size: 30, weight: .medium))
                    .foregroundStyle(.tint)
                    .frame(width: 56, height: 56)
                    .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: Radius.l))
                Text(page.title)
                    .font(.system(size: 28, weight: .bold))
                    .fixedSize(horizontal: false, vertical: true)
                Text(page.message)
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                VStack(alignment: .leading, spacing: Spacing.m) {
                    ForEach(Array(page.tips.enumerated()), id: \.offset) { _, tip in
                        Label {
                            Text(tip.text).fixedSize(horizontal: false, vertical: true)
                        } icon: {
                            Image(systemName: tip.symbol).foregroundStyle(.tint).frame(width: 22)
                        }
                    }
                }
                .padding(.top, Spacing.s)
            }
            .frame(maxWidth: 380, alignment: .leading)

            illustration
                .frame(width: 440, height: 340)
                .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: Radius.l))
                .overlay(RoundedRectangle(cornerRadius: Radius.l).strokeBorder(.separator))
                .clipShape(RoundedRectangle(cornerRadius: Radius.l))
                .shadow(color: .black.opacity(0.12), radius: 20, y: 8)
                .accessibilityHidden(true)
        }
        .frame(maxWidth: 900)
    }

    @ViewBuilder
    private var illustration: some View {
        switch page {
        case .organize: OrganizeIllustration()
        case .write: WriteIllustration()
        case .record: RecordIllustration()
        case .intelligence: IntelligenceIllustration()
        case .privacy: PrivacyIllustration()
        default: EmptyView()
        }
    }
}

private struct StartPage: View {
    @Binding var choice: TutorialView.StartChoice

    var body: some View {
        VStack(spacing: Spacing.xl) {
            VStack(spacing: Spacing.s) {
                Text(TutorialPage.start.title)
                    .font(.system(size: 32, weight: .bold))
                Text("Vous pourrez revoir ce tutoriel à tout moment avec le menu Aide.")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: Spacing.l) {
                ChoiceCard(
                    symbol: "sparkles",
                    title: "Découvrir avec des exemples",
                    detail: "Des cours de chinois, d'anglais et de maths, et une réunion déjà transcrite pour essayer le résumé et la carte mentale.",
                    isSelected: choice == .samples
                ) { choice = .samples }
                ChoiceCard(
                    symbol: "square.dashed",
                    title: "Commencer avec un carnet vide",
                    detail: "Partez de zéro et créez vos propres dossiers.",
                    isSelected: choice == .empty
                ) { choice = .empty }
            }
            .frame(maxWidth: 720)
            Label("Dans les deux cas, une note « Aide » épinglée détaille toutes les possibilités de l'éditeur.", systemImage: "questionmark.circle")
                .foregroundStyle(.secondary)
        }
    }
}

private struct ChoiceCard: View {
    let symbol: String
    let title: LocalizedStringKey
    let detail: LocalizedStringKey
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: Spacing.m) {
                HStack {
                    Image(systemName: symbol).font(.title).foregroundStyle(.tint)
                    Spacer()
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .font(.title2)
                        .foregroundStyle(isSelected ? Color.accentColor : .secondary)
                }
                Text(title).font(.title3.bold())
                Text(detail)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(Spacing.xl)
            .frame(maxWidth: .infinity, minHeight: 190, maxHeight: 220, alignment: .topLeading)
            .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: Radius.l))
            .overlay(
                RoundedRectangle(cornerRadius: Radius.l)
                    .strokeBorder(isSelected ? Color.accentColor : Color.secondary.opacity(0.25), lineWidth: isSelected ? 2 : 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: Radius.l))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

// MARK: - Illustrations (reproductions simplifiées de l'interface)

private struct OrganizeIllustration: View {
    private struct Row: Identifiable {
        let id = UUID()
        let symbol: String
        let name: String
        let color: Color
        var indent = 0
        var count: Int?
        var isSelected = false
        var isExpanded: Bool?
    }

    private let rows: [Row] = [
        Row(symbol: "graduationcap", name: "Cours", color: .indigo, isExpanded: true),
        Row(symbol: "character.book.closed", name: "Chinois", color: .red, indent: 1, count: 4, isSelected: true),
        Row(symbol: "globe.europe.africa", name: "Anglais", color: .blue, indent: 1, count: 2),
        Row(symbol: "function", name: "Maths", color: .green, indent: 1, count: 6),
        Row(symbol: "person.2", name: "Réunions", color: .orange, count: 3),
        Row(symbol: "briefcase", name: "Stage", color: .brown, count: 1, isExpanded: false),
    ]

    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                sidebarLabel("Toutes les notes", symbol: "note.text", count: 16)
                sidebarLabel("Épinglées", symbol: "pin", count: 2)
                Text("Dossiers").font(.caption.weight(.semibold)).foregroundStyle(.secondary).padding(.top, Spacing.s)
                ForEach(rows) { row in
                    HStack(spacing: 6) {
                        Image(systemName: row.isExpanded == true ? "chevron.down" : "chevron.right")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundStyle(.tertiary)
                            .opacity(row.isExpanded == nil ? 0 : 1)
                        Image(systemName: row.symbol).foregroundStyle(row.isSelected ? .white : row.color).frame(width: 18)
                        Text(row.name)
                        Spacer()
                        if let count = row.count { Text("\(count)").foregroundStyle(row.isSelected ? .white.opacity(0.8) : .secondary) }
                    }
                    .font(.callout)
                    .foregroundStyle(row.isSelected ? .white : .primary)
                    .padding(.leading, CGFloat(row.indent) * 16)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 4)
                    .background(row.isSelected ? Color.accentColor : .clear, in: RoundedRectangle(cornerRadius: Radius.s))
                }
                Text("Tags").font(.caption.weight(.semibold)).foregroundStyle(.secondary).padding(.top, Spacing.s)
                sidebarLabel("Révisions", symbol: "number", count: 5, color: .purple)
                Spacer()
            }
            .padding(Spacing.m)
            .frame(width: 210)
            .background(.quaternary.opacity(0.35))

            VStack(alignment: .leading, spacing: Spacing.s) {
                Text("Chinois").font(.headline)
                ForEach(["Les quatre tons", "Leçon 4 — la famille", "Caractères à revoir", "Vocabulaire du voyage"], id: \.self) { title in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.callout.weight(.semibold)).lineLimit(1)
                        RoundedRectangle(cornerRadius: 2).fill(.quaternary).frame(height: 6)
                        RoundedRectangle(cornerRadius: 2).fill(.quaternary).frame(width: 110, height: 6)
                    }
                    .padding(.vertical, 4)
                }
                Spacer()
            }
            .padding(Spacing.m)
        }
    }

    private func sidebarLabel(_ name: String, symbol: String, count: Int, color: Color = .accentColor) -> some View {
        HStack(spacing: 6) {
            Image(systemName: symbol).foregroundStyle(color).frame(width: 18)
            Text(name)
            Spacer()
            Text("\(count)").foregroundStyle(.secondary)
        }
        .font(.callout)
        .padding(.horizontal, 6)
        .padding(.vertical, 3)
    }
}

private struct WriteIllustration: View {
    private var highlightedTask: AttributedString {
        var highlighted = AttributedString("contrôle oral")
        highlighted.backgroundColor = Color.yellow.opacity(0.35)
        return AttributedString("Préparer le ") + highlighted + AttributedString(" de jeudi")
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(alignment: .leading, spacing: 7) {
                Text("Les quatre tons").font(.system(size: 22, weight: .bold))
                Text("Le mandarin est une langue \(Text("tonale").bold()) : le sens dépend de la mélodie.")
                    .font(.callout)
                Text("Les tons").font(.system(size: 16, weight: .semibold)).padding(.top, 4)
                ForEach(Array(["haut et plat : mā", "montant : má", "descendant puis montant : mǎ"].enumerated()), id: \.offset) { index, text in
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text("\(index + 1).").foregroundStyle(.secondary).monospacedDigit()
                        Text("\(Text("Ton \(index + 1)").bold()) — \(text)")
                    }
                    .font(.callout)
                }
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.square.fill").foregroundStyle(.tint)
                    Text("Écouter la leçon 4").strikethrough().foregroundStyle(.secondary)
                }
                .font(.callout)
                HStack(spacing: 6) {
                    Image(systemName: "square").foregroundStyle(.secondary)
                    Text(highlightedTask)
                    Spacer()
                }
                .font(.callout)
                HStack(spacing: 2) {
                    Text("/ti").font(.callout)
                    Rectangle().fill(Color.accentColor).frame(width: 1.5, height: 15)
                }
                .padding(.top, 2)
                Spacer()
            }
            .padding(Spacing.xl)
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .leading, spacing: 2) {
                Text("Blocs").font(.caption.weight(.semibold)).foregroundStyle(.secondary).padding(.horizontal, 8).padding(.top, 6)
                ForEach(Array([BlockKind.heading1, .heading2, .heading3].enumerated()), id: \.offset) { index, kind in
                    Label(kind.displayName, systemImage: kind.symbolName)
                        .font(.callout)
                        .foregroundStyle(index == 0 ? .white : .primary)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(index == 0 ? Color.accentColor : .clear, in: RoundedRectangle(cornerRadius: Radius.s))
                }
            }
            .padding(4)
            .frame(width: 170)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Radius.m))
            .overlay(RoundedRectangle(cornerRadius: Radius.m).strokeBorder(.separator))
            .shadow(color: .black.opacity(0.18), radius: 10, y: 4)
            .padding(.trailing, 70)
            .padding(.bottom, 30)
        }
    }
}

private struct RecordIllustration: View {
    private let lines: [(String, String, String)] = [
        ("00:00", "Lucas", "Bonjour à tous, merci d'être là pour ce point d'avancement."),
        ("00:05", "Clément", "L'objectif est de valider la nouvelle identité visuelle."),
        ("00:13", "Nathan", "Côté design, on part sur une interface sobre."),
        ("00:19", "Lucas", "Est-ce que tout le monde est d'accord ?"),
    ]

    var body: some View {
        ZStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: Spacing.m) {
                HStack(spacing: Spacing.s) {
                    Image(systemName: "waveform.badge.mic").foregroundStyle(.red)
                    VStack(alignment: .leading, spacing: 0) {
                        Text("Transcription").font(.headline)
                        Text("En cours · 00:23").font(.caption).foregroundStyle(.secondary)
                    }
                }
                ForEach(Array(lines.enumerated()), id: \.offset) { index, line in
                    HStack(alignment: .firstTextBaseline, spacing: Spacing.s) {
                        Text(line.0).font(.caption.monospacedDigit()).foregroundStyle(.tint)
                        Text("\(Text(line.1).bold())  \(line.2)")
                            .font(.callout)
                            .foregroundStyle(index == lines.count - 1 ? .secondary : .primary)
                            .italic(index == lines.count - 1)
                    }
                }
                Spacer()
            }
            .padding(Spacing.xl)
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: Spacing.m) {
                Circle().fill(.red).frame(width: 9, height: 9)
                Text("Enregistrement").font(.callout.weight(.semibold))
                Text("00:23").font(.system(.body, design: .rounded).monospacedDigit())
                LevelMeter(level: 0.65).frame(width: 60, height: 16)
                Image(systemName: "pause.fill")
                Image(systemName: "stop.fill").foregroundStyle(.red)
            }
            .padding(.horizontal, Spacing.l)
            .padding(.vertical, Spacing.s)
            .glassEffect(.regular, in: Capsule())
            .padding(.bottom, Spacing.l)
        }
    }
}

private struct IntelligenceIllustration: View {
    private let root = MindMapNode(label: "Réunion", kind: .root, children: [
        MindMapNode(label: "Design", kind: .topic, children: [
            MindMapNode(label: "Sobre", kind: .decision),
            MindMapNode(label: "Natif", kind: .idea),
        ]),
        MindMapNode(label: "Planning", kind: .topic, children: [
            MindMapNode(label: "15 nov.", kind: .date),
        ]),
        MindMapNode(label: "Actions", kind: .topic, children: [
            MindMapNode(label: "Éditeur", kind: .action),
            MindMapNode(label: "RGPD", kind: .action),
        ]),
        MindMapNode(label: "Questions", kind: .topic, children: [
            MindMapNode(label: "Date ?", kind: .question),
        ]),
    ])

    private let branchColors: [Color] = [.blue, .orange, .green, .purple]

    private static var metrics: MindMapLayout.Metrics {
        var metrics = MindMapLayout.Metrics()
        metrics.horizontalGap = 22
        metrics.verticalGap = 10
        metrics.padding = 6
        metrics.characterWidth = 6.6
        metrics.minWidth = 56
        return metrics
    }

    var body: some View {
        // Schéma statique (sans défilement ni zoom), compact et mis à l'échelle du cadre.
        let layout = MindMapLayout(root: root, metrics: Self.metrics)
        let colors = branchColorMap()
        GeometryReader { proxy in
            let scale = min(proxy.size.width / layout.bounds.width, proxy.size.height / layout.bounds.height, 1)
            ZStack(alignment: .topLeading) {
                Canvas { context, _ in
                    for edge in layout.edges {
                        guard let from = layout.frames[edge.from], let to = layout.frames[edge.to] else { continue }
                        let side = CGFloat(layout.sides[edge.to] ?? 1)
                        let start = CGPoint(x: side > 0 ? from.maxX : from.minX, y: from.midY)
                        let end = CGPoint(x: side > 0 ? to.minX : to.maxX, y: to.midY)
                        let dx = (end.x - start.x) * 0.5
                        var path = Path()
                        path.move(to: start)
                        path.addCurve(to: end, control1: CGPoint(x: start.x + dx, y: start.y), control2: CGPoint(x: end.x - dx, y: end.y))
                        context.stroke(path, with: .color((colors[edge.to] ?? .gray).opacity(0.55)), lineWidth: 2)
                    }
                }
                ForEach(nodes, id: \.id) { node in
                    if let frame = layout.frames[node.id] {
                        let isRoot = node.kind == .root
                        let color = colors[node.id] ?? .gray
                        HStack(spacing: 5) {
                            Image(systemName: node.kind.symbolName)
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(isRoot ? Color.primary : node.kind.color.color)
                            Text(node.label).font(isRoot ? .headline : .callout).lineLimit(1)
                        }
                        .frame(width: frame.width, height: frame.height)
                        .background(RoundedRectangle(cornerRadius: Radius.m).fill(isRoot ? Color.accentColor.opacity(0.16) : color.opacity(0.10)))
                        .overlay(RoundedRectangle(cornerRadius: Radius.m).strokeBorder(isRoot ? Color.accentColor.opacity(0.5) : color.opacity(0.45)))
                        .position(x: frame.midX, y: frame.midY)
                    }
                }
            }
            .frame(width: layout.bounds.width, height: layout.bounds.height)
            .scaleEffect(scale, anchor: .topLeading)
            .frame(width: layout.bounds.width * scale, height: layout.bounds.height * scale, alignment: .topLeading)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(Spacing.s)
    }

    private var nodes: [MindMapNode] {
        [root] + root.children + root.children.flatMap(\.children)
    }

    private func branchColorMap() -> [UUID: Color] {
        var map: [UUID: Color] = [:]
        for (index, child) in root.children.enumerated() {
            let color = branchColors[index % branchColors.count]
            map[child.id] = color
            child.children.forEach { map[$0.id] = color }
        }
        return map
    }
}

private struct PrivacyIllustration: View {
    var body: some View {
        VStack(spacing: Spacing.xl) {
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 72))
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
            VStack(alignment: .leading, spacing: Spacing.m) {
                row("wifi.slash", "Aucune connexion à Internet")
                row("person.crop.circle.badge.xmark", "Aucun compte, aucune collecte")
                row("lock.fill", "Audio chiffré (AES-256)")
                row("square.and.arrow.up", "Export complet de vos données")
                row("trash", "Effacement total en un clic")
            }
        }
        .padding(Spacing.xl)
    }

    private func row(_ symbol: String, _ text: LocalizedStringKey) -> some View {
        HStack(spacing: Spacing.m) {
            Image(systemName: symbol).foregroundStyle(.secondary).frame(width: 24)
            Text(text)
            Spacer()
            Image(systemName: "checkmark").foregroundStyle(.green).fontWeight(.semibold)
        }
        .frame(width: 300)
    }
}
