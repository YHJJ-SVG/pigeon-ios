package top.bingtangyun.pigeon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Build
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// ---------- 主题色 ----------
object C {
    val BG = Color(0xFF070B12)
    val Nav = Color(0xFF101824)
    val Card = Color(0xFF16202D)
    val Accent = Color(0xFF5EB5F7)
    val In = Color(0xFF182533)
    val Out = Color(0xFF2B5278)
    val Dim = Color(0xFF708499)
    val White = Color(0xFFFFFFFF)
    val Danger = Color(0xFFE05A5A)
}

// ---------- 数据模型 ----------
data class User(
    val id: Int, val username: String, val nickname: String,
    val avatar: String?, val isPrivate: Int = 0, val online: Boolean = false,
    val relation: String? = null, val bio: String? = null, val isAdmin: Int = 0
)

data class Sender(val id: Int, val nickname: String, val avatar: String? = null)

data class Message(
    val id: Int, val chatId: Int, val senderId: Int, val type: String,
    val text: String?, val mediaUrl: String?, val mediaId: Int?, val createdAt: String,
    val deleted: Int = 0, val sender: Sender, val fwdFrom: String? = null,
    val replyNick: String? = null, val replyText: String? = null, val duration: Int? = null, val enc: Int = 0
)

data class Chat(
    val id: Int, val type: String, val title: String, val avatar: String?,
    val unread: Int = 0, val lastText: String? = null, val lastTime: String? = null,
    val memberCount: Int = 0, val online: Boolean = false, val peerReadId: Int? = null,
    val peerPubKey: String? = null, val pinned: Int = 0, val notice: String = ""
)

data class FriendRequest(
    val id: Int, val user: User, val message: String,
    val status: String? = null, val reply: String? = null
)

data class Captcha(val token: String, val question: String)

class ApiException(msg: String) : Exception(msg)

// 端到端加密：RSA-2048 密钥对，私钥只存本地，公钥上传服务端
object Crypto {
    private lateinit var prefs: android.content.SharedPreferences
    fun init(ctx: android.content.Context) {
        prefs = ctx.getSharedPreferences("pigeon_crypto", android.content.Context.MODE_PRIVATE)
    }

    // ---- DER 编码（PKCS#1 RSAPublicKey） ----
    private fun derLen(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), ((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte())
    }
    private fun derInt(v: ByteArray): ByteArray {
        var b = v
        var i = 0
        while (i < b.size - 1 && b[i].toInt() == 0) i++
        b = b.copyOfRange(i, b.size)
        if ((b[0].toInt() and 0x80) != 0) b = byteArrayOf(0) + b
        return byteArrayOf(0x02) + derLen(b.size) + b
    }
    private fun pkcs1(pub: java.security.interfaces.RSAPublicKey): String {
        val mod = derInt(pub.modulus.toByteArray())
        val exp = derInt(pub.publicExponent.toByteArray())
        val inner = mod + exp
        val der = byteArrayOf(0x30) + derLen(inner.size) + inner
        return android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP)
    }

    // ---- DER 解析 ----
    private fun readLen(b: ByteArray, start: Int): Pair<Int, Int> {
        val first = b[start].toInt() and 0xFF
        if (first < 0x80) return first to (start + 1)
        val n = first and 0x7F
        var v = 0
        for (j in 1..n) v = (v shl 8) or (b[start + j].toInt() and 0xFF)
        return v to (start + 1 + n)
    }
    private fun readInteger(b: ByteArray, start: Int): Pair<ByteArray, Int> {
        val len = readLen(b, start + 1)
        val vs = len.second
        return (b.copyOfRange(vs, vs + len.first)) to (vs + len.first)
    }
    fun rsaPublicFromPkcs1(b64: String): java.security.PublicKey? {
        return try {
            val der = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            val seqLen = readLen(der, 1)
            var i = seqLen.second
            val mod = readInteger(der, i); i = mod.second
            val exp = readInteger(der, i)
            val spec = java.security.spec.RSAPublicKeySpec(
                java.math.BigInteger(1, mod.first), java.math.BigInteger(1, exp.first))
            java.security.KeyFactory.getInstance("RSA").generatePublic(spec)
        } catch (_: Exception) { null }
    }

    fun ensureKeys(): String {
        prefs.getString("pub", null)?.let { existing ->
            // 校验旧 key 是否合法，不合法则重生成
            if (rsaPublicFromPkcs1(existing) != null) return existing
        }
        try {
            val kpg = java.security.KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val pub = pkcs1(kp.public as java.security.interfaces.RSAPublicKey)
            if (rsaPublicFromPkcs1(pub) == null) return ""
            val priv = android.util.Base64.encodeToString(kp.private.encoded, android.util.Base64.NO_WRAP)
            prefs.edit().putString("pub", pub).putString("priv", priv).apply()
            return pub
        } catch (_: Exception) { return "" }
    }
    fun privateKey(): java.security.PrivateKey? {
        val priv = prefs.getString("priv", null) ?: return null
        return try {
            val kf = java.security.KeyFactory.getInstance("RSA")
            kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(android.util.Base64.decode(priv, android.util.Base64.NO_WRAP)))
        } catch (_: Exception) { null }
    }

    // ---- 端到端加密（AES-256-GCM + RSA-OAEP-SHA256 混合） ----
    fun encryptText(plain: String, peerPubB64: String): String? {
        return try {
            val peerPub = rsaPublicFromPkcs1(peerPubB64) ?: return null
            val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, nonce))
            val ct = aes.doFinal(plain.toByteArray(Charsets.UTF_8))
            val rsa = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            rsa.init(javax.crypto.Cipher.ENCRYPT_MODE, peerPub)
            val wrapped = rsa.doFinal(key)
            val out = ByteArray(1 + 12 + 2 + wrapped.size + ct.size)
            out[0] = 1
            System.arraycopy(nonce, 0, out, 1, 12)
            out[13] = ((wrapped.size shr 8) and 0xFF).toByte()
            out[14] = (wrapped.size and 0xFF).toByte()
            System.arraycopy(wrapped, 0, out, 15, wrapped.size)
            System.arraycopy(ct, 0, out, 15 + wrapped.size, ct.size)
            android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }
    fun decryptText(dataB64: String): String? {
        return try {
            val data = android.util.Base64.decode(dataB64, android.util.Base64.NO_WRAP)
            if (data.size < 16 || data[0].toInt() != 1) return null
            val nonce = data.copyOfRange(1, 13)
            val lenW = ((data[13].toInt() and 0xFF) shl 8) or (data[14].toInt() and 0xFF)
            val wrapped = data.copyOfRange(15, 15 + lenW)
            val ct = data.copyOfRange(15 + lenW, data.size)
            val priv = privateKey() ?: return null
            val rsa = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            rsa.init(javax.crypto.Cipher.DECRYPT_MODE, priv)
            val key = rsa.doFinal(wrapped)
            val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, nonce))
            String(aes.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }
}

// 登录态本地持久化：第二次打开不用重新登录
object Session {
    private lateinit var prefs: android.content.SharedPreferences
    fun init(ctx: android.content.Context) {
        prefs = ctx.getSharedPreferences("pigeon", android.content.Context.MODE_PRIVATE)
        Api.token = prefs.getString("token", null)
    }
    fun save(token: String, user: User) {
        Api.token = token
        prefs.edit().putString("token", token).putInt("uid", user.id)
            .putString("username", user.username).putString("nickname", user.nickname).apply()
    }
    fun user(): User? {
        val id = prefs.getInt("uid", -1)
        if (id <= 0) return null
        return User(id, prefs.getString("username", "") ?: "", prefs.getString("nickname", "") ?: "", null)
    }
    fun clear() { Api.token = null; prefs.edit().clear().apply() }
}

// 媒体本地缓存：图片/视频下载到设备本地，服务器删除后仍可看
object MediaCache {
    lateinit var dir: File
    fun init(ctx: android.content.Context) { dir = File(ctx.filesDir, "media"); dir.mkdirs() }
    fun file(mediaId: Int) = File(dir, "m$mediaId")
    fun has(mediaId: Int) = file(mediaId).exists() && file(mediaId).length() > 0
    fun save(mediaId: Int, bytes: ByteArray) { runCatching { file(mediaId).writeBytes(bytes) } }
    fun read(mediaId: Int): ByteArray? = if (has(mediaId)) runCatching { file(mediaId).readBytes() }.getOrNull() else null
}

// 自己发出的端到端加密消息：服务端只有密文，本地记录明文用于显示
object PlainCache {
    private var prefs: android.content.SharedPreferences? = null
    fun init(ctx: android.content.Context) {
        prefs = ctx.getSharedPreferences("pigeon_plain", android.content.Context.MODE_PRIVATE)
    }
    fun put(msgId: Int, text: String) { prefs?.edit()?.putString("m$msgId", text)?.apply() }
    fun get(msgId: Int): String? = prefs?.getString("m$msgId", null)
}

// 本地消息缓存：服务器 3 天后清理，但本机收到过的消息永久保留
object MsgCache {
    private var prefs: android.content.SharedPreferences? = null
    fun init(ctx: android.content.Context) {
        prefs = ctx.getSharedPreferences("pigeon_msgs", android.content.Context.MODE_PRIVATE)
    }
    fun save(chatId: Int, msgs: List<Message>) {
        val arr = org.json.JSONArray()
        msgs.takeLast(300).forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id); put("chat_id", m.chatId); put("sender_id", m.senderId)
                put("type", m.type); put("text", m.text); put("media_url", m.mediaUrl)
                put("media_id", m.mediaId); put("created_at", m.createdAt)
                put("deleted", m.deleted); put("duration", m.duration); put("enc", m.enc)
                put("sender_name", m.sender.nickname); put("sender_id2", m.sender.id)
            })
        }
        prefs?.edit()?.putString("c$chatId", arr.toString())?.apply()
    }
    fun load(chatId: Int): List<Message> {
        val raw = prefs?.getString("c$chatId", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Message(
                    id = o.optInt("id"), chatId = o.optInt("chat_id"), senderId = o.optInt("sender_id"),
                    type = o.optString("type"), text = if (o.isNull("text")) null else o.optString("text"),
                    mediaUrl = if (o.isNull("media_url")) null else o.optString("media_url"),
                    mediaId = if (o.isNull("media_id")) null else o.optInt("media_id"),
                    createdAt = o.optString("created_at"), deleted = o.optInt("deleted"),
                    sender = Sender(o.optInt("sender_id2"), o.optString("sender_name")),
                    duration = if (o.isNull("duration")) null else o.optInt("duration"),
                    enc = o.optInt("enc")
                )
            }
        } catch (e: Exception) { emptyList() }
    }
}

// 傲云/InfinityFree 的 JS 人机校验（__test cookie）
object AntiBot {
    private var prefs: android.content.SharedPreferences? = null
    private var cookie: String? = null

    fun init(ctx: android.content.Context) {
        val p = ctx.getSharedPreferences("pigeon_antibot", android.content.Context.MODE_PRIVATE)
        prefs = p
        val v = p.getString("cookie", null)
        val exp = p.getLong("exp", 0L)
        cookie = if (v != null && System.currentTimeMillis() < exp) v else null
    }

    fun header(): String? = cookie?.let { "__test=$it" }

    fun isChallenge(t: String): Boolean =
        t.length < 4000 && (t.contains("slowAES") || t.contains("toNumbers"))

    private fun hexToBytes(h: String): ByteArray? {
        if (h.length % 2 != 0) return null
        val out = ByteArray(h.length / 2)
        for (i in out.indices) {
            val v = h.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = v.toByte()
        }
        return out
    }

    fun solve(html: String): String? {
        val hexes = Regex("toNumbers\\(\"([0-9a-fA-F]+)\"\\)")
            .findAll(html).map { it.groupValues[1] }.take(3).toList()
        if (hexes.size < 3) return null
        val key = hexToBytes(hexes[0]) ?: return null
        val iv = hexToBytes(hexes[1]) ?: return null
        val ct = hexToBytes(hexes[2]) ?: return null
        if (key.size != 16 || iv.size != 16 || ct.size != 16) return null
        return try {
            val c = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.IvParameterSpec(iv))
            val pt = c.doFinal(ct)
            val out = pt.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
            cookie = out
            prefs?.edit()?.putString("cookie", out)
                ?.putLong("exp", System.currentTimeMillis() + 5L * 3600 * 1000)?.apply()
            out
        } catch (e: Exception) { null }
    }
}

// ---------- API ----------
object Api {
    private const val BASE = "http://cheap-host1.cheapyun.com:29883/api"
    var token: String? = null
    var device: String = "d" + UUID.randomUUID().toString().replace("-", "").take(16)

    fun get(path: String): String = request(path, "GET", null)
    fun post(path: String, body: JSONObject = JSONObject()): String = request(path, "POST", body)

    private fun request(path: String, method: String, body: JSONObject?): String {
        val raw = body?.toString()?.toByteArray(Charsets.UTF_8)
        var text = perform(BASE + "/" + path, method, raw)
        if (text != null && AntiBot.isChallenge(text)) {
            AntiBot.solve(text)?.let { ck ->
                text = perform(BASE + "/" + path, method, raw)
            }
        }
        val t = text ?: throw ApiException("请求失败")
        if (t.startsWith("__HTTP401__")) throw ApiException("登录已失效")
        if (t.startsWith("__HTTP")) {
            val code = t.removePrefix("__HTTP").substringBefore("__")
            val msg = runCatching { JSONObject(t.substringAfter("__", "")).optString("message") }.getOrNull()
            throw ApiException(if (!msg.isNullOrEmpty()) msg else "请求失败 HTTP $code")
        }
        return t
    }

    /** 返回响应体；非 2xx 时返回 __HTTP<code>__<body> */
    private fun perform(urlStr: String, method: String, bodyBytes: ByteArray?): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection)
            conn.requestMethod = method
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            AntiBot.header()?.let { conn.setRequestProperty("Cookie", it) }
            if (bodyBytes != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(bodyBytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val t = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            conn.disconnect()
            if (code in 200..299) t else "__HTTP${code}__$t"
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 解析辅助 ----------
    fun user(o: JSONObject): User = User(
        id = o.optInt("id"), username = o.optString("username"), nickname = o.optString("nickname"),
        avatar = if (o.isNull("avatar")) null else o.optString("avatar"),
        isPrivate = o.optInt("is_private"), online = o.optBoolean("online", false),
        relation = if (o.isNull("relation")) null else o.optString("relation"),
        bio = if (o.isNull("bio")) null else o.optString("bio"), isAdmin = o.optInt("is_admin")
    )

    fun captcha(): Captcha {
        val o = JSONObject(get("captcha")).getJSONObject("captcha")
        return Captcha(o.getString("token"), o.getString("question"))
    }

    fun login(username: String, password: String, token: String, answer: Int): Pair<String, User> {
        val o = JSONObject(post("auth/login", JSONObject().apply {
            put("username", username); put("password", password)
            put("captcha_token", token); put("captcha_answer", answer); put("device", device)
        }))
        return o.getString("token") to user(o.getJSONObject("user"))
    }

    fun register(username: String, password: String, nickname: String, token: String, answer: Int): Pair<String, User> {
        val o = JSONObject(post("auth/register", JSONObject().apply {
            put("username", username); put("password", password); put("nickname", nickname)
            put("captcha_token", token); put("captcha_answer", answer); put("device", device)
        }))
        return o.getString("token") to user(o.getJSONObject("user"))
    }

    fun me(): User = user(JSONObject(get("me")).getJSONObject("user"))

    fun chats(): List<Chat> {
        val arr = JSONObject(get("conversations")).getJSONArray("chats")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val lm = if (o.isNull("last_message")) null else o.getJSONObject("last_message")
            val peer = if (o.isNull("peer")) null else o.getJSONObject("peer")
            Chat(
                id = o.getInt("id"), type = o.optString("type"),
                title = o.optString("title", "").ifBlank { peer?.optString("nickname") ?: "会话" },
                avatar = if (o.isNull("avatar")) (peer?.let { if (it.isNull("avatar")) null else it.optString("avatar") }) else o.optString("avatar"),
                unread = o.optInt("unread"),
                lastText = lm?.let { previewText(it) }, lastTime = lm?.optString("created_at"),
                memberCount = o.optInt("member_count"),
                online = peer?.optBoolean("online", false) ?: false,
                peerReadId = if (o.isNull("peer_read_id")) null else o.optInt("peer_read_id"),
                peerPubKey = peer?.let { if (it.isNull("public_key")) null else it.optString("public_key") },
                pinned = o.optInt("pinned", 0),
                notice = if (o.isNull("notice")) "" else o.optString("notice", "")
            )
        }
    }

    private fun previewText(m: JSONObject): String {
        if (m.optInt("deleted") == 1) return "消息已撤回"
        return when (m.optString("type")) {
            "image" -> "📷 图片"; "voice" -> "🎤 语音"; "file" -> "📎 文件"; "video" -> "🎬 视频"
            else -> if (m.optInt("enc") == 1) {
                PlainCache.get(m.optInt("id"))
                    ?: Crypto.decryptText(m.optString("text"))
                    ?: "🔒 加密消息"
            } else m.optString("text")
        }
    }

    fun messages(chatId: Int): List<Message> {
        val arr = JSONObject(get("conversations/$chatId/messages?limit=80")).getJSONArray("messages")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val s = o.getJSONObject("sender")
            val r = if (o.isNull("reply")) null else o.optJSONObject("reply")
            Message(
                id = o.getInt("id"), chatId = o.getInt("chat_id"), senderId = o.getInt("sender_id"),
                type = o.optString("type"), text = if (o.isNull("text")) null else o.optString("text"),
                mediaUrl = if (o.isNull("media_url")) null else o.optString("media_url"),
                mediaId = if (o.isNull("media_id")) null else o.optInt("media_id"),
                createdAt = o.optString("created_at"), deleted = o.optInt("deleted"),
                sender = Sender(s.getInt("id"), s.optString("nickname"),
                    if (s.isNull("avatar")) null else s.optString("avatar")),
                fwdFrom = if (o.isNull("fwd_from")) null else o.optString("fwd_from"),
                replyNick = r?.optString("nickname"), replyText = r?.optString("text"),
                duration = if (o.isNull("duration")) null else o.optInt("duration"),
                enc = o.optInt("enc")
            )
        }
    }

    fun send(chatId: Int, text: String, replyTo: Int? = null, fwdFrom: String? = null, enc: Int = 0): Message {
        val o = JSONObject(post("conversations/$chatId/messages", JSONObject().apply {
            put("type", "text"); put("text", text); put("client_id", "c-" + UUID.randomUUID().toString().take(8))
            if (replyTo != null) put("reply_to", replyTo)
            if (fwdFrom != null) put("fwd_from", fwdFrom)
            if (enc == 1) put("enc", 1)
        })).getJSONObject("message")
        return Message(
            id = o.getInt("id"), chatId = o.getInt("chat_id"), senderId = o.getInt("sender_id"),
            type = "text", text = o.optString("text"), mediaUrl = null, mediaId = null, createdAt = o.optString("created_at"),
            sender = Sender(o.getJSONObject("sender").getInt("id"), o.getJSONObject("sender").optString("nickname"))
        )
    }

    fun recall(chatId: Int, msgId: Int) { post("conversations/$chatId/messages/$msgId/recall") }

    fun uploadImage(bytes: ByteArray, name: String): Int {
        val boundary = "----Pigeon" + System.currentTimeMillis()
        fun once(): Pair<Int, String> {
            val conn = (URL(BASE + "/upload").openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 30000; conn.readTimeout = 60000
            conn.setRequestProperty("Authorization", "Bearer " + (token ?: ""))
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            AntiBot.header()?.let { conn.setRequestProperty("Cookie", it) }
            conn.outputStream.use { os ->
                os.write(("--$boundary\r\n").toByteArray())
                os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n").toByteArray())
                os.write(("Content-Type: application/octet-stream\r\n\r\n").toByteArray())
                os.write(bytes)
                os.write(("\r\n--$boundary--\r\n").toByteArray())
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            conn.disconnect()
            return code to text
        }
        var (code, text) = once()
        if (AntiBot.isChallenge(text)) {
            if (AntiBot.solve(text) != null) {
                val r = once(); code = r.first; text = r.second
            }
        }
        if (code !in 200..299) throw ApiException("上传失败")
        return JSONObject(text).getJSONObject("file").getInt("id")
    }

    fun sendVideo(chatId: Int, mediaId: Int): Message {
        val o = JSONObject(post("conversations/$chatId/messages", JSONObject().apply {
            put("type", "video"); put("media_id", mediaId); put("client_id", "c-" + UUID.randomUUID().toString().take(8))
        })).getJSONObject("message")
        return Message(
            id = o.getInt("id"), chatId = o.getInt("chat_id"), senderId = o.getInt("sender_id"),
            type = "video", text = null, mediaUrl = "/api/media/" + mediaId, mediaId = mediaId,
            createdAt = o.optString("created_at"),
            sender = Sender(o.getJSONObject("sender").getInt("id"), o.getJSONObject("sender").optString("nickname"))
        )
    }

    fun sendVoice(chatId: Int, mediaId: Int, duration: Int? = null): Message {
        val o = JSONObject(post("conversations/$chatId/messages", JSONObject().apply {
            put("type", "voice"); put("media_id", mediaId); put("client_id", "c-" + UUID.randomUUID().toString().take(8))
            if (duration != null && duration > 0) put("duration", duration)
        })).getJSONObject("message")
        return Message(
            id = o.getInt("id"), chatId = o.getInt("chat_id"), senderId = o.getInt("sender_id"),
            type = "voice", text = null, mediaUrl = "/api/media/" + mediaId, mediaId = mediaId,
            createdAt = o.optString("created_at"),
            sender = Sender(o.getJSONObject("sender").getInt("id"), o.getJSONObject("sender").optString("nickname"))
        )
    }

    fun sendImage(chatId: Int, mediaId: Int): Message {
        val o = JSONObject(post("conversations/$chatId/messages", JSONObject().apply {
            put("type", "image"); put("media_id", mediaId); put("client_id", "c-" + UUID.randomUUID().toString().take(8))
        })).getJSONObject("message")
        return Message(
            id = o.getInt("id"), chatId = o.getInt("chat_id"), senderId = o.getInt("sender_id"),
            type = "image", text = null, mediaUrl = "/api/media/" + mediaId, mediaId = mediaId,
            createdAt = o.optString("created_at"),
            sender = Sender(o.getJSONObject("sender").getInt("id"), o.getJSONObject("sender").optString("nickname"))
        )
    }

    fun openPrivate(userId: Int): Int = JSONObject(post("conversations", JSONObject().apply {
        put("type", "private"); put("user_id", userId)
    })).getInt("chat_id")

    fun createGroup(title: String, memberIds: List<Int>): Int {
        val b = JSONObject().put("type", "group").put("title", title)
        b.put("member_ids", JSONArray(memberIds))
        return JSONObject(post("conversations", b)).getInt("chat_id")
    }

    fun pinChat(chatId: Int, pinned: Int) { post("chats/pin", JSONObject().put("chat_id", chatId).put("pinned", pinned)) }
    fun setNotice(chatId: Int, notice: String) { post("chats/notice", JSONObject().put("chat_id", chatId).put("notice", notice)) }
    fun hideChat(chatId: Int) { post("chats/hide", JSONObject().put("chat_id", chatId)) }
    fun clearChat(chatId: Int) { post("chats/clear", JSONObject().put("chat_id", chatId)) }

    fun renameGroup(chatId: Int, title: String) { post("conversations/$chatId/rename", JSONObject().put("title", title)) }
    fun kickMember(chatId: Int, userId: Int) { post("conversations/$chatId/kick", JSONObject().put("user_id", userId)) }
    fun addMembers(chatId: Int, userIds: List<Int>) { post("conversations/$chatId/members", JSONObject().put("member_ids", JSONArray(userIds))) }

    fun groupMembers(chatId: Int): List<User> {
        val arr = JSONObject(get("conversations/$chatId/members")).getJSONArray("members")
        return (0 until arr.length()).map { i -> user(arr.getJSONObject(i)) }
    }

    fun markRead(chatId: Int, lastId: Int) {
        post("conversations/$chatId/read", JSONObject().put("last_id", lastId))
    }

    fun sync(cursor: Int): Triple<Int, List<Message>, List<Chat>> {
        val o = JSONObject(get("sync?cursor=$cursor&device=$device"))
        val cur = o.optInt("cursor", cursor)
        val msgs = if (o.isNull("messages")) emptyList() else {
            val arr = o.getJSONArray("messages")
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val s = m.getJSONObject("sender")
                Message(
                    id = m.getInt("id"), chatId = m.getInt("chat_id"), senderId = m.getInt("sender_id"),
                    type = m.optString("type"), text = if (m.isNull("text")) null else m.optString("text"),
                    mediaUrl = if (m.isNull("media_url")) null else m.optString("media_url"),
                    mediaId = if (m.isNull("media_id")) null else m.optInt("media_id"),
                    createdAt = m.optString("created_at"), deleted = m.optInt("deleted"),
                    sender = Sender(s.getInt("id"), s.optString("nickname")),
                    duration = if (m.isNull("duration")) null else m.optInt("duration"),
                    enc = m.optInt("enc")
                )
            }
        }
        val chats = if (o.isNull("chats")) emptyList() else {
            val arr = o.getJSONArray("chats")
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                val lm = if (c.isNull("last_message")) null else c.getJSONObject("last_message")
                Chat(
                    id = c.getInt("id"), type = c.optString("type"),
                    title = c.optString("title", "").ifBlank { "会话" }, avatar = null,
                    unread = c.optInt("unread"), lastText = lm?.let { previewText(it) },
                    lastTime = lm?.optString("created_at")
                )
            }
        }
        return Triple(cur, msgs, chats)
    }

    fun ack(lastId: Int) { post("ack", JSONObject().put("device", device).put("last_id", lastId)) }

    // 下载媒体字节（带 token）
    fun downloadMedia(path: String): ByteArray {
        val conn = (URL("http://cheap-host1.cheapyun.com:29883" + path + "?t=" + (token ?: "")).openConnection() as HttpURLConnection)
        conn.requestMethod = "GET"; conn.connectTimeout = 20000; conn.readTimeout = 30000
        conn.setRequestProperty("Accept", "image/*")
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        AntiBot.header()?.let { conn.setRequestProperty("Cookie", it) }
        var code = conn.responseCode
        if (code == 200) return conn.inputStream.use { it.readBytes() }
        // 可能是人机校验页，解一次再重试
        val page = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        if (AntiBot.solve(page) != null) {
            val conn2 = (URL("http://cheap-host1.cheapyun.com:29883" + path + "?t=" + (token ?: "")).openConnection() as HttpURLConnection)
            conn2.requestMethod = "GET"; conn2.connectTimeout = 20000; conn2.readTimeout = 30000
            conn2.setRequestProperty("Accept", "image/*")
            token?.let { conn2.setRequestProperty("Authorization", "Bearer " + it) }
            AntiBot.header()?.let { conn2.setRequestProperty("Cookie", it) }
            return conn2.inputStream.use { it.readBytes() }
        }
        throw ApiException("下载失败 HTTP " + code)
    }

    fun friends(): List<User> {
        val arr = JSONObject(get("friends")).getJSONArray("friends")
        return (0 until arr.length()).map { i -> user(arr.getJSONObject(i)) }
    }

    fun requests(): Pair<List<FriendRequest>, List<FriendRequest>> {
        val o = JSONObject(get("friends/requests"))
        fun parse(key: String): List<FriendRequest> {
            val arr = if (o.isNull(key)) JSONArray() else o.getJSONArray(key)
            return (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                FriendRequest(
                    id = r.getInt("id"), user = user(r.getJSONObject("user")),
                    message = r.optString("message", "请求添加你为好友"),
                    status = if (r.isNull("status")) null else r.optString("status"),
                    reply = if (r.isNull("reply")) null else r.optString("reply")
                )
            }
        }
        return parse("incoming") to parse("outgoing")
    }

    fun search(q: String): List<User> {
        val arr = JSONObject(get("users?q=" + java.net.URLEncoder.encode(q, "UTF-8"))).getJSONArray("users")
        return (0 until arr.length()).map { i -> user(arr.getJSONObject(i)) }
    }

    fun sendFriendRequest(userId: Int, msg: String) {
        post("friends/request", JSONObject().put("user_id", userId).put("message", msg))
    }

    fun acceptFriend(id: Int) { post("friends/accept", JSONObject().put("id", id)) }
    fun rejectFriend(id: Int, reply: String? = null) {
        val b = JSONObject().put("id", id); if (reply != null) b.put("reply", reply); post("friends/reject", b)
    }
    fun deleteFriend(userId: Int) { post("friends/delete", JSONObject().put("user_id", userId)) }
    fun updateMe(nickname: String? = null, bio: String? = null, isPrivate: Int? = null, avatar: String? = null, publicKey: String? = null): User {
        val b = JSONObject()
        if (nickname != null) b.put("nickname", nickname)
        if (bio != null) b.put("bio", bio)
        if (isPrivate != null) b.put("is_private", isPrivate)
        if (avatar != null) b.put("avatar", avatar)
        if (publicKey != null) b.put("public_key", publicKey)
        return user(JSONObject(post("me", b)).getJSONObject("user"))
    }
}

// 全局 Context（读取版本号等）
object AppCtx {
    private var ctx: android.content.Context? = null
    fun set(c: android.content.Context) { ctx = c.applicationContext }
    fun get(): android.content.Context = ctx ?: throw IllegalStateException("ctx not ready")
}

// ---------- 入口 ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MediaCache.init(applicationContext)
        Session.init(applicationContext)
        Crypto.init(applicationContext)
        AntiBot.init(applicationContext)
        PlainCache.init(applicationContext)
        MsgCache.init(applicationContext)
        AppCtx.set(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = C.Accent, background = C.BG, surface = C.Nav, onBackground = C.White
            )) {
                App()
            }
        }
    }
}

@Composable
fun DisclaimerScreen(onAgree: () -> Unit, onReject: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(C.BG).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("用户协议与免责声明", color = C.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Column(Modifier.weight(1f, false).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text(
                "1. 本软件（鸽信 Pigeon）仅供学习、研究和交流使用，不提供任何形式的商业服务。\n\n" +
                "2. 使用者应自行遵守所在国家或地区的法律法规。利用本软件从事的任何违法违规行为，" +
                "均由使用者本人承担全部责任，与开发者无关。\n\n" +
                "3. 本软件不对消息内容的真实性、合法性负责，也不对因使用本软件造成的任何直接或间接损失负责。\n\n" +
                "4. 服务器仅保留消息 3 天用于投递，逾期自动清理；请勿在软件中存储重要资料。\n\n" +
                "5. 开发者有权在不另行通知的情况下修改或终止本软件。\n\n" +
                "点击「同意」表示你已阅读、理解并接受上述全部条款；点击「不同意」将直接退出软件。",
                color = C.Dim, fontSize = 14.sp, lineHeight = 22.sp
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onAgree, Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.Accent)) {
            Text("同意并继续", color = C.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onReject, Modifier.fillMaxWidth()) {
            Text("不同意，退出", color = C.Dim)
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val agreed = remember {
        ctx.getSharedPreferences("pigeon_app", android.content.Context.MODE_PRIVATE)
            .getBoolean("agreed_terms", false)
    }
    var hasAgreed by remember { mutableStateOf(agreed) }
    if (!hasAgreed) {
        DisclaimerScreen(onAgree = {
            ctx.getSharedPreferences("pigeon_app", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("agreed_terms", true).apply()
            hasAgreed = true
        }, onReject = {
            (ctx as? android.app.Activity)?.finishAffinity()
        })
        return
    }
    var screen by remember { mutableStateOf<Screen>(if (Api.token != null) Screen.Main else Screen.Login) }
    var me by remember { mutableStateOf<User?>(Session.user()) }
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(1300); splash = false }

    if (splash) {
        SplashScreen()
    } else {
        when (val s = screen) {
            is Screen.Login -> LoginScreen { t, u ->
            Session.save(t, u); me = u; screen = Screen.Main
            val pk = Crypto.ensureKeys()
            if (pk.isNotEmpty()) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Thread { runCatching { Api.updateMe(publicKey = pk) } }.start()
                }
            }
        }
            is Screen.Main -> MainScreen(me, onLogout = { Session.clear(); me = null; screen = Screen.Login })
        }
    }
}

@Composable
fun SplashScreen() {
    var shown by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (shown) 1f else 0.72f, animationSpec = spring(dampingRatio = 0.6f))
    val alpha by animateFloatAsState(targetValue = if (shown) 1f else 0f, animationSpec = tween(550))
    LaunchedEffect(Unit) { shown = true }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0A1320), C.BG))), contentAlignment = Alignment.Center) {
        Box(Modifier.size(96.dp).scale(scale).alpha(alpha).clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(C.Accent, Color(0xFF2B8FD8)))), contentAlignment = Alignment.Center) {
            Text("鸽", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = C.White)
        }
    }
}

sealed class Screen {
    object Login : Screen()
    object Main : Screen()
}

// ---------- 登录 ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onDone: (String, User) -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var registerMode by remember { mutableStateOf(false) }
    var captcha by remember { mutableStateOf<Captcha?>(null) }
    var answer by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    suspend fun loadCap() { captcha = try { Api.captcha() } catch (e: Exception) { err = e.message ?: ""; null } }
    LaunchedEffect(Unit) { loadCap() }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0A1320), C.BG)))) {
        Column(
            Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 28.dp, vertical = 50.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.verticalGradient(listOf(C.Accent, Color(0xFF2B8FD8)))),
                contentAlignment = Alignment.Center
            ) { Text("鸽", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = C.White) }
            Spacer(Modifier.height(14.dp))
            Text(if (registerMode) "注册鸽信" else "登录", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.White)
            Text("轻量 · 端到端加密 · 消息只存 3 天", color = C.Dim, fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("邮箱 / 用户名") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), colors = fieldColors())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") },
                singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = fieldColors())
            if (registerMode) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(nickname, { nickname = it }, Modifier.fillMaxWidth(), label = { Text("昵称") },
                    singleLine = true, colors = fieldColors())
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(captcha?.question ?: "加载中…", color = C.White, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("↻", Modifier.clickable { scope.launch { loadCap() } }, color = C.Accent, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(answer, { answer = it }, Modifier.width(84.dp), label = { Text("答案") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors())
            }
            if (err.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(err, color = C.Danger, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = {
                val cap = captcha
                if (cap == null) { err = "请先加载验证码"; return@Button }
                val ans = answer.trim().toIntOrNull()
                if (ans == null) { err = "请回答验证"; return@Button }
                busy = true; err = ""
                scope.launch {
                    try {
                        val (t, u) = withContext(Dispatchers.IO) {
                            if (registerMode) Api.register(username, password, nickname.ifBlank { username }, cap.token, ans)
                            else Api.login(username, password, cap.token, ans)
                        }
                        onDone(t, u)
                    } catch (e: Exception) {
                        err = e.message ?: "请求失败"; loadCap()
                    } finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (registerMode) "注册并登录" else "登录", fontSize = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { registerMode = !registerMode; err = "" }) {
                Text(if (registerMode) "已有账号？登录" else "还没有账号？注册", color = C.Accent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.Accent, unfocusedBorderColor = Color(0xFF2A3A4A),
    focusedTextColor = C.White, unfocusedTextColor = C.White,
    focusedLabelColor = C.Accent, unfocusedLabelColor = C.Dim,
    cursorColor = C.Accent
)

// ---------- 主界面 ----------
@Composable
fun MainScreen(me: User?, onLogout: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        containerColor = C.BG,
        bottomBar = {
            NavigationBar(containerColor = C.Nav) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("💬") }, label = { Text("聊天") },
                    colors = navColors())
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Text("👥") }, label = { Text("联系人") },
                    colors = navColors())
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Text("👤") }, label = { Text("我的") },
                    colors = navColors())
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ChatListScreen()
                1 -> ContactsScreen()
                2 -> ProfileScreen(me, onLogout)
            }
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = C.Accent, selectedTextColor = C.Accent,
    unselectedIconColor = C.Dim, unselectedTextColor = C.Dim,
    indicatorColor = C.Accent.copy(alpha = 0.15f)
)

// ---------- 聊天列表 ----------
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen() {
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<Chat>>(emptyList()) }
    var openChat by remember { mutableStateOf<Chat?>(null) }
    var me by remember { mutableStateOf<User?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        me = try { Api.me() } catch (e: Exception) { null }
        chats = try { Api.chats() } catch (e: Exception) { emptyList() }
    }

    val c = openChat
    if (c != null && me != null) {
        ChatScreen(c, me!!) { openChat = null; refreshKey++ }
        return
    }

    var showNew by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Chat?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("聊天", fontWeight = FontWeight.Bold) },
            actions = {
                Text("＋", Modifier.clickable { showNew = true }.padding(16.dp),
                    color = C.Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = C.Nav, titleContentColor = C.White))
        if (chats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有会话", color = C.Dim)
            }
        } else {
            LazyColumn {
                items(chats, key = { it.id }) { ch ->
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { openChat = ch },
                                onLongClick = { menuFor = ch }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        avatar(ch.title, 50)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(ch.title, color = C.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                                    maxLines = 1, modifier = Modifier.weight(1f))
                                if (ch.lastTime != null) Text(shortTime(ch.lastTime!!), color = C.Dim, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(ch.lastText ?: "暂无消息", color = if (ch.unread > 0) C.White else C.Dim,
                                    fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                if (ch.unread > 0) {
                                    Box(Modifier.clip(CircleShape).background(C.Accent).padding(horizontal = 7.dp, vertical = 1.dp)) {
                                        Text("${ch.unread}", color = C.White, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF0D151D))
                }
            }
        }
    }

    val mf = menuFor
    if (mf != null) {
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(mf.title, color = C.White, fontSize = 15.sp) },
            text = {
                Column {
                    Text(if (mf.pinned == 1) "取消置顶" else "置顶聊天",
                        Modifier.fillMaxWidth().clickable {
                            menuFor = null
                            scope.launch {
                                try {
                                    Api.pinChat(mf.id, if (mf.pinned == 1) 0 else 1)
                                    chats = Api.chats()
                                } catch (_: Exception) {}
                            }
                        }.padding(vertical = 12.dp), color = C.White, fontSize = 15.sp)
                    Text("删除此聊天",
                        Modifier.fillMaxWidth().clickable {
                            menuFor = null
                            scope.launch {
                                try {
                                    Api.hideChat(mf.id)
                                    chats = Api.chats()
                                } catch (_: Exception) {}
                            }
                        }.padding(vertical = 12.dp), color = C.Danger, fontSize = 15.sp)
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("取消", color = C.Dim) } }
        )
    }

    if (showNew) {
        var nf by remember { mutableStateOf<List<User>>(emptyList()) }
        var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var groupTitle by remember { mutableStateOf("") }
        LaunchedEffect(showNew) { nf = try { Api.friends() } catch (_: Exception) { emptyList() } }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text(if (picked.size > 1) "新建群聊（已选 ${picked.size} 人）" else "发起聊天", color = C.White) },
            text = {
                Column(Modifier.heightIn(max = 380.dp)) {
                    if (nf.isEmpty()) {
                        Text("还没有好友，先去「联系人」添加", color = C.Dim)
                    } else {
                        Text("选 1 人创建私聊，选多人创建群聊", color = C.Dim, fontSize = 12.sp)
                        if (picked.size > 1) {
                            OutlinedTextField(groupTitle, { groupTitle = it }, Modifier.fillMaxWidth().padding(top = 6.dp),
                                placeholder = { Text("群名称（可留空）", color = C.Dim) }, singleLine = true, colors = fieldColors())
                        }
                        LazyColumn(Modifier.padding(top = 6.dp)) {
                            items(nf, key = { it.id }) { f ->
                                val on = picked.contains(f.id)
                                Row(Modifier.fillMaxWidth()
                                    .clickable { picked = if (on) picked - f.id else picked + f.id }
                                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    avatar(f.nickname, 34)
                                    Spacer(Modifier.width(10.dp))
                                    Text(f.nickname, color = C.White, modifier = Modifier.weight(1f))
                                    Text(if (on) "✓" else "", color = C.Accent, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = picked.isNotEmpty(), onClick = {
                    val ids = picked.toList()
                    showNew = false
                    scope.launch {
                        try {
                            val cid = if (ids.size == 1) Api.openPrivate(ids[0])
                                      else Api.createGroup(groupTitle.ifBlank { "群聊" }, ids)
                            chats = Api.chats()
                            chats.firstOrNull { it.id == cid }?.let { openChat = it }
                        } catch (_: Exception) {}
                    }
                }) { Text(if (picked.size > 1) "创建群聊" else "开始聊天", color = C.Accent) }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("取消", color = C.Dim) } }
        )
    }

}

// 本地缓存图片：先读本地，没有就下载并落盘
@Composable
fun PhotoFull(mediaId: Int?, mediaUrl: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    var savedMsg by remember { mutableStateOf("") }
    LaunchedEffect(mediaId, mediaUrl) {
        val id = mediaId ?: return@LaunchedEffect
        val url = mediaUrl ?: return@LaunchedEffect
        val bytes = MediaCache.read(id) ?: runCatching { Api.downloadMedia(url) }.getOrNull()
        if (bytes != null) {
            if (!MediaCache.has(id)) MediaCache.save(id, bytes)
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    off += pan
                }
            }.clickable { onClose() },
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    b.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth().graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = off.x, translationY = off.y
                    ),
                    contentScale = ContentScale.Fit
                )
            } else {
                CircularProgressIndicator(color = C.Accent)
            }
        }
        // 顶部：保存到相册 + 关闭
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(Color(0xB3000000)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("保存到相册", Modifier.clickable(enabled = bmp != null) {
                val b = bmp
                if (b != null) {
                    val ok = saveBitmapToGallery(ctx, b, "pigeon_${System.currentTimeMillis()}.jpg")
                    savedMsg = if (ok) "已保存到相册" else "保存失败"
                }
            }, color = if (bmp != null) C.Accent else C.Dim, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("✕", Modifier.clickable { onClose() }.padding(6.dp), color = C.White, fontSize = 16.sp)
        }
        if (savedMsg.isNotEmpty()) {
            LaunchedEffect(savedMsg) { kotlinx.coroutines.delay(1800); savedMsg = "" }
            Text(savedMsg, Modifier.align(Alignment.BottomCenter).padding(20.dp).clip(RoundedCornerShape(20.dp))
                .background(Color(0xE6000000)).padding(horizontal = 16.dp, vertical = 10.dp),
                color = C.White, fontSize = 13.sp)
        }
    }
}

fun msgDisplayText(m: Message): String {
    if (m.type != "text") return ""
    return if (m.enc == 1) (PlainCache.get(m.id) ?: Crypto.decryptText(m.text ?: "") ?: "") else (m.text ?: "")
}

fun saveBitmapToGallery(ctx: android.content.Context, bitmap: android.graphics.Bitmap, name: String): Boolean {
    return try {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pigeon")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, os)
        } ?: return false
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
        }
        true
    } catch (e: Exception) { false }
}

@Composable
fun CachedImage(mediaId: Int?, mediaUrl: String?, modifier: Modifier = Modifier) {
    var bmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(mediaId, mediaUrl) {
        val id = mediaId ?: return@LaunchedEffect
        val url = mediaUrl ?: return@LaunchedEffect
        val bytes = MediaCache.read(id) ?: runCatching { Api.downloadMedia(url) }.getOrNull()
        if (bytes != null) {
            if (!MediaCache.has(id)) MediaCache.save(id, bytes)
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, modifier = modifier)
    } else {
        Box(modifier.background(C.In), contentAlignment = Alignment.Center) { Text("加载中…", color = C.Dim, fontSize = 12.sp) }
    }
}

@Composable
fun avatar(name: String, size: Int, online: Boolean = false) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(colorOf(name)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = C.White, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.SemiBold)
    }
}

fun colorOf(name: String): Color {
    val palette = listOf(Color(0xFFE17076), Color(0xFF7BC862), Color(0xFFE5CA77), Color(0xFF65AADD),
        Color(0xFFA695E7), Color(0xFFEE7AAE), Color(0xFF6EC9CB), Color(0xFFFAA774))
    var h = 0
    for (ch in name) h = (h * 31 + ch.code) and 0x7fffffff
    return palette[h % palette.size]
}

fun playTone(kind: Int) {
    runCatching {
        val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
        tg.startTone(if (kind == 0) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2, 140)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 400)
    }
}

fun shortTime(s: String): String {
    return runCatching {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val d = f.parse(s.replace("T", " ")) ?: return s
        val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()); tf.format(d)
    }.getOrDefault("")
}

// ---------- 聊天页 ----------
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(chat: Chat, me: User, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf<List<Message>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var cursor by remember { mutableStateOf(0) }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var actionMsg by remember { mutableStateOf<Message?>(null) }
    var forwardMsg by remember { mutableStateOf<Message?>(null) }
    var showMention by remember { mutableStateOf(false) }
    var showGroupInfo by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var voicePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var voiceId by remember { mutableStateOf<Int?>(null) }
    var voicePaused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { runCatching { voicePlayer?.release() } }
    }
    var viewer by remember { mutableStateOf<Message?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordStart by remember { mutableStateOf(0L) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    val context = LocalContext.current
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            try {
                val f = File(context.cacheDir, "voice_" + System.currentTimeMillis() + ".m4a")
                val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setOutputFile(f.absolutePath)
                r.prepare(); r.start()
                recorder = r; recordFile = f; recordStart = System.currentTimeMillis(); recording = true
            } catch (_: Exception) { recording = false }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                    val id = Api.uploadImage(bytes, "img_" + System.currentTimeMillis() + ".jpg")
                    val s = Api.sendImage(chat.id, id)
                    msgs = (msgs + s).sortedBy { it.id }
                } catch (_: Exception) {}
            }
        }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                    val id = Api.uploadImage(bytes, "video_" + System.currentTimeMillis() + ".mp4")
                    val s = Api.sendVideo(chat.id, id)
                    msgs = (msgs + s).sortedBy { it.id }
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun load() {
        val cached = MsgCache.load(chat.id)
        if (cached.isNotEmpty()) msgs = cached
        val remote = try { Api.messages(chat.id) } catch (e: Exception) { emptyList() }
        if (remote.isNotEmpty()) {
            // 合并：服务器 + 本地（本地保留服务器已清理的旧消息）
            val map = LinkedHashMap<Int, Message>()
            cached.forEach { map[it.id] = it }
            remote.forEach { map[it.id] = it }
            msgs = map.values.sortedBy { it.id }
            MsgCache.save(chat.id, msgs)
        }
    }
    LaunchedEffect(chat.id) {
        load()
        msgs.lastOrNull()?.let { runCatching { Api.markRead(chat.id, it.id) } }
    }

    // 轮询新消息
    LaunchedEffect(chat.id) {
        while (true) {
            kotlinx.coroutines.delay(2500)
            try {
                val (cur, newMsgs, _) = Api.sync(cursor)
                if (cur > cursor) { cursor = cur; runCatching { Api.ack(cur) } }
                val fresh = newMsgs.filter { it.chatId == chat.id && msgs.none { x -> x.id == it.id } }
                if (fresh.isNotEmpty()) {
                    msgs = (msgs + fresh).sortedBy { it.id }
                    MsgCache.save(chat.id, msgs)
                    if (fresh.any { it.senderId != me.id }) playTone(1)
                    fresh.filter { it.senderId != me.id }.lastOrNull()?.let { runCatching { Api.markRead(chat.id, it.id) } }
                }
            } catch (_: Exception) {}
        }
    }

    val vw = viewer
    if (vw != null) {
        Dialog(onDismissRequest = { viewer = null }) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                PhotoFull(vw.mediaId, vw.mediaUrl) { viewer = null }
            }
        }
    }

    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(chat.id) { enter.animateTo(1f, androidx.compose.animation.core.tween(260)) }

    Column(Modifier.fillMaxSize().graphicsLayer {
        alpha = enter.value
        translationX = (1f - enter.value) * 140f
    }) {
        TopAppBar(
            title = { Column { Text(chat.title, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("🔒 服务器 3 天后清理，已收到的消息本机永久保留", fontSize = 10.sp, color = C.Dim) } },
            navigationIcon = { Text("‹", Modifier.clickable { onBack() }.padding(12.dp), color = C.Accent, fontSize = 26.sp) },
            actions = {
                Text(if (searchMode) "✕" else "🔍", Modifier.clickable {
                    searchMode = !searchMode
                    if (!searchMode) searchQuery = ""
                }.padding(horizontal = 10.dp, vertical = 10.dp), color = C.Accent, fontSize = 17.sp)
                Box {
                    Text("⋯", Modifier.clickable { showMore = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = C.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        if (chat.type == "group") {
                            DropdownMenuItem(text = { Text("群信息") }, onClick = { showMore = false; showGroupInfo = true })
                        }
                        DropdownMenuItem(text = { Text("清空聊天记录") }, onClick = {
                            showMore = false
                            scope.launch {
                                try {
                                    Api.clearChat(chat.id)
                                    msgs = emptyList()
                                    MsgCache.save(chat.id, emptyList())
                                } catch (_: Exception) {}
                            }
                        })
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = C.Nav, titleContentColor = C.White)
        )
        if (chat.type == "group" && chat.notice.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2410))
                .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("📢", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(chat.notice, Modifier.weight(1f), color = Color(0xFFFFD98A), fontSize = 13.sp)
            }
        }
        if (searchMode) {
            OutlinedTextField(searchQuery, { searchQuery = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("搜索本会话消息", color = C.Dim) }, singleLine = true,
                shape = RoundedCornerShape(12.dp), colors = fieldColors())
            val matches = remember(searchQuery, msgs) {
                if (searchQuery.isBlank()) emptyList()
                else msgs.filter { msgDisplayText(it).contains(searchQuery, ignoreCase = true) }
            }
            if (searchQuery.isNotBlank()) {
                Text(if (matches.isEmpty()) "没有匹配的消息" else "找到 ${matches.size} 条",
                    Modifier.padding(horizontal = 14.dp, vertical = 2.dp), color = C.Dim, fontSize = 12.sp)
                if (matches.isNotEmpty()) {
                    LazyColumn(Modifier.weight(0.4f)) {
                        items(matches, key = { it.id }) { m ->
                            val idx = msgs.indexOfFirst { it.id == m.id }
                            Text(
                                (m.sender.nickname + "：").take(12) + msgDisplayText(m).take(30),
                                Modifier.fillMaxWidth().clickable {
                                    searchMode = false
                                    if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                                }.padding(horizontal = 14.dp, vertical = 8.dp),
                                color = C.White, fontSize = 13.sp, maxLines = 1
                            )
                        }
                    }
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(msgs, key = { it.id }) { m ->
                if (m.deleted == 1) {
                    Text("${m.sender.nickname} 撤回了一条消息", Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        color = C.Dim, fontSize = 12.sp)
                } else if (m.type == "system") {
                    Text(m.text ?: "", Modifier.fillMaxWidth().padding(vertical = 8.dp), color = C.Dim, fontSize = 12.sp)
                } else {
                    val mine = m.senderId == me.id
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { actionMsg = m }).padding(vertical = 3.dp),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                            modifier = Modifier.widthIn(max = 300.dp)) {
                            if (m.fwdFrom != null) Text("转发自 ${m.fwdFrom}", color = C.Accent, fontSize = 11.sp)
                            if (m.replyNick != null) Text("${m.replyNick}：${m.replyText}", color = C.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
                            if (m.type == "image" && m.mediaUrl != null) {
                                CachedImage(m.mediaId, m.mediaUrl,
                                    Modifier.clip(RoundedCornerShape(16.dp)).widthIn(max = 220.dp).heightIn(max = 260.dp)
                                        .clickable { viewer = m })
                            }
                            if (m.type == "video" && m.mediaUrl != null) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (mine) C.Out else C.In)
                                        .clickable {
                                            scope.launch {
                                                try {
                                                    val id = m.mediaId ?: return@launch
                                                    val url = m.mediaUrl ?: return@launch
                                                    val file = MediaCache.file(id)
                                                    if (!MediaCache.has(id)) {
                                                        val bytes = Api.downloadMedia(url)
                                                        MediaCache.save(id, bytes)
                                                    }
                                                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                                                    val it = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    context.startActivity(it)
                                                } catch (_: Exception) {}
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text("🎬", fontSize = 16.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("视频消息，点击播放", color = C.White, fontSize = 14.sp)
                                }
                            }
                            if (m.type == "voice" && m.mediaUrl != null) {
                                val playing = voiceId == m.id
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clip(RoundedCornerShape(18.dp))
                                        .background(if (mine) C.Out else C.In)
                                        .clickable {
                                            if (voiceId == m.id) {
                                                // 再点一次 = 暂停
                                                runCatching { voicePlayer?.pause() }
                                                voiceId = null
                                                voicePaused = true
                                            } else {
                                                scope.launch {
                                                    try {
                                                        val id = m.mediaId ?: return@launch
                                                        val url = m.mediaUrl ?: return@launch
                                                        val file = MediaCache.file(id)
                                                        if (!MediaCache.has(id)) {
                                                            val bytes = Api.downloadMedia(url)
                                                            MediaCache.save(id, bytes)
                                                        }
                                                        runCatching { voicePlayer?.release() }
                                                        val mp = MediaPlayer()
                                                        mp.setDataSource(file.absolutePath)
                                                        mp.prepare(); mp.start()
                                                        voicePlayer = mp
                                                        voiceId = m.id
                                                        voicePaused = false
                                                        mp.setOnCompletionListener {
                                                            voiceId = null
                                                            runCatching { it.release() }
                                                        }
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Box(Modifier.size(30.dp).clip(CircleShape)
                                        .background(if (mine) Color.White.copy(alpha = 0.18f) else C.Accent.copy(alpha = 0.22f)),
                                        contentAlignment = Alignment.Center) {
                                        Text(if (playing) "❚❚" else "▶", color = if (mine) C.White else C.Accent,
                                            fontSize = if (playing) 11.sp else 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(9.dp))
                                    Column {
                                        Text(if (playing) "正在播放…" else "语音", color = C.White, fontSize = 14.sp)
                                        val secs = m.duration ?: 0
                                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                            repeat((secs.coerceIn(1, 10))) { i ->
                                                Box(Modifier.padding(end = 2.dp).size(width = 2.dp, height = (6 + (i % 3) * 3).dp)
                                                    .clip(RoundedCornerShape(1.dp))
                                                    .background(if (playing) C.Accent else C.Dim.copy(alpha = 0.6f)))
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Text("${secs}″", color = C.Dim, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            if (m.type == "text" && !m.text.isNullOrBlank()) {
                                val disp = remember(m.id, m.enc, m.text) {
                                    if (m.enc == 1) (
                                        PlainCache.get(m.id)
                                            ?: Crypto.decryptText(m.text ?: "")
                                            ?: "🔒 加密消息（本机无密钥）"
                                        ) else (m.text ?: "")
                                }
                                val mentioned = chat.type == "group" && disp.contains("@" + me.nickname)
                                Column {
                                    if (mentioned) {
                                        Text("@ 提到了你", Modifier.padding(bottom = 3.dp), color = Color(0xFFFFB020), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(disp, Modifier.clip(RoundedCornerShape(16.dp))
                                        .background(if (mentioned) Color(0xFF3A2D16) else (if (mine) C.Out else C.In))
                                        .border(if (mentioned) 1.dp else 0.dp, Color(0xFFFFB020), RoundedCornerShape(16.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                        color = if (mentioned) Color(0xFFFFD98A) else C.White, fontSize = 16.sp)
                                }
                            }
                            Text(shortTime(m.createdAt), color = C.Dim, fontSize = 10.sp)
                            if (mine && chat.type == "private") {
                                val read = m.id <= (chat.peerReadId ?: 0)
                                Text(if (read) "✓✓ 已读" else "✓ 已送达",
                                    color = if (read) C.Accent else C.Dim, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(C.Nav).padding(10.dp)) {
            if (recording) {
                Text("🔴 录音中… 再点一下红按钮发送", Modifier.fillMaxWidth().padding(bottom = 6.dp), color = C.Danger, fontSize = 12.sp)
            }
            val r = replyTo
            if (r != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.Card).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("回复 ${r.sender.nickname}：${r.text ?: "图片"}", Modifier.weight(1f), color = C.Dim, fontSize = 12.sp, maxLines = 1)
                    Text("✕", Modifier.clickable { replyTo = null }, color = C.Accent, fontSize = 14.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(if (recording) C.Danger else C.Card).clickable {
                    if (recording) {
                        val r2 = recorder
                        try { r2?.stop() } catch (_: Exception) {}
                        try { r2?.release() } catch (_: Exception) {}
                        recorder = null; recording = false
                        val f = recordFile
                        if (f != null && f.exists() && f.length() > 0) {
                            scope.launch {
                                try {
                                    val bytes = f.readBytes()
                                    val dur = ((System.currentTimeMillis() - recordStart) / 1000).toInt().coerceAtLeast(1)
                                    val id = Api.uploadImage(bytes, "voice_" + System.currentTimeMillis() + ".m4a")
                                    val s = Api.sendVoice(chat.id, id, dur)
                                    msgs = (msgs + s).sortedBy { it.id }
                                } catch (_: Exception) {}
                            }
                        }
                    } else {
                        audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }, contentAlignment = Alignment.Center) { Text(if (recording) "⏹" else "🎤", fontSize = 17.sp) }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(36.dp).clip(CircleShape).background(C.Card).clickable { showMention = true },
                    contentAlignment = Alignment.Center) { Text("@", color = C.Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(36.dp).clip(CircleShape).background(C.Card).clickable { pickImage.launch("image/*") },
                    contentAlignment = Alignment.Center) { Text("📷", fontSize = 18.sp) }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("消息", color = C.Dim) },
                    singleLine = true, shape = RoundedCornerShape(18.dp), colors = fieldColors())
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(36.dp).clip(CircleShape).background(C.Accent).clickable {
                    val t = draft.trim()
                    if (t.isNotEmpty()) {
                        draft = ""
                        val rp = replyTo
                        replyTo = null
                        scope.launch {
                            try {
                                var enc = 0
                                var payload = t
                                if (chat.type == "private") {
                                    val pk = chat.peerPubKey
                                    if (!pk.isNullOrEmpty()) {
                                        Crypto.encryptText(t, pk)?.let { payload = it; enc = 1 }
                                    }
                                }
                                val s = Api.send(chat.id, payload, rp?.id, enc = enc)
                                if (enc == 1) PlainCache.put(s.id, t)
                                msgs = (msgs + s).sortedBy { it.id }; playTone(0)
                            } catch (e: Exception) {}
                        }
                    }
                }, contentAlignment = Alignment.Center) { Text("↑", color = C.White, fontWeight = FontWeight.Bold) }
            }
        }
    }

    val am = actionMsg
    if (am != null) {
        AlertDialog(
            onDismissRequest = { actionMsg = null },
            title = { Text(am.sender.nickname, color = C.White) },
            text = { Text(am.text ?: "图片", color = C.Dim, maxLines = 3) },
            confirmButton = {
                Row {
                    TextButton(onClick = { replyTo = am; actionMsg = null }) { Text("回复", color = C.Accent) }
                    TextButton(onClick = { forwardMsg = am; actionMsg = null }) { Text("转发", color = C.White) }
                    if (am.senderId == me.id && am.deleted == 0) {
                        TextButton(onClick = {
                            actionMsg = null
                            scope.launch {
                                try {
                                    Api.recall(chat.id, am.id)
                                    msgs = msgs.map { if (it.id == am.id) it.copy(deleted = 1) else it }
                                } catch (_: Exception) {}
                            }
                        }) { Text("撤回", color = C.Danger) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { actionMsg = null }) { Text("取消", color = C.Dim) } }
        )
    }

    if (showGroupInfo && chat.type == "group") {
        var gMembers by remember { mutableStateOf<List<User>>(emptyList()) }
        var gTitle by remember { mutableStateOf(chat.title) }
        var showAddMem by remember { mutableStateOf(false) }
        LaunchedEffect(showGroupInfo) { gMembers = try { Api.groupMembers(chat.id) } catch (_: Exception) { emptyList() } }
        AlertDialog(
            onDismissRequest = { showGroupInfo = false },
            title = { Text("群信息", color = C.White) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(gTitle, { gTitle = it }, Modifier.weight(1f), label = { Text("群名称") }, singleLine = true, colors = fieldColors())
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { scope.launch { try { Api.renameGroup(chat.id, gTitle.trim()) } catch (_: Exception) {} }; showGroupInfo = false }) { Text("保存", color = C.Accent) }
                    }
                    Spacer(Modifier.height(10.dp))
                    var gNotice by remember { mutableStateOf(chat.notice) }
                    OutlinedTextField(gNotice, { gNotice = it }, Modifier.fillMaxWidth(), label = { Text("群公告") },
                        singleLine = true, colors = fieldColors())
                    TextButton(onClick = {
                        scope.launch {
                            try { Api.setNotice(chat.id, gNotice.trim()) } catch (_: Exception) {}
                        }
                        showGroupInfo = false
                    }) { Text("保存公告", color = C.Accent) }
                    Spacer(Modifier.height(8.dp))
                    Text("成员（${gMembers.size}）", color = C.Dim, fontSize = 13.sp)
                    gMembers.forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.nickname, color = C.White, modifier = Modifier.weight(1f))
                            if (m.id == me.id) Text("我", color = C.Dim, fontSize = 12.sp)
                            else Text("移除", color = C.Danger, fontSize = 12.sp, modifier = Modifier.clickable {
                                scope.launch {
                                    try { Api.kickMember(chat.id, m.id); gMembers = Api.groupMembers(chat.id) } catch (_: Exception) {}
                                }
                            })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showAddMem = true }) { Text("添加成员", color = C.Accent) }
                }
            },
            confirmButton = { TextButton(onClick = { showGroupInfo = false }) { Text("关闭", color = C.Dim) } }
        )
        if (showAddMem) {
            var addFriends by remember { mutableStateOf<List<User>>(emptyList()) }
            var sel by remember { mutableStateOf<Set<Int>>(emptySet()) }
            LaunchedEffect(showAddMem) {
                val f = try { Api.friends() } catch (_: Exception) { emptyList() }
                addFriends = f.filter { fr -> gMembers.none { it.id == fr.id } }
            }
            AlertDialog(
                onDismissRequest = { showAddMem = false },
                title = { Text("添加成员", color = C.White) },
                text = {
                    if (addFriends.isEmpty()) Text("没有可添加的好友", color = C.Dim)
                    else Column {
                        addFriends.forEach { f ->
                            Row(Modifier.fillMaxWidth().clickable {
                                sel = if (f.id in sel) sel - f.id else sel + f.id
                            }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (f.id in sel) "☑" else "☐", color = C.Accent, fontSize = 16.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(f.nickname, color = C.White)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = {
                    scope.launch {
                        try { Api.addMembers(chat.id, sel.toList()); gMembers = Api.groupMembers(chat.id) } catch (_: Exception) {}
                    }
                    showAddMem = false
                }) { Text("添加", color = C.Accent) } },
                dismissButton = { TextButton(onClick = { showAddMem = false }) { Text("取消", color = C.Dim) } }
            )
        }
    }

    if (showMention) {
        var names by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(showMention) {
            names = try {
                if (chat.type == "group") Api.groupMembers(chat.id).map { it.nickname }
                else listOf(chat.title)
            } catch (_: Exception) { emptyList() }
        }
        AlertDialog(
            onDismissRequest = { showMention = false },
            title = { Text("@ 提及", color = C.White) },
            text = {
                if (names.isEmpty()) {
                    Text("暂无成员", color = C.Dim)
                } else {
                    Column {
                        names.forEach { n ->
                            Text(n, Modifier.fillMaxWidth().clickable {
                                draft = (draft + "@" + n + " ").trimStart()
                                showMention = false
                            }.padding(vertical = 8.dp), color = C.White, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMention = false }) { Text("取消", color = C.Dim) } }
        )
    }

    val fm = forwardMsg
    if (fm != null) {
        var fwdChats by remember { mutableStateOf<List<Chat>>(emptyList()) }
        LaunchedEffect(fm) { fwdChats = try { Api.chats() } catch (_: Exception) { emptyList() } }
        AlertDialog(
            onDismissRequest = { forwardMsg = null },
            title = { Text("转发到…", color = C.White) },
            text = {
                if (fwdChats.isEmpty()) {
                    Text("没有会话", color = C.Dim)
                } else {
                    LazyColumn {
                        items(fwdChats, key = { it.id }) { c ->
                            Text(c.title, Modifier.fillMaxWidth().clickable {
                                forwardMsg = null
                                scope.launch {
                                    try {
                                        val sent = Api.send(c.id, fm.text ?: "图片", fwdFrom = fm.sender.nickname)
                                        if (c.id == chat.id) msgs = (msgs + sent).sortedBy { it.id }
                                    } catch (_: Exception) {}
                                }
                            }.padding(vertical = 10.dp), color = C.White, fontSize = 15.sp)
                            HorizontalDivider(color = Color(0xFF0D151D))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { forwardMsg = null }) { Text("取消", color = C.Dim) } }
        )
    }
}

// ---------- 联系人 ----------
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen() {
    val scope = rememberCoroutineScope()
    var friends by remember { mutableStateOf<List<User>>(emptyList()) }
    var incoming by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }
    var outgoing by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    var showRequests by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var friendMenu by remember { mutableStateOf<User?>(null) }
    var toast by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        try { friends = Api.friends() } catch (_: Exception) {}
        try { val (i, o) = Api.requests(); incoming = i; outgoing = o } catch (_: Exception) {}
    }
    LaunchedEffect(q) {
        if (q.trim().isEmpty()) { results = emptyList() } else {
            results = try { Api.search(q.trim()) } catch (_: Exception) { emptyList() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("联系人", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = C.Nav, titleContentColor = C.White))
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            placeholder = { Text("搜索账号 / ID 添加好友", color = C.Dim) }, singleLine = true,
            shape = RoundedCornerShape(12.dp), colors = fieldColors())
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f)) {
            item {
                Row(Modifier.fillMaxWidth().clickable { showRequests = true }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(C.Accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Text("👤", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("新的朋友", color = C.White, modifier = Modifier.weight(1f))
                    if (incoming.isNotEmpty()) {
                        Box(Modifier.clip(CircleShape).background(C.Accent).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("${incoming.size}", color = C.White, fontSize = 12.sp)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clickable { showCreateGroup = true }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF4DCD5E).copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Text("👥", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("发起群聊", color = C.White, modifier = Modifier.weight(1f))
                }
            }
            if (q.trim().isNotEmpty()) {
                items(results, key = { it.id }) { u ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        avatar(u.nickname, 42)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.nickname, color = C.White)
                            Text("@${u.username} · ID ${u.id}", color = C.Dim, fontSize = 12.sp)
                        }
                        Text(when (u.relation) { "friend" -> "已添加"; "outgoing" -> "待验证"; "incoming" -> "去同意"; else -> "添加" },
                            color = if (u.relation == "friend") C.Dim else C.Accent,
                            modifier = Modifier.clickable(enabled = u.relation != "friend" && u.relation != "outgoing") {
                                scope.launch {
                                    try { Api.sendFriendRequest(u.id, "请求添加你为好友"); toast = "申请已发送"; refreshKey++ }
                                    catch (e: Exception) { toast = e.message ?: "" }
                                }
                            })
                    }
                }
            }
            item { Text("我的好友（${friends.size}）", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = C.Dim, fontSize = 13.sp) }
            items(friends, key = { it.id }) { u ->
                Row(Modifier.fillMaxWidth().combinedClickable(
                    onClick = {
                        scope.launch { try { val id = Api.openPrivate(u.id); refreshKey++ } catch (e: Exception) { toast = e.message ?: "" } }
                    },
                    onLongClick = { friendMenu = u }
                ).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    avatar(u.nickname, 42, u.online)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.nickname, color = C.White)
                        Text("@${u.username} · ${if (u.online) "在线" else "离线"}", color = C.Dim, fontSize = 12.sp)
                    }
                }
            }
            }
        }

        if (toast.isNotEmpty()) {
            LaunchedEffect(toast) { kotlinx.coroutines.delay(2200); toast = "" }
            Text(toast, Modifier.align(Alignment.BottomCenter).padding(20.dp).clip(RoundedCornerShape(20.dp))
                .background(Color(0xE6000000)).padding(horizontal = 16.dp, vertical = 10.dp), color = C.White, fontSize = 13.sp)
        }
    }

    val fm = friendMenu
    if (fm != null) {
        AlertDialog(
            onDismissRequest = { friendMenu = null },
            title = { Text(fm.nickname, color = C.White, fontSize = 16.sp) },
            text = {
                Column {
                    Text("@${fm.username} · ID ${fm.id}", color = C.Dim, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("发消息", Modifier.fillMaxWidth().clickable {
                        friendMenu = null
                        scope.launch { try { Api.openPrivate(fm.id); refreshKey++ } catch (e: Exception) { toast = e.message ?: "" } }
                    }.padding(vertical = 12.dp), color = C.White, fontSize = 15.sp)
                    Text("删除好友", Modifier.fillMaxWidth().clickable {
                        friendMenu = null
                        scope.launch {
                            try {
                                Api.deleteFriend(fm.id)
                                friends = Api.friends()
                                toast = "已删除好友"
                            } catch (e: Exception) { toast = e.message ?: "" }
                        }
                    }.padding(vertical = 12.dp), color = C.Danger, fontSize = 15.sp)
                }
            },
            confirmButton = { TextButton(onClick = { friendMenu = null }) { Text("取消", color = C.Dim) } }
        )
    }

    if (showCreateGroup) {
        var gTitle by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
        AlertDialog(
            onDismissRequest = { showCreateGroup = false },
            title = { Text("发起群聊", color = C.White) },
            text = {
                Column {
                    OutlinedTextField(gTitle, { gTitle = it }, label = { Text("群名称") }, singleLine = true, colors = fieldColors())
                    Spacer(Modifier.height(8.dp))
                    Text("选择好友（${selected.size}）", color = C.Dim, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    if (friends.isEmpty()) {
                        Text("先添加好友才能建群", color = C.Dim, fontSize = 13.sp)
                    } else {
                        friends.forEach { f ->
                            Row(Modifier.fillMaxWidth().clickable {
                                selected = if (f.id in selected) selected - f.id else selected + f.id
                            }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (f.id in selected) "☑" else "☐", color = C.Accent, fontSize = 16.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(f.nickname, color = C.White)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    try {
                        if (gTitle.isNotBlank() && selected.isNotEmpty()) {
                            Api.createGroup(gTitle.trim(), selected.toList())
                            showCreateGroup = false
                            refreshKey++
                            toast = "群聊已创建"
                        }
                    } catch (e: Exception) { toast = e.message ?: "" }
                }
            }) { Text("创建", color = C.Accent) } },
            dismissButton = { TextButton(onClick = { showCreateGroup = false }) { Text("取消", color = C.Dim) } }
        )
    }

    if (showRequests) {
        AlertDialog(
            onDismissRequest = { showRequests = false },
            confirmButton = { TextButton(onClick = { showRequests = false }) { Text("关闭", color = C.Accent) } },
            title = { Text("新的朋友", color = C.White) },
            text = {
                Column {
                    Text("收到的申请（${incoming.size}）", color = C.Dim, fontSize = 13.sp)
                    incoming.forEach { r ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text("${r.user.nickname} @${r.user.username}", color = C.White, fontWeight = FontWeight.Medium)
                            Text(r.message, color = C.Dim, fontSize = 13.sp)
                            Row {
                                TextButton(onClick = { scope.launch { try { Api.acceptFriend(r.id); showRequests = false; refreshKey++ } catch (_: Exception) {} } }) { Text("同意", color = C.Accent) }
                                TextButton(onClick = { scope.launch { try { Api.rejectFriend(r.id); showRequests = false; refreshKey++ } catch (_: Exception) {} } }) { Text("拒绝", color = C.Dim) }
                            }
                        }
                    }
                    Text("我发出的（${outgoing.size}）", color = C.Dim, fontSize = 13.sp)
                    outgoing.forEach { r ->
                        Text("${r.user.nickname} · ${if (r.status == "rejected") "已拒绝" else "等待验证"}" +
                             (r.reply?.let { " · 回复：$it" } ?: ""), color = C.Dim, fontSize = 12.sp)
                    }
                }
            }
        )
    }

}

// 读取当前安装包版本号
fun appVersion(): String {
    return try {
        val ctx = top.bingtangyun.pigeon.AppCtx.get()
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "v" + pi.versionName + " (" + (if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()) + ")"
    } catch (e: Exception) { "v?" }
}

// ---------- 我的 ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(me: User?, onLogout: () -> Unit) {
    var nickname by remember { mutableStateOf(me?.nickname ?: "") }
    var bio by remember { mutableStateOf(me?.bio ?: "") }
    var avatarEmoji by remember { mutableStateOf(me?.avatar ?: "") }
    var showEdit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val presets = listOf("🐦", "🐱", "🐶", "🦊", "🐼", "🐯", "🐰", "🐸", "🐷", "🐻")

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("我的", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = C.Nav, titleContentColor = C.White))
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (avatarEmoji.isNotEmpty()) {
                Box(Modifier.size(80.dp).clip(CircleShape).background(colorOf(avatarEmoji)), contentAlignment = Alignment.Center) {
                    Text(avatarEmoji, fontSize = 40.sp)
                }
            } else {
                avatar(nickname, 80)
            }
            Spacer(Modifier.height(8.dp))
            Text(nickname, color = C.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("@${me?.username ?: ""}", color = C.Dim, fontSize = 13.sp)
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            itemRow("📷", "更换头像", if (avatarEmoji.isEmpty()) "点击设置" else avatarEmoji) { showEdit = true }
            itemRow("✏️", "编辑资料", nickname) { showEdit = true }
            itemRow("🔒", "私密账号", if ((me?.isPrivate ?: 0) == 1) "已开启" else "关闭") {
                scope.launch { try { Api.updateMe(isPrivate = if ((me?.isPrivate ?: 0) == 1) 0 else 1) } catch (_: Exception) {} }
            }
            itemRow("⏱", "数据保留", "3 天后自动删除") {}
            itemRow("🏷", "当前版本", appVersion()) {}
            itemRow("🚪", "退出登录", "", danger = true) { onLogout() }
        }
    }

    if (showEdit) {
        AlertDialog(
            onDismissRequest = { showEdit = false },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    try {
                        val u = Api.updateMe(nickname = nickname, bio = bio, avatar = avatarEmoji.ifEmpty { null })
                        bio = u.bio ?: ""; nickname = u.nickname; avatarEmoji = u.avatar ?: ""
                    } catch (_: Exception) {}
                }
                showEdit = false
            }) { Text("保存", color = C.Accent) } },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("取消", color = C.Dim) } },
            title = { Text("编辑资料", color = C.White) },
            text = {
                Column {
                    Text("选择头像", color = C.Dim, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { e ->
                            Box(Modifier.size(42.dp).clip(CircleShape).background(colorOf(e))
                                .clickable { avatarEmoji = e }, contentAlignment = Alignment.Center) {
                                Text(e, fontSize = 22.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(nickname, { nickname = it }, label = { Text("昵称") }, singleLine = true, colors = fieldColors())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(bio, { bio = it }, label = { Text("签名") }, singleLine = true, colors = fieldColors())
                }
            }
        )
    }
}

@Composable
fun itemRow(icon: String, title: String, value: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }
        .padding(vertical = 12.dp).background(C.Nav, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 17.sp)
        Spacer(Modifier.width(10.dp))
        Text(title, color = if (danger) C.Danger else C.White, modifier = Modifier.weight(1f))
        Text(value, color = C.Dim, fontSize = 13.sp)
    }
    Spacer(Modifier.height(10.dp))
}
