import SwiftUI

// MARK: - 联系人（好友 + 申请 + 搜索添加）

struct ContactsView: View {
    @EnvironmentObject var app: AppState
    @State private var friends: [User] = []
    @State private var incoming: [FriendRequest] = []
    @State private var outgoing: [FriendRequest] = []
    @State private var search = ""
    @State private var results: [User] = []
    @State private var showRequests = false
    @State private var showAdd: User?
    @State private var showCreateGroup = false
    @State private var toast = ""

    var body: some View {
        NavigationView {
            ZStack(alignment: .bottom) {
                LiquidBackground()
                List {
                    // 新的朋友
                    Button { showRequests = true } label: {
                        HStack(spacing: 12) {
                            ZStack {
                                Circle().fill(PigeonTheme.accent.opacity(0.25))
                                Image(systemName: "person.badge.plus").foregroundColor(PigeonTheme.accent)
                            }
                            .frame(width: 40, height: 40)
                            VStack(alignment: .leading) {
                                Text("新的朋友").foregroundColor(.white)
                                Text(incoming.isEmpty ? "暂无申请" : "\(incoming.count) 个好友申请")
                                    .font(.caption).foregroundColor(PigeonTheme.textDim)
                            }
                            Spacer()
                            if !incoming.isEmpty {
                                Text("\(incoming.count)")
                                    .font(.caption2.weight(.bold)).foregroundColor(.white)
                                    .padding(.horizontal, 8).padding(.vertical, 3)
                                    .background(PigeonTheme.accent, in: Capsule())
                            }
                        }
                    }
                    .buttonStyle(.plain)

                    // 发起群聊
                    Button { showCreateGroup = true } label: {
                        HStack(spacing: 12) {
                            ZStack {
                                Circle().fill(Color(red: 0.30, green: 0.80, blue: 0.37).opacity(0.22))
                                Image(systemName: "person.3").foregroundColor(Color(red: 0.30, green: 0.80, blue: 0.37))
                            }
                            .frame(width: 40, height: 40)
                            Text("发起群聊").foregroundColor(.white)
                            Spacer()
                        }
                    }
                    .buttonStyle(.plain)

                    if !search.isEmpty {
                        Section("搜索结果") {
                            ForEach(results) { u in
                                UserRow(user: u, onAdd: { showAdd = u })
                            }
                        }
                    }

                    Section("我的好友（\(friends.count)）") {
                        if friends.isEmpty {
                            Text("还没有好友，搜索账号添加").foregroundColor(PigeonTheme.textDim)
                        } else {
                            ForEach(friends) { u in
                                Button {
                                    Task { await openChat(u) }
                                } label: {
                                    HStack(spacing: 12) {
                                        AvatarView(name: u.nickname, url: u.avatar, size: 42, online: u.online ?? false)
                                        VStack(alignment: .leading) {
                                            Text(u.nickname).foregroundColor(.white)
                                            Text("@\(u.username) · \(u.online == true ? "在线" : "离线")")
                                                .font(.caption).foregroundColor(PigeonTheme.textDim)
                                        }
                                        Spacer()
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
                .listStyle(.plain)
                
                .searchable(text: $search, prompt: "搜索账号 / ID 添加好友")
                .onChange(of: search) { q in
                    Task { await doSearch(q) }
                }
            }
            .navigationTitle("联系人")
            .navigationBarTitleDisplayMode(.inline)
            }
            .navigationViewStyle(.stack)
        .task { await reload() }
        .sheet(isPresented: $showRequests) {
            RequestsView(incoming: $incoming, outgoing: $outgoing, onDone: { Task { await reload() } })
                .environmentObject(app)
        }
        .sheet(item: $showAdd) { u in
            AddFriendSheet(user: u, onDone: { Task { await reload() } })
                .environmentObject(app)
        }
        .sheet(isPresented: $showCreateGroup) {
            CreateGroupSheet(friends: friends, onDone: { Task { await reload() } })
                .environmentObject(app)
        }
        .overlay(alignment: .bottom) {
            if !toast.isEmpty {
                Text(toast).font(.footnote).foregroundColor(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Color.black.opacity(0.8), in: Capsule())
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: toast)
    }

    private func reload() async {
        do {
            friends = try await API.shared.friends()
            let r = try await API.shared.friendRequests()
            incoming = r.incoming ?? []
            outgoing = r.outgoing ?? []
        } catch { toast = error.localizedDescription; autoClearToast() }
    }

    private func doSearch(_ q: String) async {
        let query = q.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { results = []; return }
        do { results = try await API.shared.searchUsers(query) }
        catch { results = [] }
    }

    private func openChat(_ u: User) async {
        do {
            let r = try await API.shared.openPrivate(userId: u.id)
            let s = try await API.shared.sync(cursor: app.cursor)
            if let chats = s.chats { app.chats = chats }
            if let chat = app.chats.first(where: { $0.id == r.chat_id }) {
                await MainActor.run {
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { app.route = .chat(chat) }
                }
            }
        } catch { toast = error.localizedDescription; autoClearToast() }
    }

    private func autoClearToast() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) { toast = "" }
    }
}

// MARK: - 用户行

struct UserRow: View {
    let user: User
    var onAdd: () -> Void

    private var label: String {
        switch user.relation ?? "none" {
        case "friend": return "已添加"
        case "outgoing": return "待验证"
        case "incoming": return "去同意"
        default: return "添加"
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: user.nickname, url: user.avatar, size: 42, online: user.online ?? false)
            VStack(alignment: .leading) {
                Text(user.nickname).foregroundColor(.white)
                Text("@\(user.username) · ID \(user.id)").font(.caption).foregroundColor(PigeonTheme.textDim)
            }
            Spacer()
            Button(label, action: onAdd)
                .font(.subheadline.weight(.medium))
                .foregroundColor(user.relation == "friend" ? PigeonTheme.textDim : PigeonTheme.accent)
                .disabled(user.relation == "friend" || user.relation == "outgoing")
        }
    }
}

// MARK: - 好友申请列表

struct RequestsView: View {
    @EnvironmentObject var app: AppState
    @Binding var incoming: [FriendRequest]
    @Binding var outgoing: [FriendRequest]
    var onDone: () -> Void
    @State private var replyTarget: FriendRequest?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ZStack {
                LiquidBackground()
                List {
                    Section("收到的申请") {
                        if incoming.isEmpty {
                            Text("暂无").foregroundColor(PigeonTheme.textDim)
                        } else {
                            ForEach(incoming) { r in
                                VStack(alignment: .leading, spacing: 6) {
                                    HStack(spacing: 10) {
                                        AvatarView(name: r.user.nickname, url: r.user.avatar, size: 38)
                                        VStack(alignment: .leading) {
                                            Text(r.user.nickname).foregroundColor(.white)
                                            Text("@\(r.user.username)").font(.caption).foregroundColor(PigeonTheme.textDim)
                                        }
                                        Spacer()
                                        Button("同意") { Task { await accept(r) } }
                                            .foregroundColor(PigeonTheme.accent)
                                        Button("拒绝") { Task { await reject(r) } }
                                            .foregroundColor(PigeonTheme.textDim)
                                        Button("回复") { replyTarget = r }
                                            .foregroundColor(.orange)
                                    }
                                    Text(r.message ?? "请求添加你为好友")
                                        .font(.footnote).foregroundColor(.white.opacity(0.8))
                                }
                            }
                        }
                    }
                    Section("我发出的") {
                        if outgoing.isEmpty {
                            Text("暂无").foregroundColor(PigeonTheme.textDim)
                        } else {
                            ForEach(outgoing) { r in
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack(spacing: 10) {
                                        AvatarView(name: r.user.nickname, url: r.user.avatar, size: 38)
                                        VStack(alignment: .leading) {
                                            Text(r.user.nickname).foregroundColor(.white)
                                            Text((r.status == "rejected" ? "已拒绝" : "等待验证") + " · \(r.message ?? "")")
                                                .font(.caption).foregroundColor(PigeonTheme.textDim)
                                        }
                                    }
                                    if r.status == "rejected", let reply = r.reply, !reply.isEmpty {
                                        Text("对方回复：\(reply)").font(.footnote).foregroundColor(.red.opacity(0.85))
                                    }
                                }
                            }
                        }
                    }
                }
                .listStyle(.plain)
                
            }
            .navigationTitle("新的朋友")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("完成") { dismiss(); onDone() }
                }
            }
            .sheet(item: $replyTarget) { r in
                ReplySheet(user: r.user) { reason in
                    Task { await reject(r, reply: reason) }
                }
            }
        }
    }

    private func accept(_ r: FriendRequest) async {
        do { try await API.shared.acceptFriend(r.id); await reload() }
        catch {}
    }

    private func reject(_ r: FriendRequest, reply: String? = nil) async {
        do { try await API.shared.rejectFriend(r.id, reply: reply); await reload() }
        catch {}
    }

    private func reload() async {
        do {
            let req = try await API.shared.friendRequests()
            incoming = req.incoming ?? []
            outgoing = req.outgoing ?? []
        } catch {}
        onDone()
    }
}

// MARK: - 回复 / 添加 输入弹层

struct ReplySheet: View {
    let user: User
    var onSend: (String) -> Void
    @State private var text = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            VStack(spacing: 14) {
                Text("回复 @\(user.username)").font(.headline)
                TextField("说明一下为什么暂时不能通过（可留空）", text: $text)
                    .padding(12)
                    .background(PigeonTheme.inBubble, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                Button("发送") { onSend(text); dismiss() }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity).padding(.vertical, 13)
                    .background(PigeonTheme.accent, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .padding(20)
            .background(LiquidBackground())
            .navigationTitle("好友申请")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("取消") { dismiss() } }
            }
        }
    }
}

struct AddFriendSheet: View {
    let user: User
    var onDone: () -> Void
    @EnvironmentObject var app: AppState
    @State private var text = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            VStack(spacing: 14) {
                AvatarView(name: user.nickname, url: user.avatar, size: 64)
                Text("添加 @\(user.username) 为好友").font(.headline)
                TextField("说句话，让对方知道你是谁（可留空）", text: $text)
                    .padding(12)
                    .background(PigeonTheme.inBubble, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                Button("发送申请") {
                    Task {
                        do {
                            try await API.shared.sendFriendRequest(userId: user.id,
                                message: text.isEmpty ? "请求添加你为好友" : text)
                            dismiss(); onDone()
                        } catch {}
                    }
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity).padding(.vertical, 13)
                .background(PigeonTheme.accent, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .padding(20)
            .background(LiquidBackground())
            .navigationTitle("好友申请")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("取消") { dismiss() } }
            }
        }
    }
}

// MARK: - 发起群聊

struct CreateGroupSheet: View {
    @EnvironmentObject var app: AppState
    let friends: [User]
    var onDone: () -> Void
    @State private var title = ""
    @State private var selected: Set<Int> = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ZStack {
                LiquidBackground()
                VStack(spacing: 14) {
                    TextField("群名称", text: $title)
                        .padding(12)
                        .background(PigeonTheme.inBubble, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    Text("选择好友（\(selected.count)）")
                        .font(.caption).foregroundColor(PigeonTheme.textDim)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    ScrollView {
                        VStack(spacing: 0) {
                            ForEach(friends) { f in
                                Button {
                                    if selected.contains(f.id) { selected.remove(f.id) } else { selected.insert(f.id) }
                                } label: {
                                    HStack(spacing: 12) {
                                        Image(systemName: selected.contains(f.id) ? "checkmark.circle.fill" : "circle")
                                            .foregroundColor(PigeonTheme.accent)
                                        AvatarView(name: f.nickname, url: f.avatar, size: 36)
                                        Text(f.nickname).foregroundColor(.white)
                                        Spacer()
                                    }
                                    .padding(.vertical, 8)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    Button("创建群聊") {
                        Task {
                            do {
                                let cid = try await API.shared.createGroup(title: title.trimmingCharacters(in: .whitespaces),
                                                                          memberIds: Array(selected))
                                _ = try await API.shared.sync(cursor: app.cursor)
                                if let chats = try? await API.shared.chats() { app.chats = chats }
                                dismiss(); onDone()
                            } catch {}
                        }
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity).padding(.vertical, 13)
                    .background(PigeonTheme.accent, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || selected.isEmpty)
                }
                .padding(20)
            }
            .navigationTitle("发起群聊")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("取消") { dismiss() } }
            }
        }
    }
}
