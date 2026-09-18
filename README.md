# T100 伴随助手

安装在 DJI 遥控器上的伴随 App，与 DJI SmartFarm（`com.dji.agflow`）和 DJI Agras（`com.dji.agrasx`）配合使用。App 通过无障碍服务执行经过校验的页面导航，通过前台服务从 `companion-server` 接收命令并回传结果。本项目不是 DJI 飞控 SDK，不直接实现飞行控制，最终状态始终以官方 App 的安全条件为准。

## 功能

- 启动 SmartFarm / Agras，读取当前页面摘要；
- 按文字、资源 ID、比例坐标点击，等待页面和返回；
- 接收 `.tif + .tfw` 处方图文件对，校验 SHA-256 并保存到 SD 卡 `DJI/RX/`；
- 扫描 Agras“作业 / 进行中作业 / 处方图-本地”清单并上报网页；
- 按名称准备 Agras 作业：点击作业行右侧箭头，选择处方图并确认，然后按官方页面继续点击“调用”和“执行”；
- 上报前台服务、无障碍授权/连接、Agras 安装和 SD 卡状态。

## 首次配置

1. 安装本 App、官方 Agras（或 SmartFarm）和 `companion-server`。
2. 在本 App 填写服务器地址、设备编号和 Bearer Token，点击“保存配置”。
3. 只有配置完整后，才能打开“接口命令接收”；未配置时不会轮询命令。
4. 首次打开无障碍开关时授权系统服务；返回本 App 后确认状态为“服务已连接”。
5. Android 11 及以上如需写入外置 SD 卡，还要开启“SD 卡文件访问”。

服务器地址使用 `https://`。设备编号必须与网页控制端的 `deviceId` 完全一致。

## 命令协议

### 接收后的点击链路

前台服务每隔几秒调用 `/api/device/commands/next`。收到命令后，`executeAndAck()` 根据 `type` 分发；页面点击全部通过无障碍服务完成，并在动作结束后调用 `/api/device/commands/{id}/ack` 回传成功或失败原因。

`PREPARE_AGRAS_JOB` 的内部顺序是：按 `itemNameTv` 找到作业 → 在同一行查找并点击 `gotoIv` 右箭头 → 点击地图页 `selectPrecisionRes` → 按 `precisionName` 选择处方图 → 点击 `确定(R3)`（资源 ID `textConfirm` 作为兜底）→ 如页面存在则点击“调用”→ 等待 `btnAction` 的“执行”并点击。任一步骤找不到控件、页面包名不匹配或控件被官方置灰，都会停止并回传错误，不会盲目点击坐标。

设备轮询：

```http
GET /api/device/commands/next?deviceId=test
Authorization: Bearer <device-token>
```

无命令返回 `204`，有命令时返回：

```json
{"id":"cmd-001","type":"CLICK_TEXT","payload":{"app":"agras","text":"处方图"}}
```

支持：`OPEN_DJI`、`OPEN_AGRAS`、`OPEN_APP`、`OPEN_DEEPLINK`、`INSPECT_PAGE`、`CLICK_TEXT`、`CLICK_ID`、`CLICK_RATIO`、`WAIT_PAGE`、`BACK`、`DOWNLOAD_PRESCRIPTION`、`IMPORT_PRESCRIPTION`、`READ_AGRAS_INVENTORY`、`PREPARE_AGRAS_JOB`。

`payload.app` 可为 `agras`、`smartfarm` 或完整包名，省略时默认为 Agras。比例坐标必须在 `0..1` 且提供 `description`。

执行回执：

```http
POST /api/device/commands/cmd-001/ack
Authorization: Bearer <device-token>
Content-Type: application/json

{"success":true,"message":"已点击：处方图"}
```

普通命令和服务端会拦截锁机、解锁、起飞、开始任务、返航、降落、喷洒、播撒、删除等危险关键词；坐标点击还会检查命中节点元数据。作业命令仍受官方 App 的连接、账号、航线重合和安全检查限制。

## 处方图

网页端必须同时上传基础名称一致的一对文件，例如 `result_Green1.tif` 和 `result_Green1.tfw`。单个文件最大 1 GiB，服务端分别计算 SHA-256。

有可写 SD 卡时保存到 `DJI/RX/`；没有可写 SD 卡时只保存到 `Download/DJI-Prescriptions/`，并拒绝执行导入。导入流程进入 Agras“处方图 → 内存卡”，选择文件，默认选择“平均值”并点击“确定”。

## 作业流程

1. 网页点击“从遥控器同步列表”。
2. 从真正的下拉框选择设备、作业和已导入处方图。
3. 可选：点击“保存作业与处方图关联”。
4. 点击“准备作业”。
5. App 在“作业 → 本地”按名称定位记录，点击该行右侧 `gotoIv` 箭头进入地图。
6. 打开处方图面板，精确选择处方图并确认。
7. 继续点击官方页面右下角“调用”和“执行”。

如果官方按钮置灰，常见原因是飞行器未连接、账号未加入农服团队或安全检查未通过；App 不绕过这些限制。

## 构建与安装

使用 Android Studio 打开 `F:\dj\dji-companion`，安装 Android SDK 35，构建 `app` 模块的 Debug 变体。APK 通常位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装后可检查：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell dumpsys package com.example.djicompanion
adb shell dumpsys accessibility
adb shell dumpsys activity services com.example.djicompanion
```

## 常见问题

- **无障碍显示未连接**：系统授权不等于服务实例运行；返回本 App，确认“服务已连接”，必要时关闭再打开开关。
- **清单为空**：确认安装了包含新版 Agras 资源 ID 的 APK，并在文件中心等待列表加载。
- **作业找不到箭头**：作业必须存在于最近同步的“作业-本地”清单；流程点击的是该行右侧箭头。
- **执行按钮置灰**：这是官方 App 状态限制，通常与飞行器连接、账号或安全检查有关。
- **命令不接收**：检查服务器地址、设备编号、Bearer Token 和“接口命令接收”开关；修改服务端配置后需重启 Node 服务。

## 目录约定

- `app/src/main/java/`：Android 主界面、无障碍服务和命令轮询服务；
- `app/src/main/res/xml/accessibility_service_config.xml`：无障碍服务配置；
- `local.properties`：本机 SDK 路径，不提交到版本库；
- `agras7`：官方 App 分析资料，只读，不能修改。
