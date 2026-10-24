import NotitiaKit
import SwiftData
import SwiftUI

@main
struct NotitiaApp: App {
    @State private var services = AppServices()

    var body: some Scene {
        WindowGroup("Notitia", id: "main") {
            RootView()
                .environment(services)
                .environment(services.recorder)
                .environment(services.player)
                .frame(minWidth: 900, minHeight: 560)
        }
        .modelContainer(services.container)
        .windowToolbarStyle(.unified)
        .commands {
            AppCommands()
        }

        Settings {
            SettingsView()
                .environment(services)
                .modelContainer(services.container)
        }
    }
}
