import SwiftUI

enum Palette {
    static let red = Color(red: 240 / 255, green: 68 / 255, blue: 75 / 255)
    static let green = Color(red: 24 / 255, green: 168 / 255, blue: 107 / 255)
    static let amber = Color(red: 231 / 255, green: 154 / 255, blue: 35 / 255)
    static let background = adaptive(0xF1F6F9, 0x0E141C)
    static let surface = adaptive(0xFFFFFF, 0x171F2A)
    static let soft = adaptive(0xE7F1F5, 0x202B38)
    static let text = adaptive(0x131820, 0xF3F6F8)
    static let muted = adaptive(0x65707C, 0xAAB5C0)
    static let selected = adaptive(0xFFE2E3, 0x5C2025)

    private static func adaptive(_ light: UInt, _ dark: UInt) -> Color {
        Color(UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 255) / 255, green: CGFloat((hex >> 8) & 255) / 255,
                           blue: CGFloat(hex & 255) / 255, alpha: 1)
        })
    }
}

struct ActionButtonStyle: ButtonStyle {
    var color: Color = Palette.red
    var foreground: Color = .white
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.callout.weight(.semibold))
            .multilineTextAlignment(.center).padding(.horizontal, 15).padding(.vertical, 11)
            .frame(minHeight: 44).foregroundColor(foreground)
            .background(color.opacity(enabled ? (configuration.isPressed ? 0.75 : 1) : 0.45))
            .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

extension View {
    func serviceCard(_ color: Color = Palette.surface, radius: CGFloat = 22) -> some View {
        padding(16).background(color).clipShape(RoundedRectangle(cornerRadius: radius))
    }
}
