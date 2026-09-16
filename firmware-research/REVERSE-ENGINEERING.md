# PLAUD NotePin S V1.2.7 固件逆向记录

公开版说明：本文只分析 NotePin S V1.2.7 镜像。原始固件、`analysis/extracted/`
二进制分段和提取出的公钥文件不会随源码发布；运行本目录脚本并提供自己取得的
镜像后可在本机生成。2026-09-16 的 Note Pro 实机签名可由该 NotePin S 镜像的
公钥验证，但没有 Note Pro 固件镜像可供静态对比。

分析日期：2026-09-16

## 结论

这份 NotePin S 固件证明，电脑端现有实现没有跳过设备认证，而是完整复现了官方 SDK 的认证协议。设备端会在本地验证一份由 PLAUD 签发的 SN 签名，验证通过后再接受客户端 RSA 公钥，并为每次连接生成新的 ChaCha20-Poly1305 会话材料。

当前缓存的 SN 签名没有密码学有效期。实测该签名是 PLAUD 使用 2048 位 RSA 密钥对设备原始 SN 做的 PKCS#1 v1.5 + SHA-256 签名；被签消息只有 SN，不含 JWT、时间戳、电脑公钥或到期时间。因此，JWT 过期不会影响这份签名在当前协议和固件上的离线重连。

这把签名也不绑定某一台电脑。另一台电脑有两种连接方法：

1. 迁移原 RSA 私钥、公钥、SN 签名和 bind token，可保持设备现有身份不变。
2. 复用 SN 签名并发送 65056 清除设备保存的客户端公钥，然后注册一套新的 RSA 密钥。这个路径已由 SDK 和固件静态确认，但没有在当前设备上执行，以免破坏已经验证成功的电脑配对。

“长期可用”的判断因此可以提高到：**在当前 V1.2.7 协议下，认证材料本身没有时间过期，能够离线、跨重启反复连接。** 仍可能使它失效的事件是恢复出厂、主动清除配对、固件更换根验签公钥或修改认证策略。

## 固件来源与完整性

- 型号：`notepins`
- `versionCode`：66055，对应 SDK 的 `V1.2.7` 编码规则
- 文件大小：1,145,048 字节
- MD5：`a3d986fce83e1f1f2b8cbdd2e70eb59a`
- SHA-256：`03a147a3eac77e5e7899b3bbc09483f84719ff683687a620da3ccc3f087ec038`
- 来源：PLAUD 官方 OTA 接口返回的下载地址

接口没有返回 `file_md5`，因此 MD5 和 SHA-256 是下载后在本地计算的，不是服务端声明值。

## 固件容器结构

文件不是单一裸镜像，而是一个 PLAUD host/slave 组合包：

| 文件偏移 | 长度 | 内容 |
|---:|---:|---|
| `0x00000000` | 8 | 两个小端长度：host 1,024,032；slave 120,488 |
| `0x00000008` | 1,024,032 | Realtek AmebaD host OTA |
| `0x000FA028` | 520 | PLAUD slave 头，含 `Plaud###` 标记 |
| `0x000FA230` | 120,488 | slave 固件，末尾有 512 字节 `PLAUD.AI` 信息区 |

host 是标准 Realtek `OTA1` 容器，目标 Flash 地址为 `0x0800B000`。内部镜像如下：

| 镜像 | 头偏移 | 运行地址 | 大小 |
|---|---:|---:|---:|
| KM0 XIP | `0x28` | `0x0C000020` | 90,816 |
| KM0 SRAM | `0x16308` | `0x00083000` | 9,056 |
| KM4 XIP | `0x19028` | `0x0E000020` | 801,328 |
| KM4 SRAM | `0xDCA78` | `0x10005000` | 119,040 |
| KM4 PSRAM | `0xF9B98` | `0x02000000` | 0 |

镜像签名 `81958711`、32 字节 `IMAGE_HEADER`、4 KB 对齐规则以及 KM0/KM4 地址均与 Realtek 官方 AmebaD 源码一致。这确认主控属于 RTL8721D/AmebaD 系列，使用 KM0 + KM4 双 Cortex-M33 架构。

slave 固件包含 `FMNA`、`Find My`、`tnt_spis_build_handshake`、BLE 事件处理和密钥轮换字符串。它应当负责低功耗 BLE/Apple Find My 一侧，并通过 SPI 与 Realtek host 通信；具体从控芯片型号尚未从当前镜像中确认。

## 认证协议的固件证据

### 命令分发

KM4 固件的命令分发表直接比较以下命令：

| 命令 | 十六进制 | SDK 名称 | 作用 |
|---:|---:|---|---|
| 65040 | `0xFE10` | `PRE_HANDSHAKE` | 发送 PLAUD SN 签名 |
| 65041 | `0xFE11` | `PRE_HANDSHAKE_CNF` | 签名阶段确认 |
| 65042 | `0xFE12` | `RSA_PUBLIC_KEY` / 确认 | 上传客户端 RSA 公钥；设备也用该类型回传加密会话包 |
| 65056 | `0xFE20` | `PRE_HANDSHAKE_AND_CLEAR` | 清除已保存客户端公钥后重新走 SN 签名 |

关键反汇编位置：

- `0x0E013982`：比较 `0xFE10`
- `0x0E01398A`：比较 `0xFE20`
- `0x0E013D50`：比较 `0xFE12`
- `0x0E0278FC`：RSA/SHA-256 签名验证函数
- `0x0E01417E`：保存新收到的客户端 RSA 公钥

65040/65056 最终重组为 256 字节签名；65042 最多重组约 508 字节 PEM 公钥。固件配置层存在 `tnt_config_load_user_rsa_pub_key` 和相应保存/清除逻辑。

### SN 签名的精确格式

固件中嵌入了 PLAUD 验签公钥：

- 类型：RSA 2048 bit
- SubjectPublicKeyInfo DER SHA-256：`0236fec56232de865f362217e7a5c5d06ec826c7751de6e92a9c0eb6ace5919a`
- 文件：`analysis/extracted/plaud-signature-verification-public-key.pem`

使用当前 Windows DPAPI 中缓存的设备材料做了只在内存中运行的验证：

- 签名长度：256 字节
- 算法：RSA PKCS#1 v1.5 + SHA-256
- 唯一验证成功的候选消息：原始 SN 字符串
- `type + SN`、JSON、带分隔符组合均未通过

这说明 `type` 只用于 PLAUD 服务端选择产品逻辑或签名密钥，设备实际验证的内容就是自己的 SN。

公钥可以验证签名但不能生成新签名；伪造仍然需要 PLAUD 持有的私钥。

### 会话密钥交换

官方 SDK 与固件组合起来可还原完整流程：

1. 客户端把 base64 解码后的 SN 签名以每包最多 100 字节发送到 65040；需要更换客户端身份时使用 65056。
2. 设备使用固件内置的 PLAUD 公钥验证 `SHA256(SN)` 的 RSA 签名。
3. 如果设备没有保存客户端 RSA 公钥，它用 65041 请求客户端继续发送公钥。
4. 客户端通过 65042 分包发送 PEM RSA 公钥；设备保存该公钥。
5. 设备生成本次连接的 32 字节 ChaCha key、12 字节 nonce 和 12 字节 AAD。
6. 设备把这 56 字节会话材料和一个经 ChaCha20-Poly1305 加密的 `PLAUD.AI` 探针一起用客户端 RSA 公钥加密，再通过 65042 回传。
7. 客户端用 RSA 私钥解包，并用得到的 ChaCha 参数解出 `PLAUD.AI`，由此确认双方持有同一会话材料。
8. 后续 CMD 1 bind-token 握手、文件列表、下载和控制命令都进入 ChaCha20-Poly1305 加密通道。

每次 BLE 重连都会生成新的 ChaCha 会话参数；长期保存的是 SN 签名、客户端 RSA 身份和 bind token。

## JWT 到底是否会过期

User JWT 自身会按 JWT 的 `exp` 到期，但它只用于调用 `/developer/api/open/partner/sdk/sn-sign`、生成密钥或其他 Partner API。BLE 设备没有解析 JWT，也没有从网络查询它的状态。

当前 SN 签名只覆盖 SN，没有 `iat`、`exp` 或时间戳，所以它不能自行过期。PLAUD 后端即使撤销旧 JWT，也无法让已经离线保存的 NotePin S 立即知道；要撤销现有 SN 签名，PLAUD 必须通过新固件轮换设备内置公钥、增加拒绝规则，或让用户清除设备状态。

## 与官方 SDK 的差异

官方 SDK 把以下工作封装在 AAR 和原生库中：

- 使用 JWT 获取 SN 签名和 RSA 材料
- 发送 65040/65042/65056
- 解包 RSA 会话材料
- ChaCha20-Poly1305 封包、序列号和重放控制
- CMD 1 绑定、文件列表、传输、音频解密与导出

电脑客户端使用同一套设备端协议，但把已经取得的长期材料保存在 Windows DPAPI 中，并用 Python 实现 BLE、RSA、ChaCha 和文件转换。设备看到的是一个满足官方协议的客户端，不知道它运行在 Android 还是 Windows。

## 其他发现

- 固件保留了较完整的函数日志和源码路径，例如 `plaud-pi-fw/RTL8711/project/recorder`，大幅降低了静态分析难度。
- 录音侧存在 Opus 编码、`plaud encrypt data` 和 `PLAUD.AI` 容器逻辑。
- 设备配置存在加密保存逻辑，日志显示其密钥派生依赖 SN/PSN；具体 KDF 仍需继续还原。
- host 固件会把组合 OTA 拆成 host/slave 两部分，再触发 slave BLE DFU。这与包头的两个长度完全吻合。
- slave 固件包含 Apple Find My 的 FMNA 状态机、令牌存储和密钥轮换代码。

## 可继续利用的设备能力

固件和 SDK 命令表组合后，还确认了下列能力。它们都在认证后的 ChaCha 加密通道内，适合逐步加入电脑客户端：

| 方向 | 命令 | 价值 |
|---|---|---|
| 设备状态 | 3、6、9、141 | 当前录音场景、session、电量、容量、隐私、U-Disk、按键和其他状态 |
| 录音控制 | 20–23、35、38 | 开始、暂停、恢复、停止、实时标记和录音标签 |
| 文件同步 | 26、28–31、112–117 | 文件列表、区间下载、停止、删除、头尾 CRC、空包和断点续传 |
| Wi-Fi 快传 | 10、13–18 | 开关 Wi-Fi、配置热点、WebSocket 握手和高速文件传输 |
| 空闲自动同步 | 120–126 | 保存 Wi-Fi、测试网络并在设备空闲时自行同步 |
| 设备设置 | 8、24、25、103、107–109、139、140 | 通用设置、录音灯、隐私模式、BLE 名称和通用参数 |
| 设备日志 | 142–146 | 获取日志列表、下载、停止和删除日志，适合诊断断连或升级失败 |
| 固件升级 | 50–54、151 | host/slave 版本、分包推送、校验、安装结果和 OTA 文件信息 |
| Find My | 128 及内部 token 命令 | Find My 状态复位、token 存储和密钥轮换；不应在正常同步中调用 |

文件传输不是简单的连续字节流。固件明确存在 file-head、file-tail、区间范围、CRC 结果、扩展 CRC 和数据结束空包；这解释了早期电脑客户端下载完整数据后仍收到“空状态包”的现象。电脑客户端应把 tail/empty 作为传输状态，而不是音频数据。

Wi-Fi 快传仍由 BLE 建立信任：固件具有 WebSocket token + stamp 握手，并能复用 BLE 阶段生成的 ChaCha key、nonce 和 AAD。由此可以实现“BLE 认证和下发网络参数，Wi-Fi 传大文件”，无需依赖手机，但还需要动态验证 WebSocket 帧格式和地址协商。

固件的电源管理日志表明，BLE 连接、文件传输、FOTA、Wi-Fi 和录音会分别阻止休眠。长期服务不应假设 GATT 永久在线；更合适的模型是监听广播、按需连接、同步完释放，设备再次出现时自动恢复。

固件还包含工厂测试入口，如写 BLE/Wi-Fi MAC、`AT+FTEST`、token 设置和恢复出厂。这些入口对协议理解有帮助，但可能改变不可逆设备状态，不纳入日常电脑客户端。

## 已生成的分析产物

- `analyze_firmware.py`：解析 PLAUD/Realtek 容器并提取各镜像
- `find_xrefs.py`：寻找固件字符串的 ARM Thumb literal xref
- `scan_thumb.py`：按地址反汇编或检索立即数
- `verify_cached_signature.py`：不输出凭据，只验证缓存签名的消息格式
- `analysis/firmware-layout.json`：结构化固件布局
- `analysis/interesting-strings.txt`：安全、BLE、录音、FOTA 相关字符串
- `analysis/auth-xrefs.json`：认证字符串到代码位置的交叉引用
- `analysis/extracted/`：host、slave、KM0/KM4 分段和 PLAUD 验签公钥

## 后续最有价值的工作

1. 完整标注 `0x0E0138EA` 附近命令分发表，把所有 BLE 命令映射到处理函数。
2. 还原录音文件头、RSA 包装音频密钥和分帧 CRC，减少对 SDK 行为的依赖。
3. 识别 slave 的具体 MCU 和镜像格式，确认 BLE 广播、Find My 与 host SPI 协议的职责边界。
4. 在不清除当前绑定的前提下做断网、重启、休眠和多日重连实验，验证工程层面的长期稳定性。
5. 把电脑客户端做成后台服务：自动扫描、会话重建、下载、解密、转录和网页管理。

## 参考

- Realtek AmebaD 官方源码：https://github.com/Ameba-AIoT/ameba-rtos-d
- Realtek Ameba 官方平台定义：https://github.com/Ameba-AIoT/platform-realtek-ameba
- Realtek Matter OTA 格式说明：https://github.com/Ameba-AIoT/ameba-rtos-matter/blob/main/tools/ota/README.md
- PLAUD 官方 SDK：https://github.com/Plaud-AI/plaud-sdk-public
