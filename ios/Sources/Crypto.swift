import Foundation
import Security
import CryptoKit

// MARK: - 端到端加密：RSA-2048 密钥对（私钥只存本地，公钥上传服务端）

enum Crypto {
    private static let pubKey = "rsa_pub"
    private static let privKey = "rsa_priv"

    /// 确保本机有密钥对，返回公钥（PKCS#1 DER 的 Base64，与 Android 互通）
    static func ensureKeys() -> String {
        if let existing = UserDefaults.standard.string(forKey: pubKey), !existing.isEmpty {
            return existing
        }
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
        ]
        guard let priv = SecKeyCreateRandomKey(attrs as CFDictionary, nil),
              let pub = SecKeyCopyPublicKey(priv) else {
            return ""
        }
        var err: Unmanaged<CFError>?
        guard let pubData = SecKeyCopyExternalRepresentation(pub, &err) as Data? else {
            return ""
        }
        let pubB64 = pubData.base64EncodedString()
        if let privData = SecKeyCopyExternalRepresentation(priv, &err) as Data? {
            UserDefaults.standard.set(privData.base64EncodedString(), forKey: privKey)
        }
        UserDefaults.standard.set(pubB64, forKey: pubKey)
        return pubB64
    }

    static func publicKeyFromPKCS1(_ b64: String) -> SecKey? {
        guard let data = Data(base64Encoded: b64) else { return nil }
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass as String: kSecAttrKeyClassPublic,
        ]
        return SecKeyCreateWithData(data as CFData, attrs as CFDictionary, nil)
    }

    static func privateKey() -> SecKey? {
        guard let b64 = UserDefaults.standard.string(forKey: privKey),
              let data = Data(base64Encoded: b64) else { return nil }
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
        ]
        return SecKeyCreateWithData(data as CFData, attrs as CFDictionary, nil)
    }

    /// 发送端加密：AES-256-GCM 数据密钥 + 接收方 RSA 公钥包裹
    static func encryptText(_ plain: String, peerPubB64: String) -> String? {
        guard let pub = publicKeyFromPKCS1(peerPubB64),
              let plainData = plain.data(using: .utf8) else { return nil }
        let key = SymmetricKey(size: .bits256)
        let nonce = AES.GCM.Nonce()
        guard let sealed = try? AES.GCM.seal(plainData, using: key, nonce: nonce) else { return nil }
        let keyData = key.withUnsafeBytes { Data($0) }
        var err: Unmanaged<CFError>?
        guard let wrapped = SecKeyCreateEncryptedData(pub, .rsaEncryptionOAEPSHA256, keyData as CFData, &err) as Data? else { return nil }

        var out = Data()
        out.append(1)
        out.append(nonce.withUnsafeBytes { Data($0) })
        var lenW = UInt16(wrapped.count).bigEndian
        out.append(Data(bytes: &lenW, count: 2))
        out.append(wrapped)
        out.append(sealed.ciphertext)
        out.append(sealed.tag)
        return out.base64EncodedString()
    }

    /// 接收端解密
    static func decryptText(_ b64: String) -> String? {
        guard let data = Data(base64Encoded: b64), data.count > 16, data[0] == 1,
              let priv = privateKey() else { return nil }
        let nonceData = data.subdata(in: 1..<13)
        let lenW = (Int(data[13]) << 8) | Int(data[14])
        guard data.count >= 15 + lenW else { return nil }
        let wrapped = data.subdata(in: 15..<(15 + lenW))
        let ct = data.subdata(in: (15 + lenW)..<data.count)
        var err: Unmanaged<CFError>?
        guard let keyData = SecKeyCreateDecryptedData(priv, .rsaEncryptionOAEPSHA256, wrapped as CFData, &err) as Data?,
              let nonce = try? AES.GCM.Nonce(data: nonceData) else { return nil }
        let key = SymmetricKey(data: keyData)
        let combined = nonceData + ct
        guard let box = try? AES.GCM.SealedBox(combined: combined),
              let dec = try? AES.GCM.open(box, using: key),
              let str = String(data: dec, encoding: .utf8) else { return nil }
        return str
    }
}

// MARK: - 自己发出的端到端加密消息：服务端只有密文，本地存明文用于显示

enum PlainCache {
    private static let prefix = "plain_"

    static func put(_ msgId: Int, _ text: String) {
        UserDefaults.standard.set(text, forKey: prefix + String(msgId))
    }

    static func get(_ msgId: Int) -> String? {
        UserDefaults.standard.string(forKey: prefix + String(msgId))
    }
}

// MARK: - 本地消息缓存：服务器 3 天后清理，本机收到过的消息永久保留

enum MsgCache {
    private static func url(_ chatId: Int) -> URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("msgs")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("c\(chatId).json")
    }

    static func save(_ chatId: Int, _ msgs: [Message]) {
        let tail = Array(msgs.suffix(300))
        guard let data = try? JSONEncoder().encode(tail) else { return }
        try? data.write(to: url(chatId))
    }

    static func load(_ chatId: Int) -> [Message] {
        guard let data = try? Data(contentsOf: url(chatId)),
              let msgs = try? JSONDecoder().decode([Message].self, from: data) else { return [] }
        return msgs
    }
}
