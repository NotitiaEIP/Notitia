import SwiftUI
import Shared

@main
struct iOSApp: App {
    // Before launch ends, so a touched reminder that starts the app opens its action.
    init() {
        #if DEBUG
        // The CI starts the app this way on a simulator to prove the bridge: it reads the marker, the app quits.
        if ProcessInfo.processInfo.arguments.contains("-coreSelfCheck") {
            print(MainViewControllerKt.coreSelfCheckMarker(core: RustCore()))
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