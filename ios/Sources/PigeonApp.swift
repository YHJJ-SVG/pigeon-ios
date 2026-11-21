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
            TermsGate()
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


// MARK: - 免责协议

struct TermsGate: View {
    @AppStorage("agreed_terms") private var agreed = false
    @EnvironmentObject var app: AppState

    var body: some View {
        if agreed {
            RootView().environmentObject(app)
        } else {
            DisclaimerView { agreed = true }
        }
    }
}

struct DisclaimerView: View {
    var onAgree: () -> Void
    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Text("用户协议与免责声明")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.white)
            Text("\n")
            ScrollView {
                Text("""
1. 本软件（鸽信 Pigeon）仅供学习、研究和交流使用，不提供任何形式的商业服务。

2. 使用者应自行遵守所在国家或地区的法律法规。利用本软件从事的任何违法违规行为，均由使用者本人承担全部责任，与开发者无关。

3. 本软件不对消息内容的真实性、合法性负责，也不对因使用本软件造成的任何直接或间接损失负责。

4. 服务器仅保留消息 3 天用于投递，逾期自动清理；请勿在软件中存储重要资料。

5. 开发者有权在不另行通知的情况下修改或终止本软件。

点击「同意」表示你已阅读、理解并接受上述全部条款；点击「不同意」将直接退出软件。
""")
                    .font(.system(size: 14))
                    .foregroundColor(PigeonTheme.textDim)
                    .lineSpacing(6)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: .infinity)
            Spacer()
            Button {
                onAgree()
            } label: {
                Text("同意并继续")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(PigeonTheme.accent, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            Button {
                exit(0)  // 不同意直接退出
            } label: {
                Text("不同意，退出")
                    .font(.system(size: 15))
                    .foregroundColor(PigeonTheme.textDim)
                    .padding(.vertical, 10)
            }
            Spacer()
        }
        .padding(24)
        .background(LiquidBackground())
    }
}
