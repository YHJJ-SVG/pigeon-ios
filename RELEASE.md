# 鸽信发版清单（每次更新必须全做）

版本号是唯一真源：`VERSION` 文件。

## 1. 改代码
```bash
python scripts/bump_version.py patch   # 或 minor / major
```
自动同步：`VERSION`、iOS Info.plist（版本+build）、Android build.gradle、后端 APP_VERSION、iOS 个人页版本号。

## 2. 编译
- Android：GitHub Actions `android-apk.yml`
- iOS：Codemagic `ios-unsigned`（GitHub Actions 的 macOS 额度已用尽）

## 3. 下载当前产物
- APK：GitHub artifact → `Pigeon-android.apk`
- IPA：Codemagic artifact → `Pigeon-native.ipa`

## 4. 部署到**所有**下载线路（漏一个就会出现旧包）

| 线路 | 位置 | 是否必须 |
|---|---|---|
| 本站（七枫云） | `/source/install/Pigeon-android.apk`、`Pigeon-native.ipa` | ✅ 必做 |
| GitHub Releases | tag = 版本号，两个 asset | ✅ 必做 |
| **jsDelivr 加速目录** | 仓库 `dist/` 目录（`tmp/repo/dist/` → push） | ✅ 必做（曾漏过） |
| ghfast / gh-proxy 镜像 | 自动跟随 GitHub Releases，无需单独传 | — |

```bash
mkdir -p tmp/repo/dist && cp Pigeon-android.apk Pigeon-native.ipa tmp/repo/dist/
python tmp/gh_push.py YHJJ-SVG/pigeon-ios tmp/repo
```

## 5. 校验（每个线路都要 200 + 字节数一致）
```bash
# 本站
curl -sIL https://aapi.bingtangyun.top/install/Pigeon-android.apk | grep -i content-length
# jsDelivr（@main 有缓存，改用 commit sha 或稍等）
curl -sIL "https://cdn.jsdelivr.net/gh/YHJJ-SVG/pigeon-ios@<sha>/dist/Pigeon-android.apk"
```

## 6. 更新下载页
`server/install/index.html`：版本徽标 + 三条线路链接 → 上传到七枫云 `/source/install/index.html`。

## 7. 后端（如果改了 API 代码）
SFTP 上传到新服务器 `/opt/pigeon/`（index.php、lib/*.php），
```bash
chown -R www-data:www-data /opt/pigeon && systemctl reload php7.4-fpm
curl -s http://127.0.0.1:43211/api/health
```
