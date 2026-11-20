import SwiftUI

// MARK: - 聊天列表

struct ChatListView: View {
    @EnvironmentObject var app: AppState
    @State private var loading = false

    var body: some View {
        NavigationView {
            ZStack {
                LiquidBackground()
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(app.chats) { chat in
                            Button {
                                withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
                                    app.route = .chat(chat)
                                }
                            } label: {
                                ChatRow(chat: chat)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .navigationTitle("聊天")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { Task { await refresh() } } label: {
                        Image(systemName: "square.and.pencil")
                    }
                    .foregroundColor(PigeonTheme.accent)
                }
            }
        }
        .refreshable { await refresh() }
        .task { if app.chats.isEmpty { await refresh() } }
    }

    private func refresh() async {
        loading = true
        defer { loading = false }
        do {
            let s = try await API.shared.sync(cursor: app.cursor)
            if let chats = s.chats { app.chats = chats }
            if let cursor = s.cursor, cursor > app.cursor {
                app.cursor = cursor
                UserDefaults.standard.set(cursor, forKey: "cursor")
                Task { try? await API.shared.ack(cursor) }
            }
        } catch {
            if error.localizedDescription.contains("登录已失效") { app.logout() }
        }
    }
}

struct ChatRow: View {
    let chat: Chat

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: chat.title ?? "?", url: chat.avatar, size: 54)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(chat.title ?? "会话")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(.white)
                        .lineLimit(1)
                    Spacer()
                    if let lm = chat.last_message {
                        Text(timeShort(lm.created_at))
                            .font(.caption2)
                            .foregroundColor(PigeonTheme.textDim)
                    }
                }
                HStack {
                    Text(preview(chat.last_message))
                        .font(.subheadline)
                        .foregroundColor((chat.unread ?? 0) > 0 ? .white : PigeonTheme.textDim)
                        .lineLimit(1)
                    Spacer()
                    if let unread = chat.unread, unread > 0 {
                        Text("\(unread > 99 ? "99+" : "\(unread)")")
                            .font(.caption2.weight(.semibold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(PigeonTheme.accent, in: Capsule())
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 9)
        .contentShape(Rectangle())
    }

    private func preview(_ m: Message?) -> String {
        guard let m else { return "暂无消息" }
        if (m.deleted ?? 0) == 1 { return "消息已撤回" }
        if m.type == "image" { return "📷 图片" }
        if m.type == "voice" { return "🎤 语音" }
        if m.type == "file" { return "📎 文件" }
        return m.text ?? ""
    }
}

func timeShort(_ s: String) -> String {
    let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd HH:mm:ss"
    guard let d = f.date(from: s.replacingOccurrences(of: "T", with: " ")) else { return "" }
    let cal = Calendar.current
    if cal.isDateInToday(d) {
        let hf = DateFormatter(); hf.dateFormat = "HH:mm"; return hf.string(from: d)
    }
    if cal.isDateInYesterday(d) { return "昨天" }
    let df = DateFormatter(); df.dateFormat = "M/d"; return df.string(from: d)
}
