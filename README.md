# LCU 图书馆自动选座（Android）

聊城大学图书馆座位预约的原生 Android 客户端。协议层与同目录下的 Python 项目
`lcu-library-seat` 完全一致，本仓库把它搬到手机上，变成常驻后台的自动选座 App。

## 直接安装

安装包放在 **Releases** 里（不进仓库历史，仓库只有源码）：

- [v1.0.11 (debug)](https://github.com/Suzuran7/lcu-seat-android/releases/tag/v1.0.11) —
  `lcu-seat-1.0.11-debug.apk`，54 MB，含 ML Kit 离线识别模型

```bash
adb install -r lcu-seat-1.0.11-debug.apk
```

也可以把 APK 传到手机后直接点开安装（需允许「安装未知来源应用」）。仅支持 Android 8.0 及以上。

## 这个 App 解决什么

| 需求 | 实现 |
| --- | --- |
| 手机 CPU 保持唤醒 | 前台服务 + `PARTIAL_WAKE_LOCK` + 高性能 Wi-Fi 锁；「守护模式」长期持有，任务执行期间临时持有 |
| 不同账户分别定时预约 / 取消 | 每个账号独立登录会话与任务；任务可绑定任意账号，动作可选「预约座位」或「取消预约」 |
| 定时任务选座时像网页一样显示可预约座位 | 按 `layoutByDate` 返回的 `rows × cols` 网格还原真实座位图，颜色区分空闲/使用中/暂离/停用，点座位即时校验所选时段 |
| 凌晨放座抢座 | `AlarmManager.setExactAndAllowWhileIdle` 精确定时唤醒 → 前台服务执行 → 失败按间隔重试到时间窗口结束 |

另外还提供：座位图点选手动预约（验证码自动识别失败时的兜底）、多账号预约记录查询、
一键取消进行中预约、逐次执行日志。

## 构建与安装

需要 JDK 17+ 与 Android Studio（Koala 及以上）。命令行构建：

```bash
# 本机已验证的组合：JDK 21 + Gradle 8.7 + AGP 8.5.2 + compileSdk 34
export JAVA_HOME=/path/to/jdk-17-or-21
gradle assembleDebug
# 或在 Android Studio 中打开本目录，等待 Gradle Sync 完成后 Run
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 54 MB，含 ML Kit 离线中文识别模型）。
本机实测 `BUILD SUCCESSFUL`，Kotlin 0 警告、`lintDebug` 0 错误；安装：`adb install -r app-debug.apk`。

本机已就绪的工具链（如需重跑，直接用下面的命令）：

```bash
export JAVA_HOME="/d/IntelliJ IDEA 2024.3.1.1/jbr"   # JBR 21，AGP 8.5 要求 17+
SDK="$HOME/.workbuddy/android-build/sdk"             # platform-34 / build-tools 34.0.0 / platform-tools
GRADLE="$HOME/.workbuddy/android-build/gradle-8.7/bin/gradle"
cd "C:/Users/ZFL/Desktop/个人资料/lcu-seat-android"
"$GRADLE" assembleDebug          # 打包
"$GRADLE" :app:lintDebug         # 静态检查（报告在 app/build/reports/）
```

> 仓库里只包含 `gradle/wrapper/gradle-wrapper.properties`，没有 `gradle-wrapper.jar`。
> Android Studio 首次打开若提示缺少 wrapper，选择用本机 Gradle 或执行
> `gradle wrapper --gradle-version 8.7` 生成即可。

三个与构建环境相关的设置，改动原因如下，换机器时可以按需调整：

| 设置 | 位置 | 原因 |
| --- | --- | --- |
| `android.overridePathCheck=true` | `gradle.properties` | 工程放在含中文的目录（如「个人资料」）时，AGP 默认直接报错停止 |
| 阿里云 Maven 镜像 | `settings.gradle.kts` | 部分网络下 `maven.google.com` 不可直连，镜像排在 `google()` 之前 |
| `sdk.dir` | `local.properties`（不入库） | 指向本机 Android SDK，Android Studio 会自动生成 |

## 首次使用

1. **添加账号**：填入学号与密码。密码存在 `EncryptedSharedPreferences`（Android
   Keystore 加密）里，只保存在本机。
2. **登录测试**：会拉一张登录验证码并用端侧 OCR 识别后登录，成功后会话存本地，
   后续定时任务直接复用，不必反复登录。若识别失败，多点几次即可换图重试。
3. **新建定时任务**：选账号、动作、触发时间（如 `07:00`）、执行日、房间、
   优先座位、预约时段、重试间隔与最长重试时间。
4. **座位图**：先「加载房间列表」，再选楼栋房间 → 显示座位网格；点任意座位会
   实时校验该时段是否可约（`startTimesForSeat` + `endTimesForSeat`）。
   在任务编辑页点「去座位图挑选座位」可把点选的座位写回任务优先级列表。
5. **记录页**：开启「守护模式」，并允许精确闹钟、忽略电池优化、通知权限。

## 后台与唤醒是怎么做的

| 机制 | 位置 | 说明 |
| --- | --- | --- |
| 精确定时 | `schedule/AlarmScheduler.kt` | `RTC_WAKEUP` + `setExactAndAllowWhileIdle`；未授权时降级为非精确闹钟 |
| 闹钟自续期 | `service/TaskService.kt` | 任务执行结束即按周规则排下一次，一条闹钟对应一个任务 |
| 前台服务 | `service/TaskService.kt` | `foregroundServiceType=dataSync`，执行期间常驻通知显示进度 |
| CPU 唤醒锁 | `service/WakeLocks.kt` | 任务锁带超时自动释放；守护锁常驻；另加高性能 Wi-Fi 锁 |
| 开机恢复 | `schedule/BootReceiver.kt` | 重启后重排全部闹钟，守护模式自动恢复 |
| 免休眠引导 | `ui/LogsScreen.kt` | 直接跳转「精确闹钟」「忽略电池优化」「通知」三个系统设置页 |

> 守护模式会持续阻止 CPU 休眠，耗电明显，建议插电时使用。关掉守护模式后，
> 依赖精确闹钟仍可正常触发，只是部分厂商 ROM 的深度休眠策略可能延迟唤醒。

## 目录结构

```
app/src/main/java/cn/edu/lcu/libseat/
├── SeatApp.kt                  Application：初始化存储、通知渠道、重排闹钟
├── core/
│   ├── Protocol.kt             AES-CBC 凭据加密、HMAC-SHA256 请求签名、坐标编码
│   ├── SeatApi.kt             HTTP 接口封装与响应判定
│   ├── SeatOps.kt             登录、选座、验证码、提交、取消
│   ├── CaptchaSolver.kt       ML Kit 端侧 OCR：登录文本码 + 点选验证码定位
│   ├── Models.kt / Store.kt   数据模型与本地存储
│   └── TimeKit.kt             北京时间与下次触发时间计算
├── service/
│   ├── TaskService.kt         前台服务：执行任务 / 守护模式
│   ├── TaskRunner.kt          单个任务的完整流程与重试
│   ├── WakeLocks.kt           CPU / Wi-Fi 锁
│   └── Notifier.kt            通知渠道与结果通知
├── schedule/
│   ├── AlarmScheduler.kt      精确定时
│   └── BootReceiver.kt        开机恢复
└── ui/                        Compose 界面：账号 / 任务 / 座位图 / 记录
```

## 与 Python 版的行为对齐

| 环节 | 对齐点 |
| --- | --- |
| 签名 | `seat::{request_id}::{timestamp}::{METHOD}`，每次请求重新生成 UUID 与毫秒时间 |
| 凭据加密 | AES-128-CBC/PKCS7，密钥 `server_date_time`、向量 `client_date_time`，Base64 后加 `_encrypt` |
| 验证码接口 | 与 Python 的 `raw=True` 一致：登录验证码不校验 `status`（该接口没有该字段） |
| token 参数 | 只在调用方未给 `token` 时补会话 token（等价 `setdefault`），避免验证码校验出现同名参数 |
| 提交前复查 | 验证码流程耗时后会再查一次预约记录和座位时段，避免过期提交 |
| 结果不确定 | 提交遇到中断/非 2xx/非 JSON 时**不重复提交**，改为查预约记录确认 |
| 已有预约 | 存在匹配预约→视为已预约；存在其他进行中预约→停止提交 |
| 取消 | 只撤销 `RESERVE/CHECK_IN/IN_USE/AWAY`，状态未知的记录只报告 |
| 循环重试 | 会话失效自动重登一次；座位不可用、请求失败按间隔重试至时间窗口结束 |

接口与字段的完整规格见 [`docs/protocol.md`](docs/protocol.md)。

## 验证码识别策略

两种验证码用不同的管线（`core/CaptchaSolver.kt`），都完全在设备端离线完成。

**登录验证码（4 位字母/数字）**

1. 对同一张图生成多组预处理：原图、放大 2×/3×、灰度增强、二值化、红/蓝通道分离、锐化、中值滤波、反相。
2. 每组预处理分别送 **拉丁模型**（对字母数字更准）识别，收集所有「恰好 4 位」的结果。
3. 出现两张一致的结果就提前停止；否则用 **中文模型** 兜底再跑一轮（连笔字有时只有中文模型读得出）。
4. 结果用「整串投票 → 位置投票」定稿，避免单次识别的随机错误。

**点选验证码（提示 N 个字，在照片中依次点出）**

1. 提示图：读成 N 个字符，元素级识别优先，整图识别兜底。
2. 背景图：多组预处理各跑一遍，收集所有单字框（含整行只有一个字的情况），换算回原图坐标后
   **合并去重**（同字同位置只留一次、同位置不同字只留优先级最高的），并过滤整图级大框与噪点小框。
3. 按提示顺序匹配：唯一命中直接用；同一轮出现多个命中视为歧义，**放弃本次换新图**；
   跨轮次多命中时取优先级最高的；精确匹配失败时用形近字表（未/末、己/已、日/曰…）兜底，
   且必须唯一且明显领先。最后校验两个点不重叠。
4. 服务器不接受则换新图重试，最多 5 次；仍失败就推「需要人工处理」，此时可在座位图中手动点选。

识别过程会写进执行记录：登录失败时记录页能看到「登录码[放大2倍] → ab3d」这类过程；
定时任务失败时结果里会附最近 3 条过程信息，方便判断是 OCR 读错还是协议/网络问题。

## 已知限制

- **User-Agent**：站点对 `baseUrl.js` 按 UA 分流，UA 含 `Android`/`iPhone` 会返回 404。
  App 默认发送 `LCUSeat/1.0 (Kotlin)`，失败时按候选列表（桌面 Chrome、Python 版 UA）重试；
  若哪天又出现「读取站点配置失败：HTTP 404」，先检查这一段是不是需要更新 UA。
- **验证码**：端侧 OCR 不是 100% 成功。登录码靠多轮预处理投票，点选码靠「提示字 → 背景字符位置」
  匹配，歧义时宁可换图也不乱点；自动失败会重试 5 次，仍失败则终止并推「需要人工处理」，
  此时可在座位图里手动点选完成预约。
- **首次登录**：若无已保存会话且 OCR 连续失败，需在 App 内手动点验证码登录一次。
- **精确闹钟**：Android 12+ 需要在系统设置里允许；未允许时退化为非精确闹钟，可能延迟数分钟。
- **厂商 ROM**：MIUI / ColorOS 等需要在系统里额外允许「自启动」「后台弹出界面」，
  否则闹钟被清理后不会执行。

## 使用边界

本项目只用于减少手动操作，请遵守图书馆座位管理规定；长时间预约后不签到、
恶意占座可能被系统记为违约。请勿将账号密码分享或提交到任何在线服务。
