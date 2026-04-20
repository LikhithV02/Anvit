import SwiftUI
import ComposeApp

@main
struct AnvitApp: App {

    init() {
        // Bootstrap Koin DI — must run before any ViewModel or Room access
        KoinHelper.shared.doInitKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
        }
    }
}
