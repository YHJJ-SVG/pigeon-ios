import SwiftUI

// MARK: - 液态玻璃主题

enum PigeonTheme {
    static let bg = Color(red: 0.04, green: 0.07, blue: 0.12)
    static let nav = Color(red: 0.09, green: 0.13, blue: 0.17)
    static let accent = Color(red: 0.37, green: 0.71, blue: 0.97)
    static let inBubble = Color(red: 0.09, green: 0.15, blue: 0.20)
    static let outBubble = Color(red: 0.17, green: 0.32, blue: 0.47)
    static let textDim = Color(red: 0.51, green: 0.60, blue: 0.69)
}

/// 液态玻璃卡片：毛玻璃 + 半透明底色 + 描边 + 投影
struct Glass: ViewModifier {
    var radius: CGFloat = 20
    func body(content: Content) -> some View {
        content
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .background(Color.white.opacity(0.05), in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.16), lineWidth: 0.7)
            )
            .shadow(color: .black.opacity(0.35), radius: 14, x: 0, y: 8)
    }
}

extension View {
    func glass(radius: CGFloat = 20) -> some View { modifier(Glass(radius: radius)) }
}

/// 渐变玻璃底（液态玻璃的层次感）
struct LiquidBackground: View {
    var body: some View {
        ZStack {
            PigeonTheme.bg
            RadialGradient(colors: [PigeonTheme.accent.opacity(0.20), .clear],
                           center: .topLeading, startRadius: 10, endRadius: 420)
            RadialGradient(colors: [Color.purple.opacity(0.14), .clear],
                           center: .bottomTrailing, startRadius: 10, endRadius: 460)
        }
        .ignoresSafeArea()
    }
}

/// 圆头像
struct AvatarView: View {
    let name: String
    var url: String?
    var size: CGFloat = 48
    var online: Bool = false

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Group {
                if let url, !url.isEmpty, !url.contains("/") {
                    Text(url)
                        .font(.system(size: size * 0.5))
                        .frame(width: size, height: size)
                        .background(colorFor(url))
                } else if let url, let u = URL(string: url) {
                    AsyncImage(url: u) { img in
                        img.resizable().scaledToFill()
                    } placeholder: {
                        Text(initial).font(.system(size: size * 0.42, weight: .semibold))
                    }
                } else {
                    Text(initial)
                        .font(.system(size: size * 0.42, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(colorFor(name))
                }
            }
            .frame(width: size, height: size)
            .clipShape(Circle())
            if online {
                Circle().fill(Color.green).frame(width: size * 0.28, height: size * 0.28)
                    .overlay(Circle().stroke(PigeonTheme.bg, lineWidth: 2))
            }
        }
    }

    private var initial: String { String(name.prefix(1)).uppercased() }

    private func colorFor(_ n: String) -> Color {
        let palette: [Color] = [.red, .green, .yellow, .blue, .purple, .pink, .mint, .orange]
        var h = 0
        for ch in n.unicodeScalars { h = (h &* 31 &+ Int(ch.value)) & 0x7fffffff }
        return palette[h % palette.count].opacity(0.85)
    }
}
