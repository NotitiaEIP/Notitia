import SwiftUI

@main
struct iOSApp: App {
    // Before launch ends, so a touched reminder that starts the app opens its action.
    init() {
        NotificationRemindersKt.listenToReminders()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}