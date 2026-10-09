import AppKit
import NotitiaKit
import SwiftData
import SwiftUI

struct SettingsView: View {
    var body: some View {
        TabView {
            Tab("Général", systemImage: "gearshape") { GeneralSettings() }
            Tab("Transcription", systemImage: "waveform") { TranscriptionSettings() }
            Tab("Intelligence", systemImage: "sparkles") { IntelligenceSettings() }
            Tab("Confidentialité", systemImage: "lock.shield") { PrivacySettings() }
        }
        .frame(width: 580)
        .scenePadding()
    }
}

// MARK: - Général

private struct GeneralSettings: View {
    @AppStorage(Preferences.Keys.noteSortOrder) private var sortOrder = NoteSortOrder.modified

    var body: some View {
        Form {
            Picker("Trier les notes par", selection: $sortOrder) {
                ForEach(NoteSortOrder.allCases) { Text($0.label).tag($0) }
            }
            LabeledContent("Apparence") {
                Text("Suit le réglage du système (clair ou sombre) et votre couleur d'accent.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
            }
            LabeledContent("Version") {
                Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }
}

// MARK: - Moteurs

private struct EngineRow: View {
    let engine: EngineDescriptor

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(engine.displayName)
                Text(engine.detail).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if engine.requiresNetwork {
                Label("Réseau", systemImage: "network").font(.caption).foregroundStyle(.orange)
            } else {
                Label("Hors ligne", systemImage: "wifi.slash").font(.caption).foregroundStyle(.secondary)
            }
        }
        .opacity(engine.isAvailable ? 1 : 0.5)
    }
}

private struct TranscriptionSettings: View {
    @AppStorage(Preferences.Keys.transcriptionEngine) private var engineID = DemoTranscriptionEngine.descriptorValue.id
    @AppStorage(Preferences.Keys.transcriptionLocale) private var localeID = "fr_FR"

    var body: some View {
        Form {
            Section {
                Picker("Moteur", selection: $engineID) {
                    ForEach(EngineCatalog.transcriptionEngines) { engine in
                        EngineRow(engine: engine).tag(engine.id).disabled(!engine.isAvailable)
                    }
                }
                .pickerStyle(.radioGroup)
            } footer: {
                Text("Le moteur définitif n'est pas encore choisi. Le moteur de démonstration rejoue un texte d'exemple pour tester l'application ; aucun son n'est analysé.")
            }
            Picker("Langue parlée", selection: $localeID) {
                ForEach(Preferences.transcriptionLocales, id: \.id) { Text($0.name).tag($0.id) }
            }
        }
        .formStyle(.grouped)
    }
}

private struct IntelligenceSettings: View {
    @AppStorage(Preferences.Keys.aiService) private var serviceID = LocalExtractiveAIService.descriptorValue.id

    var body: some View {
        Form {
            Section {
                Picker("Moteur", selection: $serviceID) {
                    ForEach(EngineCatalog.aiServices) { engine in
                        EngineRow(engine: engine).tag(engine.id).disabled(!engine.isAvailable)
                    }
                }
                .pickerStyle(.radioGroup)
            } footer: {
                Text("Le moteur local sélectionne et organise les phrases de vos notes pour produire résumés, mots-clés et cartes mentales. Il fonctionne sans Internet.")
            }
        }
        .formStyle(.grouped)
    }
}

// MARK: - Confidentialité

private struct PrivacySettings: View {
    @Environment(AppServices.self) private var services
    @Environment(\.modelContext) private var context
    @AppStorage(Preferences.Keys.askConsentBeforeRecording) private var askConsent = true
    @AppStorage(Preferences.Keys.audioRetentionDays) private var audioRetentionDays = 0
    @AppStorage(Preferences.Keys.hasCompletedWelcome) private var hasCompletedWelcome = true
    @State private var statistics = NotitiaStore.Statistics()
    @State private var confirmErase = false
    @State private var message: String?

    var body: some View {
        Form {
            Section {
                Label {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Vos données restent sur ce Mac").font(.headline)
                        Text("Notes, transcriptions et enregistrements sont stockés uniquement dans le conteneur privé de Notitia. L'application n'a pas l'autorisation d'accéder à Internet : rien n'est envoyé, rien n'est collecté.")
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                } icon: {
                    Image(systemName: "lock.shield.fill").foregroundStyle(.tint).font(.title2)
                }
                InfoRow(label: "Notes", value: "\(statistics.notes) (dont \(statistics.trashed) dans la corbeille)")
                InfoRow(label: "Dossiers", value: "\(statistics.folders)")
                InfoRow(label: "Enregistrements", value: "\(statistics.recordings) (dont \(statistics.recordingsWithAudio) avec audio)")
                InfoRow(label: "Espace utilisé", value: ByteCountFormatter.string(fromByteCount: statistics.diskUsage, countStyle: .file))
                Button("Afficher les fichiers dans le Finder") {
                    NSWorkspace.shared.activateFileViewerSelecting([services.paths.root])
                }
            }

            Section {
                Toggle("Rappeler l'obligation d'informer les participants avant chaque enregistrement", isOn: $askConsent)
                Picker("Conserver l'audio", selection: $audioRetentionDays) {
                    ForEach(RetentionPolicy.audioRetentionChoices, id: \.self) { days in
                        Text(RetentionPolicy.label(forAudioRetention: days)).tag(days ?? 0)
                    }
                }
                .onChange(of: audioRetentionDays) { services.runMaintenance(); refresh() }
            } header: {
                Text("Enregistrements")
            } footer: {
                Text("L'audio est chiffré (AES-256-GCM) avec une clé conservée dans le trousseau de ce Mac. Quand l'audio expire, la transcription est conservée.")
            }

            Section {
                InfoRow(label: "Corbeille", value: String(localized: "suppression définitive après \(RetentionPolicy.defaultTrashRetentionDays) jours"))
                InfoRow(label: "Outils d'écriture Apple", value: String(localized: "désactivés dans les notes"))
            } header: {
                Text("Conservation")
            }

            Section {
                Button("Exporter toutes mes données…", action: export)
                Button("Effacer toutes mes données…", role: .destructive) { confirmErase = true }
                if let message {
                    Text(message).font(.caption).foregroundStyle(.secondary)
                }
            } header: {
                Text("Vos droits")
            } footer: {
                Text("L'export contient toutes vos notes (Markdown et JSON), vos transcriptions et votre audio déchiffré. L'effacement supprime définitivement la base, l'audio, les images et la clé de chiffrement.")
            }
        }
        .formStyle(.grouped)
        .onAppear(perform: refresh)
        .confirmationDialog("Effacer toutes vos données ?", isPresented: $confirmErase) {
            Button("Tout effacer définitivement", role: .destructive, action: eraseEverything)
        } message: {
            Text("Toutes les notes, dossiers, tags, transcriptions et enregistrements seront supprimés de ce Mac. Cette action est irréversible. Pensez à exporter vos données avant.")
        }
    }

    private var store: NotitiaStore {
        NotitiaStore(context: context, paths: services.paths, vault: services.vault)
    }

    private func refresh() {
        statistics = store.statistics()
    }

    private func export() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.prompt = String(localized: "Exporter ici")
        panel.message = String(localized: "Choisissez le dossier où créer l'export Notitia.")
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try context.save()
            let snapshot = try ExportSnapshot.make(context: context)
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            let folder = try DataExporter(paths: services.paths, vault: services.vault).export(snapshot, into: url)
            message = String(localized: "Export créé : \(folder.lastPathComponent)")
            NSWorkspace.shared.activateFileViewerSelecting([folder])
        } catch {
            message = String(localized: "Échec de l'export : \(error.localizedDescription)")
        }
    }

    private func eraseEverything() {
        services.player.stop()
        do {
            try store.eraseEverything()
            hasCompletedWelcome = false
            message = String(localized: "Toutes les données ont été effacées.")
        } catch {
            message = String(localized: "Échec de l'effacement : \(error.localizedDescription)")
        }
        refresh()
    }
}
