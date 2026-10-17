import SwiftUI

@main struct DomruTechnicsApp: App {
    @StateObject private var preferences: AppPreferences
    @StateObject private var model: AppModel
    private let demo: Bool

    init() {
        #if DEBUG
        let uiTesting = ProcessInfo.processInfo.arguments.contains("--ui-testing")
        demo = ProcessInfo.processInfo.arguments.contains("--demo")
        let defaults = uiTesting ? UserDefaults(suiteName: "ru.domru.technics.uitests")! : .standard
        if uiTesting { defaults.removePersistentDomain(forName: "ru.domru.technics.uitests") }
        #else
        demo = false
        let defaults = UserDefaults.standard
        #endif
        let preferences = AppPreferences(defaults: defaults)
        _preferences = StateObject(wrappedValue: preferences)
        _model = StateObject(wrappedValue: AppModel(preferences: preferences))
    }

    var body: some Scene {
        WindowGroup {
            HomeScreen(model: model, preferences: preferences)
                .tint(Palette.red)
                .preferredColorScheme(preferences.theme == .system ? nil : (preferences.theme == .dark ? .dark : .light))
                .task { await model.start(demo: demo) }
        }
    }
}
