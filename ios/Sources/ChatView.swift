import SwiftUI
import AVFoundation
import AVKit
import AudioToolbox

// MARK: - 聊天页

struct ChatView: View {
    @EnvironmentObject var app: AppState
    let chat: Chat
    @State private var msgs: [Message] = []
    @State private var draft = ""
    @State private var loading = false
    @State private var errMsg = ""
    @State private var replyTo: Message?
    @State private var actionMsg: Message?
    @State private var forwardMsg: Message?
    @State private var showPicker = false
    @State private var pickedImage: Data?
    @State private var showMention = false
    @State private var members: [User] = []
    @State private var showGroupInfo = false
    @State private var showVideoPicker = false
    @State private var pickedVideo: URL?
    @State private var playVideoURL: URL?
    @State private var showVideoPlay = false
    @State private var recording = false
    @State private var audioRecorder: AVAudioRecorder?
    @State private var recordURL: URL?
    @State private var audioPlayer: AVAudioPlayer?
    @State private var playingVoiceId: Int?
    @State private var showPhoto: Message?
    @State private var enter = false

    var body: some View {
        ZStack {
            LiquidBackground()
            VStack(spacing: 0) {
                header
                messageList
                composer
            }
        }
        .opacity(enter ? 1 : 0)
        .offset(x: enter ? 0 : 120)
        .onAppear {
            withAnimation(.easeOut(duration: 0.26)) { enter = true }
        }
        .fullScreenCover(item: $showPhoto) { m in
            PhotoZoomView(mediaId: m.media_id, mediaUrl: m.media_url)
        }
        .task { await open() }
        .sheet(isPresented: $showPicker) { ImagePicker(imageData: $pickedImage) }
        .fullScreenCover(isPresented: $showVideoPlay) {
            if let url = playVideoURL {
                VideoPlayer(player: AVPlayer(url: url))
                    .ignoresSafeArea()
                    .onTapGesture { showVideoPlay = false }
            }
        }
        .sheet(isPresented: $showGroupInfo) {
            GroupInfoSheet(chat: chat).environmentObject(app)
        }
        .sheet(isPresented: $showMention) {
            NavigationView {
                ZStack {
                    LiquidBackground()
                    List {
                        ForEach(members) { m in
                            Button {
                                draft += "@\(m.nickname) "
                                showMention = false
                            } label: {
                                HStack(spacing: 12) {
                                    AvatarView(name: m.nickname, url: m.avatar, size: 36)
                                    Text(m.nickname).foregroundColor(.white)
                                    Spacer()
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .listStyle(.plain)
                }
                .navigationTitle("@ 提及成员")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) { Button("取消") { showMention = false } }
                }
                .onAppear {
                    Task { members = (try? await API.shared.groupMembers(chat: chat.id)) ?? [] }
                }
            }
        }
        .sheet(item: $forwardMsg) { m in
            ForwardSheet(from: m, currentChatId: chat.id) { target in
                Task {
                    do {
                        let sent = try await API.shared.send(chat: target.id, type: "text", text: m.text ?? "图片",
                                                             fwdFrom: m.sender.nickname)
                        if target.id == chat.id {
                            if !msgs.contains(where: { $0.id == sent.id }) { msgs.append(sent) }
                            msgs.sort { $0.id < $1.id }
                        }
                    } catch { errMsg = error.localizedDescription }
                }
            }
            .environmentObject(app)
        }
        .onChange(of: pickedImage) { data in
            guard let data else { return }
            pickedImage = nil
            Task {
                do {
                    let id = try await API.shared.uploadImage(data, name: "img_\(Int(Date().timeIntervalSince1970)).jpg")
                    let sent = try await API.shared.send(chat: chat.id, type: "image", mediaId: id)
                    if !msgs.contains(where: { $0.id == sent.id }) { msgs.append(sent) }
                    msgs.sort { $0.id < $1.id }
                } catch { errMsg = error.localizedDescription }
            }
        }
    }

    // MARK: 顶部栏

    private var header: some View {
        HStack(spacing: 10) {
            Button {
                withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { app.route = .main }
            } label: {
                Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold))
            }
            .foregroundColor(PigeonTheme.accent)

            AvatarView(name: chat.title ?? "?", url: chat.avatar, size: 36)
            VStack(alignment: .leading, spacing: 1) {
                Text(chat.title ?? "会话").font(.headline)
                Text(subtitle).font(.caption).foregroundColor(PigeonTheme.textDim)
            }
            Spacer()
            Menu {
                if chat.type == "group" {
                    Button { showGroupInfo = true } label: {
                        Label("群信息", systemImage: "info.circle")
                    }
                }
                Button(role: .destructive) {
                    Task {
                        do {
                            try await API.shared.clearChat(chat: chat.id)
                            await MainActor.run {
                                msgs = []
                                MsgCache.save(chat.id, [])
                            }
                        } catch { await MainActor.run { errMsg = error.localizedDescription } }
                    }
                } label: {
                    Label("清空聊天记录", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis.circle").font(.system(size: 20))
            }
            .foregroundColor(PigeonTheme.accent)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
    }

    private var subtitle: String {
        if chat.type == "group" { return "\(chat.member_count ?? 0) 位成员" }
        return (chat.peer?.online == true) ? "在线" : "离线"
    }

    // MARK: 消息列表

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 4) {
                    Text("🔒 服务器 3 天后清理，已收到的消息本机永久保留")
                        .font(.caption2)
                        .foregroundColor(PigeonTheme.textDim)
                        .padding(.vertical, 6)

                    ForEach(msgs) { m in
                        bubble(m)
                            .id(m.id)
                    }
                }
                .padding(.horizontal, 12)
            }
            .onChange(of: msgs.count) { _ in
                if let last = msgs.last {
                    withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
        }
    }

    @ViewBuilder
    private func bubble(_ m: Message) -> some View {
        if (m.deleted ?? 0) == 1 {
            Text("\(m.sender.nickname) 撤回了一条消息")
                .font(.caption).foregroundColor(PigeonTheme.textDim)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
        } else if m.type == "system" {
            Text(m.text ?? "")
                .font(.caption).foregroundColor(PigeonTheme.textDim)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
        } else {
            let mine = m.sender_id == app.me?.id
            HStack {
                if mine { Spacer(minLength: 40) }
                VStack(alignment: mine ? .trailing : .leading, spacing: 3) {
                    if chat.type == "group" && !mine {
                        Text(m.sender.nickname).font(.caption).foregroundColor(PigeonTheme.accent)
                    }
                    if let r = m.reply {
                        Text("\(r.nickname ?? "")：\(r.text ?? "")")
                            .font(.caption2).foregroundColor(.white.opacity(0.7))
                            .lineLimit(1)
                            .padding(.leading, 6)
                            .overlay(Rectangle().fill(PigeonTheme.accent.opacity(0.7)).frame(width: 2), alignment: .leading)
                    }
                    if let f = m.fwd_from, !f.isEmpty {
                        Text("转发自 \(f)").font(.caption2).foregroundColor(PigeonTheme.accent)
                    }
                    if m.type == "image", m.media_url != nil {
                        CachedImageView(mediaId: m.media_id, mediaUrl: m.media_url)
                            .frame(width: 220, height: 160)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .contentShape(Rectangle())
                            .onTapGesture { showPhoto = m }
                    }
                    if m.type == "video", m.media_url != nil {
                        HStack(spacing: 8) {
                            Text("🎬")
                            Text("视频消息，点击播放").font(.system(size: 14)).foregroundColor(.white)
                        }
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(mine ? PigeonTheme.outBubble : PigeonTheme.inBubble,
                                    in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .onTapGesture { Task { await playVideo(m) } }
                    }
                    if m.type == "voice", m.media_url != nil {
                        let isPlaying = playingVoiceId == m.id
                        HStack(spacing: 8) {
                            ZStack {
                                Circle().fill(PigeonTheme.accent.opacity(0.22))
                                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(PigeonTheme.accent)
                            }
                            .frame(width: 30, height: 30)
                            Text(isPlaying ? "正在播放…" : "语音").font(.system(size: 14)).foregroundColor(.white)
                            if let d = m.duration { Text("\(d)″").font(.system(size: 12)).foregroundColor(PigeonTheme.textDim) }
                        }
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(mine ? PigeonTheme.outBubble : PigeonTheme.inBubble,
                                    in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .onTapGesture {
                            if playingVoiceId == m.id {
                                audioPlayer?.pause()
                                playingVoiceId = nil
                            } else {
                                Task { await playVoice(m) }
                            }
                        }
                    }
                    if let text = m.text, !text.isEmpty {
                        let disp: String = {
                            if (m.enc ?? 0) == 1 {
                                if let plain = PlainCache.get(m.id) { return plain }
                                return Crypto.decryptText(text) ?? "🔒 加密消息（本机无密钥）"
                            }
                            return text
                        }()
                        Text(disp)
                            .font(.system(size: 16))
                            .foregroundColor(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(mine ? PigeonTheme.outBubble : PigeonTheme.inBubble,
                                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    Text(timeShort(m.created_at))
                        .font(.system(size: 10))
                        .foregroundColor(PigeonTheme.textDim)
                    if mine, chat.type == "private" {
                        let read = m.id <= (chat.peer_read_id ?? 0)
                        Text(read ? "✓✓ 已读" : "✓ 已送达")
                            .font(.system(size: 10))
                            .foregroundColor(read ? PigeonTheme.accent : PigeonTheme.textDim)
                    }
                }
                if !mine { Spacer(minLength: 40) }
            }
            .contextMenu {
                Button { replyTo = m } label: { Label("回复", systemImage: "arrowshape.turn.up.left") }
                Button { forwardMsg = m } label: { Label("转发", systemImage: "arrowshape.turn.up.right") }
                if mine && (m.deleted ?? 0) == 0 {
                    Button(role: .destructive) { Task { await recall(m) } } label: { Label("撤回", systemImage: "arrow.uturn.backward") }
                }
            }
            .padding(.vertical, 2)
        }
    }

    // MARK: 输入栏

    private var composer: some View {
        VStack(spacing: 6) {
            if recording {
                Text("🔴 录音中… 再点一下红色停止键发送")
                    .font(.caption).foregroundColor(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if let r = replyTo {
                HStack(spacing: 8) {
                    Text("回复 \(r.sender.nickname)：\(r.text ?? "图片")")
                        .font(.caption).foregroundColor(PigeonTheme.textDim).lineLimit(1)
                    Spacer()
                    Button { replyTo = nil } label: {
                        Image(systemName: "xmark.circle.fill").foregroundColor(PigeonTheme.accent)
                    }
                }
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(PigeonTheme.inBubble, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            HStack(spacing: 10) {
                Button {
                    if recording { stopRecording() } else { startRecording() }
                } label: {
                    Image(systemName: recording ? "stop.fill" : "mic.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(recording ? .red : PigeonTheme.accent)
                        .frame(width: 34, height: 34)
                        .background(PigeonTheme.inBubble, in: Circle())
                }
                if chat.type == "group" {
                    Button { showMention = true } label: {
                        Text("@")
                            .font(.system(size: 17, weight: .bold))
                            .foregroundColor(PigeonTheme.accent)
                            .frame(width: 34, height: 34)
                            .background(PigeonTheme.inBubble, in: Circle())
                    }
                }
                Button { showPicker = true } label: {
                    Image(systemName: "photo")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(PigeonTheme.accent)
                        .frame(width: 34, height: 34)
                        .background(PigeonTheme.inBubble, in: Circle())
                }
                TextField("消息", text: $draft)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(PigeonTheme.inBubble, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                Button(action: send) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 34, height: 34)
                        .background(PigeonTheme.accent, in: Circle())
                }
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }

    // MARK: 动作

    private func open() async {
        loading = true
        defer { loading = false }
        let cached = MsgCache.load(chat.id)
        if !cached.isEmpty { msgs = cached }
        do {
            let remote = try await API.shared.messages(chat: chat.id)
            var map: [Int: Message] = [:]
            cached.forEach { map[$0.id] = $0 }
            remote.forEach { map[$0.id] = $0 }
            msgs = map.values.sorted { $0.id < $1.id }
            MsgCache.save(chat.id, msgs)
            if let last = msgs.last {
                try? await API.shared.markRead(chat: chat.id, lastId: last.id)
            }
            await poll()
        } catch {
            errMsg = error.localizedDescription
        }
    }

    private func poll() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard app.route == .chat(chat) else { return }
            do {
                let s = try await API.shared.sync(cursor: app.cursor)
                if let new = s.messages, !new.isEmpty {
                    var gotNew = false
                    for m in new where m.chat_id == chat.id {
                        if !msgs.contains(where: { $0.id == m.id }) { msgs.append(m); if m.sender_id != app.me?.id { gotNew = true } }
                        if m.sender_id != app.me?.id { try? await API.shared.markRead(chat: chat.id, lastId: m.id) }
                    }
                    msgs.sort { $0.id < $1.id }
                    MsgCache.save(chat.id, msgs)
                    if gotNew { AudioServicesPlaySystemSound(1003) }
                }
                if let cursor = s.cursor, cursor > app.cursor {
                    app.cursor = cursor
                    UserDefaults.standard.set(cursor, forKey: "cursor")
                    Task { try? await API.shared.ack(cursor) }
                }
            } catch {
                if error.localizedDescription.contains("登录已失效") { await MainActor.run { app.logout() } }
            }
        }
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let rp = replyTo
        replyTo = nil
        draft = ""
        let clientId = "tmp-" + UUID().uuidString.prefix(8)
        let tmp = Message(id: -Int(Date().timeIntervalSince1970), chat_id: chat.id, sender_id: app.me?.id ?? 0,
                          type: "text", text: text, media_id: nil, media_url: nil, reply_to: nil, reply: nil,
                          client_id: clientId, fwd_from: nil, deleted: 0,
                          created_at: nowString(),
                          sender: Sender(id: app.me?.id ?? 0, nickname: app.me?.nickname ?? "我"))
        msgs.append(tmp)
        Task {
            do {
                var encFlag = 0
                var payload = text
                if chat.type == "private", let pk = chat.peer?.public_key, !pk.isEmpty {
                    if let c = Crypto.encryptText(text, peerPubB64: pk) { payload = c; encFlag = 1 }
                }
                let sent = try await API.shared.send(chat: chat.id, type: "text", text: payload, replyTo: rp?.id, clientId: clientId, enc: encFlag)
                if encFlag == 1 { PlainCache.put(sent.id, text) }
                AudioServicesPlaySystemSound(1004)
                msgs.removeAll { $0.client_id == clientId }
                if !msgs.contains(where: { $0.id == sent.id }) { msgs.append(sent) }
                msgs.sort { $0.id < $1.id }
            } catch {
                msgs.removeAll { $0.client_id == clientId }
                await MainActor.run { self.errMsg = error.localizedDescription }
            }
        }
    }

    private func startRecording() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default)
        try? session.setActive(true)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("voice_\(Int(Date().timeIntervalSince1970)).m4a")
        let settings: [String: Any] = [AVFormatIDKey: Int(kAudioFormatMPEG4AAC), AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1]
        do {
            let rec = try AVAudioRecorder(url: url, settings: settings)
            rec.record()
            audioRecorder = rec
            recordURL = url
            recording = true
        } catch { errMsg = error.localizedDescription }
    }

    private func stopRecording() {
        let dur = Int(audioRecorder?.currentTime ?? 0)
        audioRecorder?.stop()
        recording = false
        guard let url = recordURL, let data = try? Data(contentsOf: url), data.count > 200 else { return }
        Task {
            do {
                let id = try await API.shared.uploadImage(data, name: "voice_\(Int(Date().timeIntervalSince1970)).m4a")
                let sent = try await API.shared.send(chat: chat.id, type: "voice", mediaId: id, duration: dur > 0 ? dur : nil)
                if !msgs.contains(where: { $0.id == sent.id }) { msgs.append(sent) }
                msgs.sort { $0.id < $1.id }
            } catch { errMsg = error.localizedDescription }
        }
    }

    private func playVideo(_ m: Message) async {
        guard let mediaId = m.media_id, let mediaUrl = m.media_url else { return }
        do {
            let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("video_\(mediaId).mp4")
            if !FileManager.default.fileExists(atPath: fileURL.path) {
                let data = try await API.shared.downloadMedia(mediaUrl)
                try data.write(to: fileURL)
            }
            await MainActor.run { playVideoURL = fileURL; showVideoPlay = true }
        } catch { errMsg = error.localizedDescription }
    }

    private func playVoice(_ m: Message) async {
        guard let mediaId = m.media_id, let mediaUrl = m.media_url else { return }
        do {
            let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("voice_\(mediaId).m4a")
            if !FileManager.default.fileExists(atPath: fileURL.path) {
                let data = try await API.shared.downloadMedia(mediaUrl)
                try data.write(to: fileURL)
            }
            audioPlayer?.pause()
            let player = try AVAudioPlayer(contentsOf: fileURL)
            player.play()
            audioPlayer = player
            playingVoiceId = m.id
        } catch { errMsg = error.localizedDescription }
    }

    private func recall(_ m: Message) async {
        do {
            try await API.shared.recall(chat: chat.id, message: m.id)
            if let i = msgs.firstIndex(where: { $0.id == m.id }) { msgs[i].deleted = 1 }
        } catch {
            await MainActor.run { errMsg = error.localizedDescription }
        }
    }

    private func nowString() -> String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd HH:mm:ss"; return f.string(from: Date())
    }
}

// MARK: - 转发选择会话

struct ForwardSheet: View {
    @EnvironmentObject var app: AppState
    let from: Message
    let currentChatId: Int
    var onPick: (Chat) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ZStack {
                LiquidBackground()
                List {
                    ForEach(app.chats, id: \.id) { c in
                        Button {
                            dismiss(); onPick(c)
                        } label: {
                            HStack(spacing: 12) {
                                AvatarView(name: c.title ?? "?", url: c.avatar, size: 40)
                                Text(c.title ?? "会话").foregroundColor(.white)
                                Spacer()
                                if c.id == currentChatId {
                                    Text("当前").font(.caption).foregroundColor(PigeonTheme.textDim)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .listStyle(.plain)
            }
            .navigationTitle("转发到…")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("取消") { dismiss() } }
            }
        }
    }
}

// MARK: - 图片本地缓存显示

struct CachedImageView: View {
    let mediaId: Int?
    let mediaUrl: String?
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    PigeonTheme.inBubble
                    Text("图片加载中…").font(.caption).foregroundColor(PigeonTheme.textDim)
                }
            }
        }
        .task {
            guard let mediaId, let mediaUrl else { return }
            let fm = FileManager.default
            let dir = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("media")
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
            let file = dir.appendingPathComponent("m\(mediaId)")
            if let data = try? Data(contentsOf: file), let img = UIImage(data: data) {
                image = img
            } else if let data = try? await API.shared.downloadMedia(mediaUrl) {
                try? data.write(to: file)
                image = UIImage(data: data)
            }
        }
    }
}

// MARK: - 群信息 / 群管理

struct GroupInfoSheet: View {
    @EnvironmentObject var app: AppState
    let chat: Chat
    @State private var members: [User] = []
    @State private var newTitle = ""
    @State private var showAdd = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ZStack {
                LiquidBackground()
                List {
                    Section("群名称") {
                        HStack(spacing: 10) {
                            TextField("群名称", text: $newTitle)
                                .padding(10)
                                .background(PigeonTheme.inBubble, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                            Button("保存") {
                                Task {
                                    try? await API.shared.renameGroup(chat: chat.id, title: newTitle.trimmingCharacters(in: .whitespaces))
                                    dismiss()
                                }
                            }
                            .foregroundColor(PigeonTheme.accent)
                            .disabled(newTitle.trimmingCharacters(in: .whitespaces).isEmpty)
                        }
                    }
                    Section("成员（\(members.count)）") {
                        ForEach(members) { m in
                            HStack(spacing: 12) {
                                AvatarView(name: m.nickname, url: m.avatar, size: 36)
                                Text(m.nickname).foregroundColor(.white)
                                if m.id == app.me?.id { Text("我").font(.caption).foregroundColor(PigeonTheme.textDim) }
                                Spacer()
                                if m.id != app.me?.id {
                                    Button("移除") {
                                        Task {
                                            try? await API.shared.kickMember(chat: chat.id, userId: m.id)
                                            members = (try? await API.shared.groupMembers(chat: chat.id)) ?? []
                                        }
                                    }
                                    .font(.caption).foregroundColor(.red)
                                }
                            }
                        }
                    }
                    Section {
                        Button("添加成员") { showAdd = true }
                            .foregroundColor(PigeonTheme.accent)
                    }
                }
                .listStyle(.plain)
            }
            .navigationTitle("群信息")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("完成") { dismiss() } }
            }
            .task {
                members = (try? await API.shared.groupMembers(chat: chat.id)) ?? []
                newTitle = chat.title ?? ""
            }
            .sheet(isPresented: $showAdd) {
                AddMembersSheet(chat: chat, existing: members) {
                    Task { members = (try? await API.shared.groupMembers(chat: chat.id)) ?? [] }
                }
                .environmentObject(app)
            }
        }
    }
}

struct AddMembersSheet: View {
    @EnvironmentObject var app: AppState
    let chat: Chat
    let existing: [User]
    var onDone: () -> Void
    @State private var friends: [User] = []
    @State private var selected: Set<Int> = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ZStack {
                LiquidBackground()
                VStack(spacing: 14) {
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
                    Button("添加") {
                        Task {
                            do {
                                try await API.shared.addMembers(chat: chat.id, userIds: Array(selected))
                                dismiss(); onDone()
                            } catch {}
                        }
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity).padding(.vertical, 13)
                    .background(PigeonTheme.accent, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .disabled(selected.isEmpty)
                }
                .padding(20)
            }
            .navigationTitle("添加成员")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("取消") { dismiss() } }
            }
            .task {
                let all = (try? await API.shared.friends()) ?? []
                friends = all.filter { f in !existing.contains(where: { $0.id == f.id }) }
            }
        }
    }
}


// MARK: - 图片全屏查看（缩放、不变形）

struct PhotoZoomView: View {
    let mediaId: Int?
    let mediaUrl: String?
    @Environment(\.dismiss) private var dismiss
    @State private var image: UIImage?
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(scale)
                    .gesture(
                        MagnificationGesture()
                            .onChanged { v in scale = min(max(lastScale * v, 1), 6) }
                            .onEnded { _ in lastScale = scale }
                    )
                    .onTapGesture { dismiss() }
            } else {
                ProgressView().tint(PigeonTheme.accent)
            }
        }
        .task {
            guard let mediaId, let mediaUrl else { return }
            let fm = FileManager.default
            let dir = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("media")
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
            let file = dir.appendingPathComponent("m\(mediaId)")
            if let data = try? Data(contentsOf: file), let img = UIImage(data: data) {
                image = img
            } else if let data = try? await API.shared.downloadMedia(mediaUrl) {
                try? data.write(to: file)
                image = UIImage(data: data)
            }
        }
    }
}
