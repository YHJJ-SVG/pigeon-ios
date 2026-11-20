import SwiftUI

// MARK: - 我的

struct ProfileView: View {
    @EnvironmentObject var app: AppState
    @State private var showEdit = false
    @State private var toast = ""

    var body: some View {
        NavigationView {
            ZStack(alignment: .bottom) {
                LiquidBackground()
                ScrollView {
                    VStack(spacing: 10) {
                        VStack(spacing: 10) {
                            AvatarView(name: app.me?.nickname ?? "?", url: app.me?.avatar, size: 80)
                            Text(app.me?.nickname ?? "—").font(.title3.weight(.bold))
                            Text("@\(app.me?.username ?? "—")").font(.subheadline).foregroundColor(PigeonTheme.textDim)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 22)
                        .glass(radius: 22)

                        row(icon: "square.and.pencil", title: "编辑资料", value: app.me?.nickname ?? "") { showEdit = true }
                        row(icon: "lock.shield", title: "私密账号", value: (app.me?.is_private ?? 0) == 1 ? "已开启" : "关闭") { togglePrivate() }
                        row(icon: "clock", title: "数据保留", value: "3 天后自动删除") {}
                        row(icon: "arrow.triangle.2.circlepath", title: "检查更新", value: "v0.3.0") {}
                        row(icon: "rectangle.portrait.and.arrow.right", title: "退出登录", value: "", danger: true) { app.logout() }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 16)
                }
            }
            .navigationTitle("我的")
            .navigationBarTitleDisplayMode(.inline)
            }
            .navigationViewStyle(.stack)
        .sheet(isPresented: $showEdit) {
            EditProfileView(onDone: { Task { await reloadMe() } }).environmentObject(app)
        }
        .overlay(alignment: .bottom) {
            if !toast.isEmpty {
                Text(toast).font(.footnote).foregroundColor(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Color.black.opacity(0.8), in: Capsule())
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: toast)
    }

    private func row(icon: String, title: String, value: String, danger: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 13) {
                Image(systemName: icon)
                    .font(.system(size: 17))
                    .foregroundColor(danger ? .red : PigeonTheme.accent)
                    .frame(width: 34, height: 34)
                    .background((danger ? Color.red : PigeonTheme.accent).opacity(0.16), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                Text(title).foregroundColor(danger ? .red : .white)
                Spacer()
                Text(value).font(.subheadline).foregroundColor(PigeonTheme.textDim)
            }
            .padding(12)
            .glass(radius: 14)
        }
        .buttonStyle(.plain)
    }

    private func togglePrivate() {
        guard let me = app.me else { return }
        let next = (me.is_private ?? 0) == 1 ? 0 : 1
        Task {
            do {
                let u = try await API.shared.updateMe(isPrivate: next)
                await MainActor.run {
                    app.me = u
                    toast = next == 1 ? "已开启私密账号" : "已关闭私密账号"
                    autoClearToast()
                }
            } catch { await MainActor.run { toast = error.localizedDescription; autoClearToast() } }
        }
    }

    private func reloadMe() async {
        do {
            let u = try await API.shared.me()
            await MainActor.run { app.me = u }
        } catch {}
    }

    private func autoClearToast() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) { toast = "" }
    }
}

// MARK: - 编辑资料

struct EditProfileView: View {
    @EnvironmentObject var app: AppState
    var onDone: () -> Void
    @State private var nickname = ""
    @State private var bio = ""
    @State private var avatarEmoji = ""
    @Environment(\.dismiss) private var dismiss
    private let presets = ["🐦", "🐱", "🐶", "🦊", "🐼", "🐯", "🐰", "🐸", "🐷", "🐻"]

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 14) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("选择头像").font(.caption).foregroundColor(PigeonTheme.textDim)
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 5), spacing: 8) {
                            ForEach(presets, id: \.self) { e in
                                Text(e)
                                    .font(.system(size: 26))
                                    .frame(maxWidth: .infinity, minHeight: 46)
                                    .background(
                                        PigeonTheme.inBubble,
                                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                                            .stroke(avatarEmoji == e ? PigeonTheme.accent : .clear, lineWidth: 2))
                                    .onTapGesture { avatarEmoji = e }
                            }
                        }
                    }
                    TextField("昵称", text: $nickname)
                        .padding(12)
                        .background(PigeonTheme.inBubble, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    TextField("签名", text: $bio)
                        .padding(12)
                        .background(PigeonTheme.inBubble, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    Button("保存") {
                        Task {
                            do {
                                let u = try await API.shared.updateMe(nickname: nickname, bio: bio,
                                                                      avatar: avatarEmoji.isEmpty ? nil : avatarEmoji)
                                await MainActor.run { app.me = u }
                                dismiss(); onDone()
                            } catch {}
                        }
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity).padding(.vertical, 13)
                    .background(PigeonTheme.accent, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .padding(20)
            }
            .background(LiquidBackground())
            .navigationTitle("编辑资料")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("取消") { dismiss() } }
            }
            .onAppear {
                nickname = app.me?.nickname ?? ""
                bio = app.me?.bio ?? ""
                avatarEmoji = app.me?.avatar ?? ""
            }
        }
    }
}
