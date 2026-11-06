# 协议规格：本 App 与 lib-seat.lcu.edu.cn 的交互

本项目的协议事实来自同目录 Python 项目 `lcu-library-seat`（其 `docs/protocol.md` 与
`lib-seat.lcu.edu.cn.har`）以及学校公开前端脚本。App 只读取公开脚本作为协议依据，
从不执行服务器下发的 JavaScript。

基准地址：`https://lib-seat.lcu.edu.cn:8443`。

## 加密与签名

| 项目 | 规格 | 实现 |
| --- | --- | --- |
| AES 密钥 / 向量 | `server_date_time` / `client_date_time`，各 16 字节 | `Protocol.kt` |
| 凭据加密 | AES-CBC + PKCS7 后 Base64，追加 `_encrypt` | `encryptCredential()` |
| 学校代码解密 | 用同一组密钥解密 `baseUrl.js` 的 `NUMCODE`，得到 HMAC 密钥 | `decryptSchoolCode()` |
| 请求签名 | `HMAC-SHA256("seat::{request_id}::{timestamp}::{METHOD}")` | `signedHeaders()` |
| 签名请求头 | `X-request-id`、`X-request-date`、`X-hmac-request-key`、`loginType: PC` | 同上 |
| 点击坐标 | 紧凑 JSON（`[{"x":231,"y":81}]`）再 Base64 | `encodePoints()` |

验证码坐标以**背景图显示宽度 330 像素**为基准，App 中按 `330 / 图片实际宽度` 缩放。

## 接口

| 用途 | 方法与路径 | 关键字段 |
| --- | --- | --- |
| 站点配置 | `GET /libseat/static/baseUrl.js` | `CODE`、`NUMCODE`、`CASLOGIN` |

> 注意：`baseUrl.js` 会按 User-Agent 分流，UA 里带 `Android` 或 `iPhone` 时服务器直接返回
> **404**（实测 `LCUSeat/1.0 (Android)`、移动版 Chrome UA 均 404；桌面 UA、`okhttp/4.12.0`、
> 空 UA 均 200），而 `/rest/v2/*` 接口不挑 UA。因此 `SeatApi` 默认使用桌面浏览器 UA，
> 并在 `bootstrap` 里按 `USER_AGENT_CANDIDATES` 依次重试。
| 登录验证码 | `GET /auth/createCaptcha` | `captchaId`、`captchaImage`；答案固定为 **4 位小写字母或数字** |
| 登录 | `GET /rest/auth` | 查询参数 `username`(加密)、`password`(加密)、`captchaId`、`answer`；响应 `data.token` |
| 用户资料 | `GET /rest/v2/user` | `data.id`、`data.username` |
| 预约设置 | `GET /rest/v2/settings` | `data.isCaptchaOpen`（`status` 为布尔 `true`） |
| 开放信息 | `GET /rest/v2/free/filters` | `data.dates`、`data.buildings`、`data.rooms` |
| 房间统计 | `GET /rest/v2/room/stats2/{building}/{date}` | — |
| 房间布局 | `GET /rest/v2/room/layoutByDate/{room}/{date}` | `data.rows`、`data.cols`、`data.layout`（下标 → 单元格） |
| 座位开始时间 | `GET /rest/v2/startTimesForSeat/{seat}/{date}` | `data.startTimes[].id`（`now` 或当日分钟数） |
| 座位结束时间 | `GET /rest/v2/endTimesForSeat/{seat}/{date}/{start}` | `data.endTimes[].id` |
| 我的预约 | `GET /rest/v2/user/reservations` | `data[].id/seatId/onDate/begin/end/status/location` |
| 预约验证码 | `POST /cap/captcha/{token}?username=` | `status`、`token`、`image`、`wordImage`、`wordCheckCount` |
| 验证码校验 | `GET /cap/checkCaptcha` | `a`(坐标)、`token`、`userId`、`username`；`status=OK` 即通过 |
| 提交预约 | `POST /rest/v2/freeBook` | multipart：`startTime`(`-1` 表示现在)、`endTime`、`seat`、`date`、`userId`、`username`、`authid` |
| 取消预约 | `GET /rest/v2/cancel/{id}` | `status=success` 即成功 |

## 布局结构

```json
{
  "id": 23,
  "name": "二楼北侧走廊",
  "rows": 4,
  "cols": 36,
  "layout": {
    "0":    { "type": "empty" },
    "1052": { "type": "desk", "name": null },
    "2048": { "type": "seat", "id": 14616, "name": "042", "status": "IN_USE",
              "window": false, "power": false, "enabled": true, "direction": 2 }
  }
}
```

**`layout` 的键是 `行号 * 1000 + 列号`**，不是连续下标：第 0 行是 `0..cols-1`，第 1 行是
`1000..1000+cols-1`，依此类推。早期 HAR（2026-09-04）里那间房恰好是 `0..rows*cols-1`
连续编号，所以两种规则都要兼容——`SeatApi.parseLayout` 依次尝试
「行*1000+列」→「线性下标」→「JSON 插入顺序」，命中率 >= 90% 即采用。

键值规则踩坑记录：按连续下标解析时，8×57 的房间只会命中第 0 行的 57 格、座位数为 0，
界面表现为「共 0 个座位」的空网格。

## 结果判定

| 判定 | 规则 |
| --- | --- |
| 标准接口 | `status == "success"`；`code == 12` 视为登录失效 |
| 设置接口 | 额外接受布尔 `true` |
| 登录验证码接口 | **没有 `status` 字段**，只有 `captchaId` / `captchaImage`，按原始响应处理（对应 Python 的 `raw=True`） |
| 预约验证码接口 | `status == "OK"`，否则视为未返回完整验证码 |
| 验证码校验 | `status == "OK"` |
| 登录失败 | `code=31` 验证码错误（**不消耗登录次数**）；`code=13` 验证码已通过但账号/密码不正确（**消耗一次「机会」，响应里带剩余次数**） |
| 登录接口的 `code=12` | 属于凭据错误而非会话失效，直接展示服务器原文 |
| HTTP 401/403 | 登录失效，可重新登录后重试 |
| 提交结果不确定 | 中断 / 非 2xx / 非 JSON / 缺预约编号 → **不重复提交**，查预约记录确认 |

## 查询参数约定

`token` 只会补一次：调用方已经给出 `token` 时不再追加会话 token（等价于 Python 的
`params.setdefault("token", token)`）。点选验证码校验 `/cap/checkCaptcha` 的 `token`
是**验证码凭据**，不能与会话 token 混在同一个参数名上。

## 与 Python 实现的对应

| Python | Kotlin |
| --- | --- |
| `lcu_seat/protocol.py` | `core/Protocol.kt` |
| `lcu_seat/client.py` | `core/SeatApi.kt` |
| `lcu_seat/booking.py` | `core/SeatOps.kt` + `service/TaskRunner.kt` |
| `lcu_seat/captcha.py`（ddddocr） | `core/CaptchaSolver.kt`（ML Kit 端侧中文 OCR） |
| `lcu_seat/state.py` | `core/Store.kt`（会话按账号隔离）+ `schedule/BootReceiver.kt` |
| `cli.py` 的 `multi-book` / `multi-cancel` / `--at` | 任务模型 + `AlarmScheduler` + `TaskService` |
