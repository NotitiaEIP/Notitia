import NotitiaKit
import SwiftData
import SwiftUI

struct RootView: View {
    @Environment(AppServices.self) private var services
    @Environment(RecordingController.self) private var recorder
    @State private var workspace: Workspace?
    @AppStorage(Preferences.Keys.hasCompletedWelcome) private var hasCompletedWelcome = false
    @Query private var folders: [Folder]
    @Query private var notes: [Note]

    var body: some View {
        Group {
            if let workspace {
                if !hasCompletedWelcome, folders.isEmpty, notes.isEmpty {
                    TutorialView(mode: .firstLaunch) { choice in
                        completeOnboarding(choice, workspace: workspace)
                    }
                } else {
                    MainSplitView()
                        .environment(workspace)
                        .focusedSceneValue(\.workspace, workspace)
                }
            } else {
                Color.clear
            }
        }
        .task {
            if workspace == nil { workspace = Workspace(services: services) }
            #if DEBUG
            if let workspace, DebugSnapshots.isEnabled { Task { await DebugSnapshots.run(workspace) } }
            #endif
            // Maintenance RGPD au lancement puis toutes les 6 heures
            // (purge de la corbeille et de l'audio expiré).
            while !Task.isCancelled {
                services.runMaintenance()
                try? await Task.sleep(for: .seconds(6 * 3600))
            }
        }
    }
}

extension RootView {
    /// Fin du premier tutoriel : exemples éventuels, note d'aide, puis ouverture de l'aide.
    private func completeOnboarding(_ choice: TutorialView.StartChoice?, workspace: Workspace) {
        if choice == .samples { SampleData.populate(services.container.mainContext) }
        let help = HelpContent.install(in: services.container.mainContext)
        hasCompletedWelcome = true
        workspace.sidebarSelection = .allNotes
        workspace.selectedNoteID = help.uid
    }
}

struct MainSplitView: View {
    @Environment(Workspace.self) private var workspace
    @Environment(AppServices.self) private var services
    @Environment(RecordingController.self) private var recorder
    @State private var columnVisibility = NavigationSplitViewVisibility.all
    @FocusState private var isSearchFocused: Bool

    var body: some View {
        @Bindable var workspace = workspace
        NavigationSplitView(columnVisibility: $columnVisibility) {
            SidebarView()
                .navigationSplitViewColumnWidth(min: 200, ideal: 230, max: 320)
        } content: {
            NoteListView()
                .navigationSplitViewColumnWidth(min: 240, ideal: 300, max: 420)
        } detail: {
            DetailView()
        }
        .searchable(text: $workspace.searchText, placement: .toolbar, prompt: Text("Rechercher dans les notes"))
        .searchFocused($isSearchFocused)
        .inspector(isPresented: $workspace.isInspectorPresented) {
            InspectorView()
                .inspectorColumnWidth(min: 260, ideal: 290, max: 360)
        }
        .sheet(item: $workspace.folderEditor) { request in
            FolderEditorSheet(request: request)
        }
        .sheet(item: $workspace.pendingConsent) { _ in
            ConsentSheet()
        }
        .sheet(isPresented: $workspace.isQuickOpenPresented) {
            QuickOpenView()
        }
        .sheet(isPresented: $workspace.isTutorialPresented) {
            TutorialView(mode: .replay, onFinish: { _ in
                workspace.isTutorialPresented = false
            }, onOpenHelp: {
                workspace.openHelp()
            })
            .frame(width: 960, height: 620)
        }
        .alert(item: $workspace.alert) { alert in
            Alert(title: Text(alert.title), message: Text(alert.message))
        }
        .onChange(of: workspace.searchText) {
            if !workspace.searchText.isEmpty, workspace.sidebarSelection == nil {
                workspace.sidebarSelection = .allNotes
            }
        }
        .focusedSceneValue(\.searchFocus, $isSearchFocused)
    }
}

struct DetailView: View {
    @Environment(Workspace.self) private var workspace

    var body: some View {
        if let editor = workspace.editor {
            NoteEditorView(model: editor)
                .id(editor.note.uid)
        } else {
            ContentUnavailableView {
                Label("Aucune note sélectionnée", systemImage: "note.text")
            } description: {
                Text("Choisissez une note dans la liste ou créez-en une.")
            } actions: {
                Button("Nouvelle note") { workspace.createNote() }
            }
        }
    }
}

extension FocusedValues {
    @Entry var searchFocus: FocusState<Bool>.Binding?
}
