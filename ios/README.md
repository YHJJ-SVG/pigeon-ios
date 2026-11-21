# 鸽信 iOS（iOS 12+）— 出 ipa 与安装说明

原生外壳工程：UIKit + WKWebView，最低 **iOS 12.0**（SwiftUI 要 13+，所以没用）。
外壳加载 `https://aapi.bingtangyun.top/app/`，带下拉刷新、加载进度条、右滑返回、状态栏适配。

```
ios/
├─ project.yml              XcodeGen 工程描述（不用手写 pbxproj）
├─ Info.plist
├─ Sources/
│  ├─ AppDelegate.swift
│  ├─ WebViewController.swift
│  ├─ LaunchScreen.storyboard
│  └─ Assets.xcassets/       AppIcon（已生成全尺寸）
└─ ../.github/workflows/ios-ipa.yml
```

## 为什么要在 GitHub 上编

iOS App 只能用 **macOS + Xcode** 编译。当前这台是 Windows，写得出源码但编不出 ipa。
`ios-ipa.yml` 会借 GitHub 的 macOS 机器编出**未签名 ipa**（免费额度够用）。

## 三步出 ipa

1. 建一个 GitHub 仓库（私有也行），把 `tg/` 整个目录传上去。
2. 打开仓库的 **Actions → build-ios-ipa → Run workflow**，等 5–10 分钟。
3. 跑完在本次运行页面底部下载 **Pigeon-unsigned-ipa** 工件，里面是 `Pigeon-unsigned.ipa`。

## 怎么装到你手机上

未签名的 ipa 不能双击安装，必须用你的 Apple ID 重签一次：

| 方式 | 成本 | 有效期 | 说明 |
|---|---|---|---|
| **Sideloadly**（Windows 可用） | 免费 Apple ID | **7 天**，到期重签 | 装 iTunes/iCloud 后，数据线连 iPhone，拖入 ipa，输入 Apple ID 即可 |
| AltStore | 免费 Apple ID | 7 天 | 需同一 Wi-Fi 下跑 AltServer |
| 开发者账号 + 自签 | 99 美元/年 | 1 年 | 可自签或用 TestFlight 内测分发 |
| 企业证书签名 | 淘宝几十块 | 随时吊销 | 不建议，掉签就打不开 |

Sideloadly 装完第一次要在 iPhone「设置 → 通用 → VPN与设备管理」里信任你的开发者证书，然后才能打开。

## 不想折腾签名？

iPhone 上 **Safari 打开 https://aapi.bingtangyun.top/app/** → 分享 → **添加到主屏幕**。
iOS 12 起就支持，效果和这个 ipa 几乎一样（独立图标、全屏、无地址栏），而且服务端更新即时生效，不用重签。

## 改内容后怎么更新

- H5 部分：改 `server/app/index.html` 上传即可，App 里的页面下次打开就是新的（原生外壳不用重编）。
- 外壳部分（图标、启动页、包名）：改 `ios/` 后重新跑一次 Actions。
- 想改打开的地址：`ios/Sources/WebViewController.swift` 里的 `startURL`。
