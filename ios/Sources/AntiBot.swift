import Foundation
import CommonCrypto

// MARK: - 傲云/InfinityFree 的 JS 人机校验（__test cookie）绕过
//
// 服务端返回一段 HTML：
//   var a=toNumbers("<key>"),b=toNumbers("<iv>"),c=toNumbers("<ct>")
//   document.cookie="__test="+toHex(slowAES.decrypt(c,2,a,b))
// 等价于 AES-128-CBC 解密，明文 16 字节的 hex 就是 __test 的值。

enum AntiBot {
    static var cookie: String? {
        get {
            let d = UserDefaults.standard
            if let v = d.string(forKey: "antibot_cookie"),
               let exp = d.object(forKey: "antibot_exp") as? Date,
               exp > Date() {
                return v
            }
            return nil
        }
        set {
            let d = UserDefaults.standard
            if let v = newValue, !v.isEmpty {
                d.set(v, forKey: "antibot_cookie")
                d.set(Date().addingTimeInterval(5.5 * 3600), forKey: "antibot_exp")
            } else {
                d.removeObject(forKey: "antibot_cookie")
                d.removeObject(forKey: "antibot_exp")
            }
        }
    }

    static func header() -> String? {
        guard let c = cookie else { return nil }
        return "__test=" + c
    }
}

extension API {
    static func isChallenge(_ data: Data) -> Bool {
        guard data.count < 4000, let s = String(data: data, encoding: .utf8) else { return false }
        return s.contains("slowAES") || s.contains("toNumbers")
    }

    /// 解析挑战页并算出 __test 值
    static func solveChallenge(_ data: Data) -> String? {
        guard let html = String(data: data, encoding: .utf8) else { return nil }
        // 抓取页面里前三个 toNumbers("hex") —— 依次是 key、iv、密文
        var hexes: [String] = []
        if let re = try? NSRegularExpression(pattern: "toNumbers\\(\"([0-9a-fA-F]+)\"\\)") {
            let ns = html as NSString
            let matches = re.matches(in: html, range: NSRange(location: 0, length: ns.length))
            for m in matches where m.numberOfRanges > 1 {
                hexes.append(ns.substring(with: m.range(at: 1)))
                if hexes.count == 3 { break }
            }
        }
        guard hexes.count == 3,
              let keyData = Data(hex: hexes[0]), let ivData = Data(hex: hexes[1]), let ctData = Data(hex: hexes[2]),
              keyData.count == 16, ivData.count == 16, ctData.count == 16 else { return nil }

        var out = [UInt8](repeating: 0, count: ctData.count + kCCBlockSizeAES128)
        var moved = 0
        let status = keyData.withUnsafeBytes { kp in
            ivData.withUnsafeBytes { ip in
                ctData.withUnsafeBytes { cp in
                    CCCrypt(CCOperation(kCCDecrypt), CCAlgorithm(kCCAlgorithmAES), 0,
                            kp.baseAddress, keyData.count,
                            ip.baseAddress,
                            cp.baseAddress, ctData.count,
                            &out, out.count, &moved)
                }
            }
        }
        guard status == kCCSuccess else { return nil }
        return out.prefix(moved).map { String(format: "%02x", $0) }.joined()
    }
}

extension Data {
    init?(hex: String) {
        let h = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        guard h.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(h.count / 2)
        var idx = h.startIndex
        while idx < h.endIndex {
            let next = h.index(idx, offsetBy: 2)
            guard let v = UInt8(h[idx..<next], radix: 16) else { return nil }
            bytes.append(v)
            idx = next
        }
        self.init(bytes)
    }
}
