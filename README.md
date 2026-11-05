# HA MiTV

给 Home Assistant 提供控制小米电视能力的 Android 端 Agent。

不是 HA 的仪表盘：电视上不渲染 Lovelace，只跑一个后台服务，把电视能力
（Intent / 广播 / Binder 接口）暴露成 HA 里的设备实体。

## 架构

```
HA ──ESPHome API(:6053)──►  TV Agent  ──Intent/广播/Binder──►  系统与第三方 App
     浏览器 ──HTTP(:8122)──►  配置台
```

- **传输**：ESPHome 原生 API，钉在 2025.12 的 `api.proto`（API 1.13），
  HA 侧零代码，设备直接出现在「设置 → 设备与服务」。
- **配置**：全部通过内嵌 Web 配置台完成，电视只显示地址和 4 位配对码。
- **能力**：`activity` / `service` / `broadcast` 三类命令 + 内置 handler
  （摄像头画中画 AIDL、启动应用）。不依赖 root，不依赖 Shizuku。

## 端口

| 端口 | 用途 |
|---|---|
| 6053 | ESPHome API（HA 连入） |
| 8122 | 配置台 Web（仅内网） |

## 状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 工程骨架 + 协议分帧 + CI | 进行中 |
| M1 | Web 配置台（配对码 + 会话） | 待办 |
| M2 | ESPHome 会话（Hello/DeviceInfo/ListEntities） | 待办 |
| M3 | 命令表 + 实体 + 试跑 | 待办 |
| M4 | Noise PSK + mDNS | 待办 |

## 构建

APK 由 GitHub Actions 构建，推送 `android/hamitv` 分支即触发，
产物在 workflow 的 Artifacts 里。

```bash
./gradlew assembleDebug   # 本地构建（需 Android SDK 34 + JDK 17）
```

## 目录

```
app/src/main/proto/         vendored ESPHome api.proto + api_options.proto
app/src/main/java/.../esp/  分帧与传输层（plaintext 已实现，noise 待补）
app/src/main/java/.../net/  网络工具
```
