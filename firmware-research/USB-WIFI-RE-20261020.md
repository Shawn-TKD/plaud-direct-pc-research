# NotePin S USB / WiFi 静态逆向进展（2026-10-20）

> 工具：`usb_disasm.py`（本目录，带字面量池注解的 Capstone 反汇编）。
> 映射：容器文件 `0x19028` ↔ KM4 XIP runtime `0x0E000020`；`0xDCA78` ↔ SRAM `0x10005000`。
> 方法学：日志字符串以**完整字面量**（含 `[MMI_APP WARN]:` 前缀）被绝对指针引用，先定位完整字面量再搜 4 字节指针。

## 一、USB：数据线存在，U 盘模式待触发

1. **实测**：换数据线 + 唤醒状态插入 → 枚举为 CDC ACM 串口（COM12，`VID_0E8D&PID_0023`）。
   "描述符请求失败"只出现在睡眠态/劣质线组合 —— **硬件有数据线路**。
2. **vcom 静默**：AT 无响应、DTR/RTS 门控无效、插拔瞬间无日志 —— 生产固件关闭日志输出。
3. **固件 USB 栈**（`usb_plug_in_callback` @ KM4 XIP `0x0E021C7C`）：

```
query(17)  == 1  →  MSC（U 盘）分支          ; query 是 SRAM 0x1010C84D（镜像外代码，静态不可见）
                内部还要过: [0x10023A48] 状态字节 ∉ {2,3}（推测=录音中/WiFi中互斥）
                            0xe011cd8() 校验通过
else             →  检查 [0x1000000C]+4 bit3  →  "usb vcom start"
```

4. `tnt_usb_func_init(st, clk, usbms)` 的 `usbms` 参数由上述分支决定；日志确认 MSC 与 CDC ACM 分别初始化。
5. **公开 SDK 无 U 盘开关 API**（dex + native 全查过）；按键组合（双击/长按/三连击 + 插线）实测无效。
6. **未解**：query(17) 的状态来源。两条嫌疑路径：
   - 固件配置项（`tnt_config_*`，由 BLE「设备设置」命令写入）
   - 设备内部状态机（`mmi_udisk_event_handle` 处理的事件）
   下一步：反汇编 cmd 103/104/107/108/109 处理器（主分发表），找写 `[0x1000000C]`/配置区的命令。

## 二、BLE 命令分发表（主表，完整）

**表位置：容器 `0xBD04C`，runtime `0x0EA4044`，结构 = (u32 cmd_id, u32 handler) 对**

| cmd | handler | 备注 | cmd | handler | 备注 |
|---:|---|---|---:|---|---|
| 2 | 0xE010645 | | 26 | 0xE011615 | 文件列表 |
| 3 | 0xE011C89 | 笔状态(15B: state/privacy/keyState/uDisk…) | 28 | 0xE012E99 | 下载 |
| 4 | 0xE012B75 | | 29 | 0xE012875 | 下载 |
| 5 | 0xE00FCD5 | | 30 | 0xE010B29 | 删除 |
| 6 | 0xE010639 | 容量 | 38 | 0xE010929 | 标记 |
| 7 | 0xE00FC45 | | 50/51 | 0xE012685/0xE0118A1 | OTA |
| 8 | 0xE012FE1 | | 101-104 | | 设置 |
| 9 | 0xE010D45 | 电量(2B: 充电,百分比) | 107/108/109 | 0xE01079D/0xE010765/0xE01147D | 设置 |
| **10** | **0xE0117E5** | **WiFi 族入口** | 111 | 0xE01022D | |
| 20-23 | | 录音控制 | 112/114/116 | | 文件同步 |
| 24/25 | 0xE010CA5/0xE010C71 | 设置 | 128 | 0xE01018D | FindMy |
| | | | 138 | 0xE00FDE5 | |

- cmd 141 **不在表里**（实测超时，RE 文档的 141 属 SDK 侧编号）。
- **13-18 也不在主表** —— 在第二张索引表里（见下）。

## 三、WiFi 命令表（重大突破）

**表位置：容器 `0xD60C8`，runtime `0x0EA3CC0`，索引式（slot index = cmd id）**

| cmd | handler | 作用（从处理器行为推断）|
|---:|---|---|
| **14** | 0x0E063D0D | WiFi 配置：`[r0+0xc]` 上调 0xe08b8f0（取两个字段，疑为 SSID/PWD），置 `[r5+0x10]` bit0 |
| **15** | 0x0E063CD9 | WiFi 开：读 `[r1+6]`/`[r1+8]`，调 0xe05e2b0 |
| **18** | 0x0E063CBD | WiFi 关（0xe063b94 校验 → 0xe06fdc4）|
| **21** | 0x0E063D29 | 附加操作（`[r1+6]` 门控）|

处理器操作上下文结构体（`+0x1a40`、`+0x440`、`+0xc`、`+0x10` 标志位），
调用 0xe08b570 / 0xe08b8f0 / 0xe08b240 / 0xe05e2b0 —— 即固件字符串里的
`tnt_wifi_update_ssid_pwd` / `tnt_wifi_ap_start` / WebSocket 握手族。

固件侧已知（结合字符串）：设备开 **SoftAP + DHCP server**，App/PC 连热点后对设备内
**WebSocket server** 握手（`mmi_ws_handle_handshake_req: token=%s, stamp=%d`），
加密复用 BLE 会话的 ChaCha key/nonce/AAD。

### WiFi 快传 PC 化剩余工作

1. cmd 14 载荷格式（SSID/PWD 打包方式）—— 反汇编 0xe08b8f0 上下游
2. cmd 10（主表 0xE0117E5）与 cmd 14/15 的关系（10 可能是"请求 WiFi 传输总入口"）
3. WebSocket 端口/路径 + token/stamp 的来源（token 疑似经 BLE 下发）
4. 传输帧格式（file-head/tail/CRC/断点续传，RE 文档已列固件侧证据）

## 四、BLE 状态探针实测（cmd 3 原始响应）

```
CMD 3 -> 01 00 00 00 01 04 00 00 00 00 00 00 01 00 00
CMD 9 -> 01 64                (充电=1, 100%)
CMD 6 -> 00 80 6d 8b 0e ...   (free/total/录音秒数, u64 LE)
CMD 141 -> 超时（不在分发表）
```

按 iOS SDK `blePenState(state, privacy, keyState, uDisk, …)` 对齐：
疑似 u32 数组 state=1, [privacy,keyState]=0x401, **uDisk=0**, findMyToken=1（待反汇编 cmd 3 处理器逐字段确认）。

## 五、脚本

- `usb_disasm.py start_rt end_rt` — 带字面量池注解反汇编（自动识别 XIP/SRAM 指针、函数指针、日志字符串）
- 断点续传下载：`plaud-pc-bridge/download_all_notepin.py`（已完成 3 条 + 6.8MB 断点）

## 六、第二轮反编译补记（21:40）

1. **设置类命令确认是 setter**：cmd 103/109 处理器读取 `payload[2]` 后写配置/回包（cmd 103 回包带 `0x67`=103 自身回显；cmd 109 对 `payload[2]==0` 做 clz 布尔化后调 0xe03a894/0xe03a888 —— 像开关量写入）。cmd 104/107/108 区域与 FOTA/工厂测试字符串重叠（RE 文档所述工厂入口）。
2. **WiFi 上下文结构**：0xe063c30（释放/校验函数）显示 WiFi 会话结构含 4 个指针字段（+0x8/+0xc/+0x10/+0x14），半字字段 +0x4==0x10 有特殊分支；0xe063c5c 校验函数检查 `[ctx+0x1afa]` 字节、`[ctx+0x1ae8]`/`[+0x8f8]`/`[+0x8e8]` 指针链，并比较 `[r1+4]==0x3b`（59，疑为子命令或长度）。
3. **方向修正**：字符串 "Connected to websocket server" 表明**设备可能是 WS 客户端主动连手机**（而非服务器）——PC 化时可能要在电脑上起 WebSocket 服务端、把 IP 经 cmd 14 载荷下发给设备。两种角色都需动态实验判别。
4. **cmd 10 handler（0x0E0117E4）**：起始反汇编错位，但内部逻辑与电量/`[0x1000000C]` 状态位相关，疑似「WiFi 传输前置检查」；需对齐后细读。

### 下个会话的工作清单（按顺序）

1. 对齐反汇编 cmd 10 / cmd 14 / cmd 15 完整函数（从字面量池反推函数边界）
2. 追 0xe08b8f0 / 0xe08b240 / 0xe08b570 / 0xe05e2b0 / 0xe08acf0（疑似 config/socket API 层）确认载荷布局
3. 动态实验：BLE 发 cmd 14（构造载荷）→ 观察 SoftAP/STA 行为 → 确认 WS 角色 → 抓握手帧
4. U 盘：反汇编 cmd 103 完整函数定位其写入的配置字段 → 与 query(17) 语义对齐

## 七、WiFi 快传架构完全破解（21:45，SDK 参考路线）

**本地就有两份"标准答案"**：
1. `plaud-ios-lab/sdk/ios/PlaudWiFiSDK.framework`（含 Swift 头文件，API 全注释）
2. `output/RecorderHub-0.6.0-*.apk` dex 里的 `sdk/ble/wifi/*` 类（新版 SDK，IWifiTransferAgent + Java-WebSocket）

### 完整流程（从 iOS 参考应用 SyncManager.swift 逐行还原）

```
1. BLE: startWiFiTransfer（快传入口）
2. 设备开 SoftAP → BLE 回调 bleWiFiOpen(status, wifiName, wholeName, wifiPass)
   （SSID+密码由设备推给 App！不是我们配的）
3. App 等 3 秒（热点就绪）→ PlaudWiFiAgent.connectWifi(ssid, pass, 180s重试)
4. 手机/PC 加入热点后，在自身上启动 WebSocket 服务器，端口 8081
   （dex 证据: "port 8081"；PSWebSocketServer serverWithHost:port:）
5. 设备作为 WS 客户端 连接 App IP:8081（App IP 经 BLE 下发，dex: onWifiSyncConfigSet）
6. 握手: token + stamp（wifiHandshake 回调，0=成功；BleSDK 有 wifiUseAes 标志 → 载荷 AES）
7. 传输协议（WiFiAgentProtocol 全套回调）:
   wifiPower(电量,电压) → wifiFileList(files) → wifiSyncFile(sessionId,status)
   → wifiSyncFileData(sessionId, offset, count, binData) → wifiDataComplete
   → wifiFileDelete(sessionId, status) → wifiSyncFileStop / wifiClose(cleanClose=1000正常)
8. 结束: endWiFiTransfer()（BLE 命令让设备关热点；SDK 1.0.9+ 自动带关闭）
9. 附加能力: 速率测试(wifiRate)、笔端日志拉取(wifiLogs)、WiFi OTA、tips(录音键按下提醒)
```

### 关键参数

| 项 | 值 |
|---|---|
| WS 端口 | **8081**（dex 字符串 "port 8081"）|
| WS 角色 | 手机/PC = **服务器**，设备 = 客户端 |
| 热点就绪等待 | 3 秒 |
| 加入超时 | 180 秒（内部自动重试，needRetry 默认 true）|
| 正常关闭 | WebSocket code 1000（设备无文件可传也用它）|
| 载荷加密 | `wifiUseAes`（BleAgent 属性，疑复用 BLE 会话密钥）|
| App IP 下发 | BLE "WiFi Sync Config" 命令（dex: onWifiSyncConfigSet）|

### PC 实现 = 五步

1. BLE 连接 + 发快传入口命令 → 收 bleWiFiOpen（SSID/密码）
2. PC 加入设备热点（Windows WLAN API）
3. PC 起 WebSocket 服务器 :8081
4. BLE 发 Sync Config（PC 的热点内 IP）→ 设备连上来，握手
5. 按上述回调协议拉 fileList/syncFileData，本地解密（复用现有 decrypt_plaud_audio）

### 仍需逆向的字段（都可在 dex/iOS 头里找到）

- 快传入口 BLE 命令号（cmd 10? 21?）与 bleWiFiOpen 载荷解析
- Sync Config 命令载荷（IP 打包）
- WS 握手帧与 token 来源（token 可能就是 BLE 会话材料）
- wifiSyncFileData 的二进制帧格式（Java-WebSocket 上是 Text/Binary 帧）

## 八、WS 线上格式 100% 破解（21:58，SDK 反编译=读文档级别）

用 androguard 反编译 RecorderHub 0.6.0 APK 的 `sdk.ble.wifi.*`（119+112 个类，
输出在 `firmware-research/sdk-wifi-decompiled/`）——**这是读自己有授权的 SDK，不是逆向厂商**。

### WS 帧格式（WifiMessageProcessor$BaseWifiRequest.encode）

```
偏移  长度  内容（小端）
0     2    total_len = 8 + json_len
2     1    0x01（版本/魔数）
3     1    0x01
4     2    type（u16 LE，见下表）
6     2    json_len（u16 LE）
8     …    JSON（UTF-8）
```

### 消息类型（WifiRequest / WifiResponse 常量）

| type | Request（PC→设备） | Response（设备→PC） |
|---:|---|---|
| 0 | — | UNIVERSAL_ERR |
| 1 | HANDSHAKE | HANDSHAKE |
| 2 | SAY_HELLO_ACK | SAY_HELLO |
| 3 | HEARTBEAT_PONG | HEARTBEAT_PING |
| 4 | — | WIFI_CLOSE |
| 11 | GET_FILE_LIST | GET_FILE_LIST |
| 12 | FILE_SYNC | FILE_SYNC_STATUS |
| 13 | — | FILE_SYNC_CONTENT |
| 14 | FILE_DELETE | FILE_DELETE |
| 15 | FILE_SYNC_STOP | FILE_SYNC_STOP |

### 握手帧 JSON（与固件字符串完美互证）

```json
{"token": "<token左对齐补'0'到32字符>", "stamp": <unix秒>}
```
固件侧 `mmi_ws_handle_handshake_req: token=%s, stamp=%d` —— 两个来源完全吻合。
**token 来自 BLE 会话**（设备端校验，不匹配直接拒绝握手）。

### 加密

- 整帧加密：ChaCha20-Poly1305（或 AES-256-GCM，`wifiUseAes` 决定），密钥/nonce/AAD 来自 **BLE 会话**，
  帧间递增 seq（`encryptWithChaChaPoly1305Separate(data, key, nonce, seq)`）
- 加密开关随 BLE 连接建立（`saveEncryptionKeys` / `clearEncryptionKeys`）

### 文件数据帧

FILE_SYNC_CONTENT(13)：JSON 部分（含 offset 等字段）之后直接跟文件二进制数据
（`getData: p5[json_size+8 : ]`，长度必须 == size 字段）。

### 端口实锤（SDK 源码）

`WebSocketOperation.startServer: "Starting WebSocket server on port 8081" → new InetSocketAddress(8081)`

## 九、PC 实现 = 直接照抄 SDK 的 Java 逻辑

所有构建材料齐备，无需任何猜测：

1. **BLE 层**（已有）：认证会话 + 拿到 token/密钥 + 快传入口命令（待一轮对照：NiceBuildSdk.startWifiTransfer 的 BLE 命令号在 dex 里）
2. **WS 服务器**：Python `websockets` 库监听 0.0.0.0:8081
3. **设备连入** → 收 SAY_HELLO(2) → 回 SAY_HELLO_ACK(2)；或设备发 HANDSHAKE(1) 校验 token
4. **心跳**：设备 PING(3) → 回 PONG(3)
5. **拉取**：发 GET_FILE_LIST(11) → 收文件列表 JSON → 逐个发 FILE_SYNC(12, sessionId) →
   收 FILE_SYNC_STATUS(12)/FILE_SYNC_CONTENT(13) 拼接 → FILE_SYNC_STOP(15)
6. **解密落盘**：复用 `decrypt_plaud_audio` → 可播放 Opus/Ogg
7. **删除（可选）**：FILE_DELETE(14)

预计工作量：一个专注的编码会话。所有"未知"已消灭。
