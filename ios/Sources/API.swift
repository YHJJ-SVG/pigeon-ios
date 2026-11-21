import Foundation
import CommonCrypto

// MARK: - 数据模型（与 API JSON 对齐）

struct User: Codable, Identifiable, Hashable {
    let id: Int
    let username: String
    var nickname: String
    var avatar: String?
    var bio: String?
    var is_private: Int?
    var is_admin: Int?
    var last_seen: String?
    var online: Bool?
    var relation: String?
    var public_key: String? = nil
}

struct ChatMember: Codable, Hashable {
    let id: Int
    let username: String
    var nickname: String
    var avatar: String?
    var last_seen: String?
    var online: Bool?
    var role: String?
    var bio: String?
}

struct Sender: Codable, Hashable {
    let id: Int
    let nickname: String
    var avatar: String?
}

struct ReplyInfo: Codable, Hashable {
    let id: Int
    let text: String?
    let nickname: String?
}

struct Message: Codable, Identifiable, Hashable {
    let id: Int
    let chat_id: Int
    let sender_id: Int
    let type: String
    var text: String?
    var media_id: Int?
    var media_url: String?
    var reply_to: Int?
    var reply: ReplyInfo?
    var client_id: String?
    var fwd_from: String?
    var deleted: Int?
    var duration: Int? = nil
    var enc: Int? = nil
    let created_at: String
    let sender: Sender
}

struct Chat: Codable, Identifiable, Hashable {
    let id: Int
    let type: String
    var title: String?
    var avatar: String?
    var member_count: Int?
    var peer: User?
    var members: [ChatMember]?
    var last_message: Message?
    var unread: Int?
    var typing: Bool?
    var peer_read_id: Int? = nil
    var pinned: Int? = nil
    let updated_at: String?
}

struct FriendRequest: Codable, Identifiable, Hashable {
    let id: Int
    let created_at: String
    var message: String?
    var reply: String?
    var status: String?
    let user: User
}

struct Captcha: Codable {
    let token: String
    let question: String
    let expires_in: Int?
}

// MARK: - API 响应包装

struct OK: Codable {
    let ok: Bool
    let message: String?
}

struct CaptchaResp: Codable { let ok: Bool; let captcha: Captcha }
struct AuthResp: Codable { let ok: Bool; let token: String; let user: User }
struct SyncResp: Codable {
    let ok: Bool
    let cursor: Int?
    let messages: [Message]?
    let chats: [Chat]?
    let retention_days: Int?
    let server_time: String?
}
struct ChatsResp: Codable { let ok: Bool; let chats: [Chat] }
struct MessagesResp: Codable { let ok: Bool; let messages: [Message] }
struct SendResp: Codable { let ok: Bool; let message: Message; let duplicate: Bool? }
struct FriendsResp: Codable { let ok: Bool; let friends: [User] }
struct RequestsResp: Codable {
    let ok: Bool
    let incoming: [FriendRequest]?
    let outgoing: [FriendRequest]?
}
struct UsersResp: Codable { let ok: Bool; let users: [User] }
struct ChatResp: Codable { let ok: Bool; let chat_id: Int; let existed: Bool? }
struct FileResp: Codable { let ok: Bool; let file: FileInfo }
struct FileInfo: Codable { let id: Int; let url: String; let name: String; let mime: String; let size: Int }
struct AckResp: Codable { let ok: Bool; let acked_id: Int? }
struct UpdatesResp: Codable { let ok: Bool; let update: UpdateInfo? }
struct UpdateInfo: Codable { let version: String; let build: Int; let url: String; let notes: String?; let mandatory: Bool; let available: Bool }

// MARK: - API 客户端

final class API {
    static let shared = API()
    let base = URL(string: "http://cheap-host1.cheapyun.com:29883/api")!
    private let session: URLSession

    init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 25
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.urlCache = URLCache(memoryCapacity: 0, diskCapacity: 0, diskPath: nil)
        session = URLSession(configuration: cfg)
    }

    var token: String?
    var device: String = ""

    struct APIError: LocalizedError {
        let msg: String
        var errorDescription: String? { msg }
    }

    func call<T: Decodable>(_ path: String, method: String = "GET", body: [String: Any]? = nil, type: T.Type) async throws -> T {
        let url: URL
        if path.contains("?") {
            url = URL(string: base.absoluteString + "/" + path)!
        } else {
            url = base.appendingPathComponent(path)
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        if let token { req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        var (data, resp) = try await session.data(for: req)
        var code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 200, API.isChallenge(data) {
            if let c = API.solveChallenge(data) {
                AntiBot.cookie = c
                req.setValue("__test=" + c, forHTTPHeaderField: "Cookie")
                (data, resp) = try await session.data(for: req)
                code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            }
        }
        if code == 401 { throw APIError(msg: "登录已失效") }
        if code != 200 {
            var msg: String? = nil
            if let e = try? JSONDecoder().decode(OK.self, from: data) { msg = e.message }
            throw APIError(msg: msg ?? "请求失败 HTTP \(code)")
        }
        let dec = JSONDecoder()
        if let e = try? dec.decode(OK.self, from: data), e.ok == false {
            throw APIError(msg: e.message ?? "请求失败")
        }
        return try dec.decode(T.self, from: data)
    }

    // 图片等直接地址（带 token）
    func mediaURL(_ path: String) -> URL? {
        var comps = URLComponents(string: base.absoluteString + "/media/" + path)
        if let token { comps?.queryItems = [URLQueryItem(name: "t", value: token)] }
        return comps?.url
    }
}

// MARK: - 顶层 API 方法（便捷封装）

extension API {
    func captcha() async throws -> Captcha { (try await call("captcha", type: CaptchaResp.self)).captcha }

    func login(username: String, password: String, captchaToken: String, captchaAnswer: Int) async throws -> AuthResp {
        try await call("auth/login", method: "POST", body: [
            "username": username, "password": password,
            "captcha_token": captchaToken, "captcha_answer": captchaAnswer, "device": device,
        ], type: AuthResp.self)
    }

    func register(username: String, password: String, nickname: String, captchaToken: String, captchaAnswer: Int) async throws -> AuthResp {
        try await call("auth/register", method: "POST", body: [
            "username": username, "password": password, "nickname": nickname,
            "captcha_token": captchaToken, "captcha_answer": captchaAnswer, "device": device,
        ], type: AuthResp.self)
    }

    func sync(cursor: Int) async throws -> SyncResp {
        try await call("sync?cursor=\(cursor)&device=\(device)", type: SyncResp.self)
    }

    func ack(_ lastId: Int) async throws {
        _ = try await call("ack", method: "POST", body: ["device": device, "last_id": lastId], type: AckResp.self)
    }

    func chats() async throws -> [Chat] { (try await call("conversations", type: ChatsResp.self)).chats }

    func messages(chat: Int, limit: Int = 60) async throws -> [Message] {
        (try await call("conversations/\(chat)/messages?limit=\(limit)", type: MessagesResp.self)).messages
    }

    func send(chat: Int, type: String, text: String? = nil, mediaId: Int? = nil, replyTo: Int? = nil, fwdFrom: String? = nil, clientId: String? = nil, duration: Int? = nil, enc: Int = 0) async throws -> Message {
        var body: [String: Any] = ["type": type]
        if let text { body["text"] = text }
        if let mediaId { body["media_id"] = mediaId }
        if let replyTo { body["reply_to"] = replyTo }
        if let fwdFrom { body["fwd_from"] = fwdFrom }
        if let clientId { body["client_id"] = clientId }
        if let duration, duration > 0 { body["duration"] = duration }
        if enc == 1 { body["enc"] = 1 }
        return (try await call("conversations/\(chat)/messages", method: "POST", body: body, type: SendResp.self)).message
    }

    func openPrivate(userId: Int) async throws -> ChatResp {
        try await call("conversations", method: "POST", body: ["type": "private", "user_id": userId], type: ChatResp.self)
    }

    func createGroup(title: String, memberIds: [Int]) async throws -> Int {
        let r: ChatResp = try await call("conversations", method: "POST",
                                         body: ["type": "group", "title": title, "member_ids": memberIds],
                                         type: ChatResp.self)
        return r.chat_id
    }

    func groupMembers(chat: Int) async throws -> [User] {
        struct MembersResp: Codable { let members: [User] }
        return (try await call("conversations/\(chat)/members", type: MembersResp.self)).members
    }

    func renameGroup(chat: Int, title: String) async throws {
        _ = try await call("conversations/\(chat)/rename", method: "POST", body: ["title": title], type: OK.self)
    }

    func kickMember(chat: Int, userId: Int) async throws {
        _ = try await call("conversations/\(chat)/kick", method: "POST", body: ["user_id": userId], type: OK.self)
    }

    func addMembers(chat: Int, userIds: [Int]) async throws {
        _ = try await call("conversations/\(chat)/members", method: "POST", body: ["member_ids": userIds], type: OK.self)
    }

    func downloadMedia(_ path: String) async throws -> Data {
        let url = URL(string: "http://cheap-host1.cheapyun.com:29883" + path + "?t=" + (token ?? ""))!
        var req = URLRequest(url: url)
        if let token { req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        let (data, resp) = try await session.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw APIError(msg: "下载失败") }
        return data
    }

    func recall(chat: Int, message: Int) async throws {
        _ = try await call("conversations/\(chat)/messages/\(message)/recall", method: "POST", type: OK.self)
    }

    func uploadImage(_ data: Data, name: String) async throws -> Int {
        let url = base.appendingPathComponent("upload")
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        if let token { req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        let boundary = "----Pigeon\(Int(Date().timeIntervalSince1970))"
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        var body = Data()
        body.append(Data("--\(boundary)\r\n".utf8))
        body.append(Data("Content-Disposition: form-data; name=\"file\"; filename=\"\(name)\"\r\n".utf8))
        body.append(Data("Content-Type: application/octet-stream\r\n\r\n".utf8))
        body.append(data)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        req.httpBody = body
        let (d, resp) = try await session.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw APIError(msg: "上传失败") }
        struct UpResp: Codable { struct File: Codable { let id: Int }; let file: File }
        return try JSONDecoder().decode(UpResp.self, from: d).file.id
    }

    func markRead(chat: Int, lastId: Int) async throws {
        _ = try await call("conversations/\(chat)/read", method: "POST", body: ["last_id": lastId], type: OK.self)
    }

    func friends() async throws -> [User] { (try await call("friends", type: FriendsResp.self)).friends }

    func pinChat(chat: Int, pinned: Bool) async throws {
        _ = try await call("chats/pin", method: "POST", body: ["chat_id": chat, "pinned": pinned ? 1 : 0], type: OK.self)
    }
    func hideChat(chat: Int) async throws {
        _ = try await call("chats/hide", method: "POST", body: ["chat_id": chat], type: OK.self)
    }
    func clearChat(chat: Int) async throws {
        _ = try await call("chats/clear", method: "POST", body: ["chat_id": chat], type: OK.self)
    }

    func friendRequests() async throws -> RequestsResp { try await call("friends/requests", type: RequestsResp.self) }

    func sendFriendRequest(userId: Int, message: String) async throws {
        _ = try await call("friends/request", method: "POST", body: ["user_id": userId, "message": message], type: OK.self)
    }

    func acceptFriend(_ id: Int) async throws {
        _ = try await call("friends/accept", method: "POST", body: ["id": id], type: OK.self)
    }

    func rejectFriend(_ id: Int, reply: String? = nil) async throws {
        var body: [String: Any] = ["id": id]
        if let reply { body["reply"] = reply }
        _ = try await call("friends/reject", method: "POST", body: body, type: OK.self)
    }

    func deleteFriend(userId: Int) async throws {
        _ = try await call("friends/delete", method: "POST", body: ["user_id": userId], type: OK.self)
    }

    func searchUsers(_ q: String) async throws -> [User] {
        let enc = q.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? q
        return (try await call("users?q=\(enc)", type: UsersResp.self)).users
    }

    func updateMe(nickname: String? = nil, bio: String? = nil, isPrivate: Int? = nil, avatar: String? = nil, publicKey: String? = nil) async throws -> User {
        var body: [String: Any] = [:]
        if let nickname { body["nickname"] = nickname }
        if let bio { body["bio"] = bio }
        if let isPrivate { body["is_private"] = isPrivate }
        if let avatar { body["avatar"] = avatar }
        if let publicKey { body["public_key"] = publicKey }
        struct MeResp: Codable { let ok: Bool; let user: User; let retention_days: Int? }
        return (try await call("me", method: "POST", body: body, type: MeResp.self)).user
    }

    func me() async throws -> User {
        struct MeResp: Codable { let ok: Bool; let user: User; let retention_days: Int? }
        return (try await call("me", type: MeResp.self)).user
    }
}
