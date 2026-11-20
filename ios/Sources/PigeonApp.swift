import SwiftUI

// MARK: - 应用入口

@main
struct PigeonApp: App {
    @StateObject private var app = AppState()

    init() {
        // 让 List 在 iOS 15 上透明，露出液态玻璃背景
        UITableView.appearance().backgroundColor = .clear
        UITableViewCell.appearance().backgroundColor = .clear
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
                .preferredColorScheme(.dark)
        }
    }
}

// MARK: - 全局状态

final class AppState: ObservableObject {
    @Published var token: String? = UserDefaults.standard.string(forKey: "token") {
        didSet {
            if let token { UserDefaults.standard.set(token, forKey: "token") }
            else { UserDefaults.standard.removeObject(forKey: "token") }
        }
    }
    @Published var me: User? {
        didSet {
            if let me, let d = try? JSONEncoder().encode(me) {
                UserDefaults.standard.set(d, forKey: "me")
            } else {
                UserDefaults.standard.removeObject(forKey: "me")
            }
        }
    }
    @Published var chats: [Chat] = []
    @Published var cursor: Int = UserDefaults.standard.integer(forKey: "cursor")
    @Published var route: Route

    init() {
        // 记住登录：本地有 token 就直接进主界面，不用重新登录
        if UserDefaults.standard.string(forKey: "token") != nil {
            route = .main
        } else {
            route = .login
        }
        if let d = UserDefaults.standard.data(forKey: "me"),
           let u = try? JSONDecoder().decode(User.self, from: d) {
            me = u
        }
    }

    var device: String {
        if let d = UserDefaults.standard.string(forKey: "device") { return d }
        let d = "d" + UUID().uuidString.lowercased().prefix(12)
        UserDefaults.standard.set(d, forKey: "device")
        return d
    }

    func setAuth(token: String, user: User) {
        self.token = token
        self.me = user
        route = .main
    }

    func logout() {
        token = nil
        me = nil
        chats = []
        cursor = 0
        UserDefaults.standard.removeObject(forKey: "token")
        UserDefaults.standard.removeObject(forKey: "me")
        UserDefaults.standard.removeObject(forKey: "cursor")
        route = .login
    }
}

enum Route: Equatable {
    case login
    case main
    case chat(Chat)
}
