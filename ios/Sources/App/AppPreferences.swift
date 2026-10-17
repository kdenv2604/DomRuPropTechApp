import Foundation

@MainActor final class AppPreferences: ObservableObject {
    private let defaults: UserDefaults
    @Published var theme: ThemeMode { didSet { defaults.set(theme.rawValue, forKey: "theme") } }
    @Published var handedness: Handedness { didSet { defaults.set(handedness.rawValue, forKey: "handedness") } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        theme = ThemeMode(rawValue: defaults.string(forKey: "theme") ?? "") ?? .system
        handedness = Handedness(rawValue: defaults.string(forKey: "handedness") ?? "") ?? .right
    }

    var selection: Selection {
        get {
            guard let data = defaults.data(forKey: "selection"), let selection = try? JSONDecoder().decode(Selection.self, from: data) else { return Selection() }
            return selection
        }
        set { defaults.set(try? JSONEncoder().encode(newValue), forKey: "selection") }
    }
}
