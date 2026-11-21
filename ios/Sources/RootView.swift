import SwiftUI

// MARK: - 根视图

struct RootView: View {
    @EnvironmentObject var app: AppState
    @State private var splash = true

    var body: some View {
        ZStack {
            Group {
                switch app.route {
                case .login:
                    LoginView()
                case .main:
                    MainView()
                case .chat(let chat):
                    ChatView(chat: chat)
                }
            }

            if splash {
                SplashView()
                    .transition(.opacity)
                    .zIndex(1)
            }
        }
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.35) {
                withAnimation(.easeOut(duration: 0.45)) { splash = false }
            }
        }
    }
}

// MARK: - 启动动画

struct SplashView: View {
    @State private var shown = false

    var body: some View {
        ZStack {
            LiquidBackground()
            VStack(spacing: 14) {
                Text("鸽")
                    .font(.system(size: 44, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 96, height: 96)
                    .background(
                        LinearGradient(colors: [PigeonTheme.accent, Color(red: 0.17, green: 0.56, blue: 0.85)],
                                       startPoint: .top, endPoint: .bottom),
                        in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                    .scaleEffect(shown ? 1 : 0.72)
                    .opacity(shown ? 1 : 0)
                Text("鸽信")
                    .font(.headline)
                    .foregroundColor(.white.opacity(0.85))
                    .opacity(shown ? 1 : 0)
            }
        }
        .onAppear {
            withAnimation(.spring(response: 0.5, dampingFraction: 0.65)) { shown = true }
        }
    }
}
