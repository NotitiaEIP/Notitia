import SwiftUI
import Shared

@main
struct iOSApp: App {
    // Before launch ends, so a touched reminder that starts the app opens its action.
    init() {
        #if DEBUG
        // The CI starts the app this way on a simulator to prove the bridge: it reads the marker, the app quits.
        if ProcessInfo.processInfo.arguments.contains("-coreSelfCheck") {
            let marker = MainViewControllerKt.coreSelfCheckMarker(core: RustCore())
            // Also in a file: the console of a simulator app does not always reach the CI.
            let file = FileManager.default.temporaryDirectory.appendingPathComponent("core-self-check.txt")
            try? marker.write(to: file, atomically: true, encoding: .utf8)
            print(marker)
            exit(0)
        }
        #endif
        NotificationRemindersKt.listenToReminders()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}