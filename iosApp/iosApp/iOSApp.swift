import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        HotaudioBridgeProvider.shared.bridge = IosHotaudioBridge()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
