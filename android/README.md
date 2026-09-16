# 录音中枢 Android

## Note Pro 一次性在线引导

使用 `-PnoteProBootstrap=true` 构建调试包时，应用以独立包名
`com.shawn.recorderhub.noteprobootstrap` 安装，标签为“Note Pro 引导”，不会覆盖
日常使用的录音中枢。该构建启用 PLAUD 在线 SDK 流程，只显示 `881` 开头的
Note Pro；可选用 `-PbootstrapSn=881...` 限定到一台设备。首次打开时输入短期
User Access Token；JWT 和 Client Secret 都不写入引导 APK。

输入 JWT 后有两条路线：“手机连接验证”按官方 SDK 完成一次真实 BLE 绑定；
“仅准备电脑凭据”只调用 `gen-key` 和 `sn-sign`，不占用设备蓝牙，由电脑完成
首次连接。第二条路线是实验性优化，尚未在一台全新未绑定 Note Pro 上完成实机
验证。若需在 PLAUD Portal 显示设备，仍需按官方绑定 API 完成云端登记。

连接前等待 SDK 取得 RSA 密钥，并为目标 SN 调用 `sn-sign`。首次成功后，应用的
`OfflineAuthStore` 将设备签名、RSA 密钥和绑定身份加密缓存。跨到电脑时使用
`PcBootstrapExporter` 输出以电脑公钥加密的引导包，再由电脑以 Windows DPAPI
保存。完成迁移前不要卸载引导 App；需要切回其他应用时按 PLAUD 的云端与 BLE
解绑流程处理。


一个本地优先的多录音设备工作台。界面采用安静、留白、黑白为主的 PLAUD 式视觉语言，
但没有复制其品牌素材。录音下载后可以在本机播放，通过硅基流动转录并生成总结，再由用户确认后推送到闪念贝壳 MCP。

## 当前可用范围

| 设备/能力 | 当前状态 |
| --- | --- |
| PLAUD Note / NotePin / NotePin S / NotePro | 导入电脑端加密凭据包一次；凭据转存 Android Keystore，后续离线扫描、连接与同步，无需 JWT 或 PLAUD App |
| 钉钉 A1 | `DID + corpId + deviceSecret` 离线鉴权、后台重连、设备状态/电量/容量、长录音目录与流式下载、DTYJ/BABA 转 Ogg、语音备忘录实时接收、标记事件、一次确认后删除设备录音 |
| 飞书录音豆 D3200 | 原生 BLE 直连；电量/固件/存储、录音控制与列表、ECDH/HKDF/AES-CTR 本地解密、Ogg 播放、下载和删除 |
| 硅基流动 | `/v1/audio/transcriptions` 转录，兼容 `/v1/chat/completions` 的模型生成总结 |
| DeepSeek | 可选；填写后优先用于总结 |
| 闪念贝壳 | MCP Streamable HTTP：初始化、发现工具、确认后调用写入工具 |

钉钉 A1 的桌面协议已经在相邻的 `../dingtalk-a1-pc-tools` 真机验证，并移植到 Android
常驻会话。长录音目录会先以“待同步”形式进入统一 Files；点开即可下载，页面退出后传输
仍会继续。短按产生的语音备忘录通过 `0x0100/0x0116/0x0117` 实时接收并自动保存为 Ogg。

飞书录音豆适配器移植自已经实机验证的 `soundcore Work / D3200` 浏览器实现。它使用
每次连接临时生成的 P-256 ECDH 会话，通过 HKDF-SHA256 和 AES-CTR 在手机本地解密，
不需要飞书账号、云端登录、固定设备密钥或手工配置。

## 使用

1. 安装 `RecorderHub-0.5.0-a1-product-parity-debug.apk`。
2. 首页展开设备卡片，点击“添加录音设备”。
3. PLAUD 首次选择 `plaud-device-auth.portable.json` 并输入一次传输密码；钉钉 A1 首次填写自己设备的 DID、corpId、deviceSecret；飞书录音豆无需配置，直接搜索并连接 D3200。
4. 在各设备页同步目录或下载录音。钉钉 A1 的设备目录会直接显示在 Files；点开待同步录音即可导入。三种设备的本地录音统一在 Files 中播放、删除和处理。
5. 在“设置 → AI 与自动化”填写硅基流动 API Key；可选填 DeepSeek Key。
6. 填写闪念贝壳 MCP 的 HTTPS 地址和 Bearer Token。
7. 打开一条已下载录音，点击“生成转录与总结”；完成后可预览并确认发送到闪念贝壳。

## 隐私与密钥

- API Key、MCP Token、A1 deviceSecret 和导入后的 PLAUD 握手材料通过 Android Keystore 的不可导出密钥加密保存。
- 密钥字段不会回显，日志也不会记录密钥。
- BLE 发现、鉴权、下载、转换、存储和播放可离线完成。
- 转录、总结及 MCP 推送需要联网。
- MCP 写入始终显示预览并要求确认。
- 不要把任何真实密钥写入 `local.properties` 之外的版本控制文件。

默认 MCP 地址：

```text
https://api.ideashell.cn/ideashell/mcp
```

## 构建

需要 JDK 17 与 Android SDK 34：

```powershell
gradle assembleDebug
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 下一步

1. 为 A1 文件索引补充分页，覆盖设备中超过最近 100 条的历史录音。
2. 在更多固件版本上回归 A1 Wi-Fi 快传；当前 A1 下载使用可靠的 BLE 流式落盘。
3. 在实体 D3200 上继续回归超长文件下载、删除和录音控制。
4. 将三个厂商适配器进一步收敛到统一 `RecorderDeviceAdapter` 状态模型。
