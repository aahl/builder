# HA MiTV

给 Home Assistant 提供控制小米电视能力的 Android 端 Agent。

不是 HA 的仪表盘：电视上不渲染 Lovelace，只跑一个后台服务，把电视能力
（Intent / 广播 / Binder 接口）暴露成 HA 里的设备实体。

- 包名 `cn.alone.hamitv`
- 电视端只有一个页面：配置台地址 + 4 位配对码 + 二维码

## 架构

```
HA ──ESPHome API(:6053)──►  TV Agent  ──Intent/广播/Binder──►  系统与第三方 App
浏览器 ──HTTP(:8122)─────►  配置台
```

- **传输**：ESPHome 原生 API，钉在 2025.12 的 `api.proto`（API 1.13），
  HA 侧零代码，设备直接出现在「设置 → 设备与服务」。
- **配置**：全部通过内嵌 Web 配置台完成。
- **能力**：`activity` / `service` / `broadcast` 三类命令 + 内置 handler
  （摄像头画中画 AIDL、启动应用）；命令可以按组包成 `select` 实体。
  不依赖 root，不依赖 Shizuku。

## 端口

| 端口 | 用途 |
|---|---|
| 6053 | ESPHome API（HA 连入） |
| 8122 | 配置台 Web（仅内网，不要映射到公网） |

## 状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 工程骨架 + 协议分帧 + CI | 完成 |
| M1 | Web 配置台（配对码 + 会话 + 命令表编辑） | 完成 |
| M1.5 | 摄像头画中画（Binder）+ 配置台试跑 | 完成 |
| M2 | ESPHome 会话 + 实体 + 命令执行 | 完成 |
| M3 | `select` 实体（输入源） | 完成 |
| M3.5 | `camera` 实体（拉流）+ 配置台试跑接口 | 待办 |
| M4 | mDNS 自动发现 | 完成 |
| M4.5 | Noise PSK 加密 | 待办 |

## ESPHome 协议

明文 TCP 6053，`HelloResponse` 上报 API 1.13（对应 ESPHome 2025.12），
HA 侧零代码即可添加。帧格式：

```
[0x00][body 长度 varint][消息类型 varint][protobuf body]
```

**长度只算 body，不含类型 varint**。这一点容易写错：把类型算进长度后，
`aioesphomeapi` 发来的 HelloRequest 会被截断，握手直接断连。

当前实现的消息：`Hello` / `Authentication` / `DeviceInfo` / `GetTime` / `Ping` /
`Disconnect` / `ListEntities`（button、select、text_sensor）/ `SubscribeStates` /
`ButtonCommandRequest` / `SelectCommandRequest`。

实体由命令表生成，每个命令一个 button，另加 `text_sensor.mitv_last_command`
记录最后一次执行结果；改命令表会重建实体并主动断开连接，HA 重连后重新拉取列表。

## 命令表

`type` 决定执行方式，`extras` 保留 JSON 类型（`23` 是 int，`"23"` 是字符串，
两者在 `Intent.putExtra` 里不等价）：

```json
{ "id": "camera_fullscreen", "name": "摄像头全屏", "type": "activity",
  "action": "com.yicamera.ACTION_CAMERA_PLAYER",
  "component": "com.xiaomi.smarthome.tv/....YiCamerasPlayerActivity",
  "categories": ["android.intent.category.HOME"],
  "extras": { "did": "", "model": "" } }
```

`action` 和 `component` 至少有一个；只有 component 也能拉起页面。

### select

`type: "select"` 的条目本身不执行，它的 `options` 才是命令，每个 option 的
`name` 就是 HA 里可选的值（保持书写顺序，去重，最长 255 字节）：

```json
{ "id": "input_source", "name": "输入源", "type": "select",
  "icon": "mdi:video-input-hdmi",
  "options": [
    { "name": "主页", "type": "activity",
      "action": "android.intent.action.MAIN",
      "categories": ["android.intent.category.HOME"] },
    { "name": "HDMI 1", "type": "activity",
      "component": "com.xiaomi.mitv.tvplayer/.ExternalSourceActivity",
      "extras": { "input": 23 } }
  ] }
```

小米电视的信号源走 `ExternalSourceActivity`，`input` 必须是 **int**：
HDMI1/2/3 = 23/24/25，TV = 1，AV = 2，VGA = 0。

状态只反映「本 App 最后成功切到的值」——用遥控器换台时它并不知道，重启后
为 unknown（`missing_state`）。命令执行失败不会更新状态，错误留在
`text_sensor.mitv_last_command` 里。

## 自动发现

用 Android 的 `NsdManager` 广播 `_esphomelib._tcp.local.`，HA 的 zeroconf 流程
会直接弹出「发现新设备」，不用手输 IP：

```
name  客厅电视._esphomelib._tcp.local.
addr  192.168.26.55:6053
txt   mac=0282bc4f0f95  friendly_name=客厅电视  version=2025.12.0
      platform=Android  board=MiTV-ANSP0  network=ethernet
```

`mac` 是必须的（HA 没有它就 abort），并且要和 `DeviceInfoResponse.mac_address`
一致——两者都由 Android ID 派生。改设备名会重新注册记录；注册失败时配置台
的「自动发现」一栏会显示未广播。

## 摄像头画中画

`com.xiaomi.smarthome.tv` 导出了 `RemoteCameraService`（`exported=true`，无权限要求）。
真正的 AIDL 接口是 `com.xiaomi.smarthome.camera.panel.remote.ICameraBinder`，
不是内部包装类 `ITvCameraApi`；用原始 `IBinder.transact` 调用即可，无需 vendored stub：

| code | 方法 |
|---|---|
| 1 | `getApiLevel()` |
| 4 | `getAllCamera(callback)` → `(String, List<CameraInfo>)`，`CameraInfo = (did, roomName)` |
| 5 | `isExistCameraFloatWindow()` |
| 7 | `setFloatWindowState(visible, move)` |
| 8 | `startFloatWindow(did)` |
| 9 | `closeFloatWindow()` |

配置台里的「摄像头画中画」卡片可以列出账号下的摄像头、打开和关闭画中画；
对应接口是 `GET /api/pip`、`POST /api/pip/start`、`POST /api/pip/close`。
实测（2.8.0.2 / apiLevel 2）可正常拉起与关闭预览窗口。

## 配置台

电视屏幕显示 `http://<ip>:8122` 和一个 4 位配对码（遥控器按 OK 可重新生成）。
浏览器打开后输入配对码，换取 7 天有效的会话 Cookie，之后可编辑：

- 设备名称、配置台端口、ESPHome 端口
- 命令表（JSON，保存前会校验）
- 重置配对码（同时踢掉已配对会话）、重启服务

鉴权说明：4 位码只有 1 万种组合，因此配对接口按 IP 限流，5 次失败锁 60 秒并
指数退避。**不要把 8122 暴露到公网。**

## 构建

APK 由 GitHub Actions 构建，推送 `android/hamitv` 分支即触发，
产物在 workflow 的 Artifacts 里。CI 使用固定 debug keystore
（secret `HAMITV_DEBUG_KEYSTORE_BASE64`），保证新包可以直接覆盖安装。

```bash
./gradlew assembleDebug   # 本地构建（需 Android SDK 34 + JDK 17）
```

## 目录

```
app/src/main/proto/          api.proto（由 tools/sync_proto.py 从 ESPHome 生成）
app/src/main/java/.../esp/   分帧、消息 id、传输层（plaintext 已实现，noise 待补）
app/src/main/java/.../web/   内嵌配置台（Ktor CIO）+ 配对限流
app/src/main/java/.../config/ 设置与命令表存储
app/src/main/java/.../tv/     小米摄像头 Binder 客户端（画中画）
app/src/main/assets/web/     单页配置界面（无构建）
tools/sync_proto.py          重新同步 ESPHome proto
```

## 关于 proto

ESPHome 的 `api.proto` 依赖 `api_options.proto` → `google/protobuf/descriptor.proto`，
而 Maven 的 protoc 不带 well-known types，直接编译会失败。因此
`tools/sync_proto.py` 会剥掉 service 块与全部自定义 option，只保留消息定义，
并把 `(id)` 提取成 `EspMessageId.kt`。字段编号和类型保持原样，线格式不受影响。

重新同步：

```bash
python3 tools/sync_proto.py /path/to/esphome/components/api/api.proto
```
