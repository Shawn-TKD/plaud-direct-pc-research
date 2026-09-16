# 绕开官方 App，把自己的 PLAUD 接入自有应用：NotePin S 与 Note Pro 的实验记录

这篇文章记录的是**自有设备的互操作研究**。我们没有破解 PLAUD 的签名私钥，也没有让设备跳过认证。实际做到的是：用 PLAUD 官方开发者流程取得一次设备认可的身份材料，理解并复现设备的加密连接，再由自己的 Android 应用和 Windows 客户端读取录音。Android 应用不依赖官方 PLAUD App；目前仍使用 PLAUD 提供的 SDK AAR 执行底层 BLE。Windows 客户端则自行实现了 BLE、RSA 与 ChaCha 会话。

## 最终做到了什么

| 环节 | NotePin S | Note Pro |
| --- | --- | --- |
| 自有设备完成官方 SDK 引导 | 已完成 | 已完成 |
| Windows 不经手机、直接建立新的加密 BLE 会话 | 已验证 | 已验证两次 |
| Windows 读取状态与文件列表 | 已验证 | 已验证；当时文件列表为空 |
| Windows 下载、解密并转换录音 | 已验证一条录音 | 尚未用 Note Pro 录音验证 |
| Windows Wi-Fi 快传 | 尚未验证 | 尚未验证 |
| 固件静态分析 | V1.2.7 | V1.7.0 |

这里的“直接连接”指设备休眠或断开后，客户端重新扫描、认证并连接；不是承诺蓝牙物理链路永远不断。也不能把对现有固件的验证外推为未来任何型号、版本都永久有效。

## 第一阶段：把官方 SDK 当作正确答案

PLAUD [公开的 Embedded SDK](https://github.com/Plaud-AI/plaud-sdk-public)提供 Android AAR 和 iOS Framework，没有公开的 Windows SDK。因此最初先在一台安卓手机上运行 SDK，确认自己的设备能够扫描、连接、绑定、列文件、下载和导出音频。这个阶段不是为了让手机长期当网关，而是取得一条可观察、可对照的成功路径。

开发者身份的顺序是：Client ID 与 Client Secret 换 Partner Token，再为稳定的应用 User ID 申请短期 User Access Token（JWT）。SDK 用这个 JWT 向 PLAUD 服务申请 RSA 密钥对和**指定序列号的签名**。`sn-sign` 的 `type` 必须匹配设备类型，例如 Note Pro 为 `notepro`、NotePin S 为 `notepins`。JWT 是云端 API 凭据，不是发给设备的永久密码。

成功的 Note Pro 首次实验还包括两种不同的绑定：设备在 BLE 上接受本地 bind 身份；PLAUD 的云端 `sdk/bind` 接口登记设备，使它出现在开发者平台的设备列表。平台显示设备，不等于 Windows 已能通过 BLE 连接，因此我们又做了独立的电脑重连实验。

## 第二阶段：还原设备实际检查什么

老款 [PLAUD NOTE NB-100 的公开研究](https://github.com/Kurikara-dev/plaud-note-nb100-re)帮助定位 GATT、文件列表和传输命令，但不能直接套用到 NotePin S 或 Note Pro：我们观察到的设备使用 `portVersion=20` 的加密握手，而 NB-100 的作者实测为 `portVersion=10`。

结合 Android SDK 的成功日志、AAR 行为、BLE 抓包与自有设备的重连结果，我们把连接分成两层：

```text
一次性在线引导：User JWT → gen-key / sn-sign → RSA 身份 + 设备 SN 签名

每次本地连接：扫描 BLE → 发送 SN 签名 → 提交客户端 RSA 公钥
              → 设备用该公钥包装新的会话材料 → 客户端 RSA 私钥解开
              → 双方以 ChaCha20-Poly1305 通信 → 发送绑定身份
              → 查询状态、文件列表和录音
```

预握手使用命令 `65040`，必要时通过 `65042` 交换 RSA 公钥和会话包；认证完成后，`CMD 1` 承载绑定身份。文件列表和区间下载使用 `26`、`28`、`29` 等命令。每次重连都会生成新的 ChaCha 会话参数，长期保存的则是该设备的 SN 签名、客户端 RSA 身份和 bind 身份。设备不会在每次 BLE 重连时解析已经过期的 JWT。

NotePin S V1.2.7 固件中的 PLAUD RSA-2048 公钥，成功验证了 Note Pro 的签名；验证消息是**原始序列号**，不包含 JWT 的到期时间或客户端 RSA 公钥。后来下载的 Note Pro V1.7.0 固件中也找到了同一把公开验签公钥和 SN 签名验证代码。这解释了两款设备的认证思路为何相通，却不表示一台设备的签名能拿来连接另一台设备。公钥只能验签，不能生成 PLAUD 的签名。

两份固件的硬件结构并不相同：NotePin S 镜像以 AmebaD host 加从控固件组成；Note Pro 的主镜像标有 `RTL8773DO`，并嵌有另一段 `OTA1` 镜像，日志显示升级时会拆分 CPU 与 Wi-Fi 固件。详见[固件比较](../firmware-research/NOTE-PRO-VS-NOTEPIN-S.md)。

## 第三阶段：让电脑和自有 App 接手

Windows 客户端把长期材料加密存入当前用户的 DPAPI。它不依赖手机转发蓝牙数据：扫描设备、建立新的 RSA／ChaCha 会话、读取状态和下载录音都在电脑完成。NotePin S 的一条录音已经经过“下载到临时文件、确认传输结束、解密 PLAUD.AI 容器、将 Opus 帧封装为可播放 Ogg”的完整链路；源文件保留在设备上。Note Pro 已完成两次新的电脑 BLE 会话，但当时没有录音可供下载。

为了让日常使用的 Android App 也不依赖官方 PLAUD App，我们把获得的身份材料做成加密迁移包，导入自有的 **Recorder Hub**，再用 Android Keystore 加密保存。App 在离线重连时恢复缓存的 SN 签名、RSA 密钥和 bind 身份，调用 SDK AAR 的 BLE 能力。这里要区分两件事：**没有用官方 App**；**Android 版本尚未移除官方 SDK AAR**。SDK 某些接口只从 JWT `sub` 读取 Wi-Fi 身份时，App 会在本机临时提供由缓存 bind 身份构成的接口适配值；它不是 PLAUD 签发的新 JWT，也不用于向云端申请签名。

Android App 还实现了 SDK 管理的 Wi-Fi 快传入口，并默认在同步后保留设备录音；手动删除是独立操作。这不意味着 Windows 端的 Wi-Fi 协议已经逆向或实测完成。

对于一台全新设备，已经验证的路线仍是“官方 SDK 一次性在线引导 → 加密迁移 → 自有 App／电脑长期离线重连”。项目里还实现了“手机只申请材料、不连接设备”的实验性捷径，以及电脑直接调用 `gen-key`／`sn-sign` 的代码；前者尚未在全新未绑定 Note Pro 上完成实测，后者在这台电脑上遇到 PLAUD 服务端 HTTP 403／1010，因此都不应写成已跑通的通用方案。

## 录音进入硅基流动和闪念贝壳

Recorder Hub 的音频默认保存在本机。用户配置自己的硅基流动 API Key 后，App 把选定录音提交给[语音转文本接口](https://docs.siliconflow.cn/docs/api/audio-transcriptions-post)；默认模型配置是 `FunAudioLLM/SenseVoiceSmall`。生成总结时，代码调用兼容的[聊天补全接口](https://docs.siliconflow.cn/docs/api/chat-completions-post)，也可选用单独配置的 DeepSeek Key。模型名称可在 App 设置中调整；本项目不把 API Key 写入公开源码。

需要保存为笔记时，App 才用用户配置的闪念贝壳 MCP HTTPS 地址和 Bearer Token 建立 Streamable HTTP 会话，依次执行 `initialize`、`tools/list` 和 `tools/call`。它按服务端实际公布的工具列表选择 `note_create` 或 `note_update`，将标题、总结和转录全文写入；成功后保存返回的 `note_id`，便于下次更新而非重复创建。**写入前会向用户展示预览并要求确认。**音频文件不会因为这一步自动上传到闪念贝壳；发送的是整理后的文字。App 源码中已有这条调用链，但本文的 PLAUD 实机实验没有另行证明每一种模型与每个 MCP 账号都端到端成功。

这三层职责分开后，离线与在线的边界也很清楚：设备发现、加密认证、录音下载和本地播放可离线完成；首次签名申请、硅基流动转录／总结以及闪念贝壳写入需要联网。

## 开源范围与复现边界

本仓库的 [`pc-bridge`](../pc-bridge/) 是独立的 Windows BLE 客户端；[`android`](../android/) 是一次性引导和 Recorder Hub 源码；[`firmware-research`](../firmware-research/) 提供解析脚本和研究记录。PLAUD 的专有 AAR 需从[官方仓库](https://github.com/Plaud-AI/plaud-sdk-public)自行取得，才能构建 Android 版本。公开仓库不包含设备固件二进制、AAR、录音、真实序列号、JWT、Client Secret、SN 签名或 RSA 私钥。

这个项目证明了“**绕开官方 PLAUD App，把自己的设备接入自有应用**”可行；它没有证明“任何人只靠固件便能为任意 PLAUD 设备签发身份”，也没有完成 Note Pro 录音下载或电脑 Wi-Fi 快传的实机验证。
