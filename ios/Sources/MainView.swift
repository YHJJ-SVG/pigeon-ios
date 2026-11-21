import SwiftUI

// MARK: - 主界面（三标签 + 导航）

struct MainView: View {
    @EnvironmentObject var app: AppState
    @State private var tab = 0
    @State private var synced = false

    var body: some View {
        TabView(selection: $tab) {
            ChatListView()
                .tabItem { Label("聊天", systemImage: "bubble.left.and.bubble.right.fill") }
                .tag(0)
            ContactsView()
                .tabItem { Label("联系人", systemImage: "person.2.fill") }
                .tag(1)
            ProfileView()
                .tabItem { Label("我的", systemImage: "person.crop.circle.fill") }
                .tag(2)
        }
        .tint(PigeonTheme.accent)
        .task {
            if !synced {
                synced = true
                await bootstrap()
            }
        }
    }

    private func bootstrap() async {
        API.shared.token = app.token
        API.shared.device = app.device
        do {
            let me = try await API.shared.me()
            await MainActor.run { app.me = me }
        } catch {
            // 忽略 /me 失败，继续尝试同步
        }
        // 端到端加密：确保本机密钥对并上传公钥
        let pk = Crypto.ensureKeys()
        if !pk.isEmpty, app.me?.public_key != pk {
            _ = try? await API.shared.updateMe(publicKey: pk)
        }
        do {
            let s = try await API.shared.sync(cursor: app.cursor)
            if let chats = s.chats { app.chats = chats }
            if let cursor = s.cursor, cursor > app.cursor {
                app.cursor = cursor
                UserDefaults.standard.set(cursor, forKey: "cursor")
                Task { try? await API.shared.ack(cursor) }
            }
        } catch {
            if error.localizedDescription.contains("登录已失效") {
                app.logout()
            }
        }
    }
}
