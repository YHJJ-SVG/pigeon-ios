import SwiftUI

// MARK: - 聊天列表

struct ChatListView: View {
    @EnvironmentObject var app: AppState
    @State private var loading = false
    @State private var showNew = false
    @State private var menuChat: Chat?

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
                            .contextMenu {
                                Button {
                                    Task { await togglePin(chat) }
                                } label: {
                                    Label((chat.pinned ?? 0) == 1 ? "取消置顶" : "置顶聊天",
                                          systemImage: (chat.pinned ?? 0) == 1 ? "pin.slash" : "pin")
                                }
                                Button(role: .destructive) {
                                    Task { await hide(chat) }
                                } label: {
                                    Label("删除此聊天", systemImage: "trash")
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("聊天")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    HStack(spacing: 14) {
                        Button { showNew = true } label: {
                            Image(systemName: "plus.circle.fill")
                        }
                        .foregroundColor(PigeonTheme.accent)
                        Button { Task { await refresh() } } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                        .foregroundColor(PigeonTheme.accent)
                    }
                }
            }
            .sheet(isPresented: $showNew) {
                NewChatSheet { chat in
                    if let c = chat {
                        Task {
                            let all = (try? await API.shared.chats()) ?? app.chats
                            await MainActor.run {
                                app.chats = all
                                withAnimation { app.route = .chat(c) }
                            }
                        }
                    }
                }
                .environmentObject(app)
            }
        }
        .refreshable { await refresh() }
        .task { if app.chats.isEmpty { await refresh() } }
    }

    private func togglePin(_ chat: Chat) async {
        do {
            try await API.shared.pinChat(chat: chat.id, pinned: (chat.pinned ?? 0) == 0)
            app.chats = (try? await API.shared.chats()) ?? app.chats
        } catch {}
    }

    private func hide(_ chat: Chat) async {
        do {
            try await API.shared.hideChat(chat: chat.id)
            app.chats = (try? await API.shared.chats()) ?? app.chats
        } catch {}
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
                HStack(spacing: 6) {
                    if (chat.pinned ?? 0) == 1 {
                        Image(systemName: "pin.fill")
                            .font(.caption2)
                            .foregroundColor(PigeonTheme.accent)
                    }
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
        if m.type == "video" { return "🎬 视频" }
        if (m.enc ?? 0) == 1 {
            return PlainCache.get(m.id) ?? Crypto.decryptText(m.text ?? "") ?? "🔒 加密消息"
        }
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


// MARK: - 新建聊天（选好友，1 人私聊 / 多人建群）

struct NewChatSheet: View {
    @EnvironmentObject var app: AppState
    var onPick: (Chat?) -> Void
    @State private var friends: [User] = []
    @State private var picked: Set<Int> = []
    @State private var groupTitle = ""
    @State private var busy = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ZStack {
                LiquidBackground()
                List {
                    Section {
                        Text("选 1 人创建私聊，选多人创建群聊")
                            .font(.footnote)
                            .foregroundColor(PigeonTheme.textDim)
                    }
                    if friends.isEmpty {
                        Section {
                            Text("还没有好友，先去「联系人」添加")
                                .foregroundColor(PigeonTheme.textDim)
                        }
                    } else {
                        Section {
                            ForEach(friends) { f in
                                Button {
                                    if picked.contains(f.id) { picked.remove(f.id) }
                                    else { picked.insert(f.id) }
                                } label: {
                                    HStack(spacing: 12) {
                                        AvatarView(name: f.nickname, url: f.avatar, size: 40)
                                        Text(f.nickname).foregroundColor(.white)
                                        Spacer()
                                        if picked.contains(f.id) {
                                            Image(systemName: "checkmark.circle.fill")
                                                .foregroundColor(PigeonTheme.accent)
                                        }
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    if picked.count > 1 {
                        Section("群名称") {
                            TextField("群名称（可留空）", text: $groupTitle)
                                .foregroundColor(.white)
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
            .navigationTitle(picked.count > 1 ? "已选 \(picked.count) 人" : "发起聊天")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        create()
                    } label: {
                        if busy { ProgressView() }
                        else { Text(picked.count > 1 ? "创建群聊" : "开始").foregroundColor(PigeonTheme.accent) }
                    }
                    .disabled(picked.isEmpty || busy)
                }
            }
            .task { friends = (try? await API.shared.friends()) ?? [] }
        }
    }

    private func create() {
        guard !picked.isEmpty, !busy else { return }
        busy = true
        let ids = Array(picked)
        Task {
            do {
                let cid: Int
                if ids.count == 1 {
                    let r = try await API.shared.openPrivate(userId: ids[0])
                    cid = r.chat_id
                } else {
                    cid = try await API.shared.createGroup(title: groupTitle.isEmpty ? "群聊" : groupTitle, memberIds: ids)
                }
                let all = (try? await API.shared.chats()) ?? []
                await MainActor.run {
                    app.chats = all
                    dismiss()
                    onPick(all.first { $0.id == cid })
                }
            } catch {
                await MainActor.run { dismiss(); onPick(nil) }
            }
        }
    }
}
