import SwiftUI

// MARK: - 登录 / 注册

struct LoginView: View {
    @EnvironmentObject var app: AppState
    @State private var username = ""
    @State private var password = ""
    @State private var nickname = ""
    @State private var registerMode = false
    @State private var captcha: Captcha?
    @State private var answer = ""
    @State private var errMsg = ""
    @State private var busy = false
    @State private var appear = false

    var body: some View {
        ZStack {
            LiquidBackground()
            ScrollView {
                VStack(spacing: 14) {
                    Spacer().frame(height: 40)
                    logo
                        .scaleEffect(appear ? 1 : 0.8)
                    Text(registerMode ? "注册鸽信" : "登录")
                        .font(.title.weight(.bold))
                    Text("轻量 · 端到端加密 · 消息只存 3 天")
                        .font(.subheadline)
                        .foregroundColor(PigeonTheme.textDim)
                    Spacer().frame(height: 8)

                    glassField("用户名", text: $username, keyboard: .asciiCapable)
                    glassField("密码", text: $password, secure: true)
                    if registerMode { glassField("昵称", text: $nickname) }

                    captchaRow

                    if !errMsg.isEmpty {
                        Text(errMsg).font(.footnote).foregroundColor(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Button(action: submit) {
                        Group {
                            if busy { ProgressView().tint(.white) }
                            else { Text(registerMode ? "注册并登录" : "登录").font(.headline) }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                    }
                    .foregroundColor(.white)
                    .background(PigeonTheme.accent, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .disabled(busy)

                    Button(registerMode ? "已有账号？登录" : "还没有账号？注册") {
                        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                            registerMode.toggle()
                            errMsg = ""
                        }
                    }
                    .font(.subheadline)
                    .foregroundColor(PigeonTheme.accent)
                }
                .padding(.horizontal, 26)
            }
        }
        .onAppear {
            withAnimation(.spring(response: 0.55, dampingFraction: 0.75)) { appear = true }
            Task { await loadCaptcha() }
        }
    }

    private var logo: some View {
        Text("鸽")
            .font(.system(size: 40, weight: .bold))
            .foregroundColor(.white)
            .frame(width: 88, height: 88)
            .background(
                LinearGradient(colors: [PigeonTheme.accent, Color(red: 0.17, green: 0.56, blue: 0.85)],
                               startPoint: .top, endPoint: .bottom),
                in: RoundedRectangle(cornerRadius: 22, style: .continuous)
            )
            .shadow(color: PigeonTheme.accent.opacity(0.4), radius: 20, y: 10)
    }

    private func glassField(_ placeholder: String, text: Binding<String>, keyboard: UIKeyboardType = .default, secure: Bool = false) -> some View {
        Group {
            if secure {
                SecureField(placeholder, text: text)
            } else {
                TextField(placeholder, text: text)
                    .keyboardType(keyboard)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
            }
        }
        .padding(14)
        .glass(radius: 14)
    }

    private var captchaRow: some View {
        HStack(spacing: 10) {
            Text(captcha?.question ?? "加载中…")
                .font(.system(.body, design: .monospaced).weight(.semibold))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button { Task { await loadCaptcha() } } label: {
                Image(systemName: "arrow.clockwise").font(.system(size: 18, weight: .semibold))
            }
            .foregroundColor(PigeonTheme.accent)
            TextField("答案", text: $answer)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.center)
                .frame(width: 72)
                .padding(.vertical, 12)
                .background(PigeonTheme.inBubble, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .padding(12)
        .glass(radius: 14)
    }

    private func loadCaptcha() async {
        do { captcha = try await API.shared.captcha() }
        catch { captcha = nil; errMsg = "验证码加载失败：\(error.localizedDescription)" }
    }

    private func submit() {
        guard let captcha, let ans = Int(answer.trimmingCharacters(in: .whitespaces)) else {
            errMsg = "请先回答人机验证"; return
        }
        busy = true; errMsg = ""
        Task {
            do {
                API.shared.token = nil
                API.shared.device = app.device
                let resp: AuthResp
                if registerMode {
                    resp = try await API.shared.register(username: username, password: password,
                                                        nickname: nickname.isEmpty ? username : nickname,
                                                        captchaToken: captcha.token, captchaAnswer: ans)
                } else {
                    resp = try await API.shared.login(username: username, password: password,
                                                     captchaToken: captcha.token, captchaAnswer: ans)
                }
                API.shared.token = resp.token
                await MainActor.run {
                    withAnimation(.spring(response: 0.5, dampingFraction: 0.8)) {
                        app.setAuth(token: resp.token, user: resp.user)
                    }
                }
            } catch {
                await MainActor.run { errMsg = error.localizedDescription }
                Task { await loadCaptcha() }
            }
            await MainActor.run { busy = false; answer = "" }
        }
    }
}
