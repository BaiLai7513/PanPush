# PanPush

> 网盘分享链接 → 真实直链 → 一键推送下载。Android 原生实现，支持 **root 直读** 与 **免 root 应用内登录** 两种凭据通道。

[![Platform](https://img.shields.io/badge/platform-Android%2026%2B-3ddc84)](#)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue)](#)
[![License](https://img.shields.io/badge/license-MIT-yellow)](LICENSE)

---

## 这是什么

把网盘的「分享链接」（常带提取码、甚至带 JS 验证页）解析成**可直接下载的真实直链**，
再交给下载器（Gopeed）或浏览器去下载。

核心思路是**把「解析」和「下载」解耦**：

```
分享链接 + 提取码
      ↓  解析层：只负责产出「直链 + 文件名 + 大小 + 必需请求头」
   DownloadItem
      ↓  下载层：Gopeed（本地 HTTP API，可携带自定义请求头）/ 浏览器（自带会话，可执行 JS）
   真实文件
```

## 支持的网盘

| 网盘 | 解析 | Gopeed 推送 | 浏览器下载 | 凭据获取方式 |
|---|:--:|:--:|:--:|---|
| 百度网盘 | ✅ | ✅ | 🚫 | 应用内登录 / root 直读 |
| 123 云盘 | ✅ | ✅ | ✅ | 应用内登录 / root 直读 |
| 夸克网盘 | ✅ | ✅ | 🚫 | 应用内登录（桌面 UA，拿 `__puus`） |
| 阿里云盘 | ✅ | ✅ | ✅ | root 直读 / 应用内登录 |
| 迅雷云盘 | ✅ | ✅ | ✅ | root 直读三件套 / 应用内登录（四样头） |
| 蓝奏云 | ✅ | ⚠️ | ✅ | 免登录（分享页匿名可解析） |

> 🚫 标记的站点（夸克 / 百度）**不提供浏览器下载入口**：它们的直链必须带专用
> UA / Referer / Cookie 才可访问，外部浏览器裸开会提示「无法访问该网站」。
> 请改用 Gopeed 推送（Gopeed 会把请求头写进任务）。

> ⚠️ **应用内登录**抓到凭据后**不会自动保存**，需点右上角「完成并保存」。

> ⚠️ **蓝奏云**：部分 CDN（如 `*.dmpdmp.com/file/?token`）会对裸 HTTP 请求返回
> 「网络异常需要验证」的 JS 验证页，Gopeed 没有 JS 引擎会把它当文件存下来（只有几 KB）。
> 这类链接请用**浏览器下载**——浏览器会执行验证脚本，再交给下载器即可。

## 功能

- **两种凭据通道**
  - `root 直读`：直接读网盘 App 的本地凭据（Android `/data/data/<pkg>`）
  - `应用内登录`：内置 WebView 登录网页版，从 Cookie / localStorage 提取令牌
- **应用列表扫描**：自动识别已安装的网盘 App（含蓝云等第三方客户端）
- **提取码自动识别**：粘贴整段分享文本即可，支持 `?pwd=` 与中文「提取码：xxxx」
- **Gopeed 集成**：`POST /api/v1/tasks`，支持把 Referer / Cookie / UA 写进任务
- **运行日志**：解析/推送全过程可查，便于自助排查

## 安装

1. 从 [Releases](../../releases) 下载最新 `PanPush-*-arm64-release.apk`
2. 需要 **Android 8.0+（API 26）**，仅 `arm64-v8a`
3. 安装后按提示授予网络权限；root 功能需自行授予 su

## 使用

```
① 粘贴分享链接（可连同「提取码：xxxx」一起贴）
② 点「解析」
③ 三选一：
   ① 取直链并推送 Gopeed   —— 需把 Gopeed 挂成小窗（后台会被系统冻结）
   ② 手动拉起并推送        —— Gopeed 没在后台时用这个
   ③ 用浏览器打开直链      —— 带 JS 验证页的链接走这条
```

## 构建

```bash
# 需要 JDK 17 + Android SDK 35
cd panpush-android
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-arm64-v8a-release.apk`

## 已知限制

| 限制 | 原因 |
|---|---|
| Gopeed 在后台会「假死」 | Android 冻结后台进程：端口仍 LISTEN 但连接超时。**挂小窗**即可 |
| 第三方下载器带不了请求头 | Android 的 `ACTION_VIEW`/`ACTION_SEND` 协议传不了 HTTP 头 |
| 蓝奏 token 形态直链 Gopeed 下不了 | CDN 验证页需要执行 JS，见上文 |
| 迅雷网页令牌 captcha 寿命短 | 重新打开一次迅雷登录页即可刷新 |

## 免责声明

见 [DISCLAIMER.md](DISCLAIMER.md)。**使用前请务必阅读。**

## 许可

[MIT](LICENSE)
