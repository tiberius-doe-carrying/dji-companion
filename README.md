# T100 伴随助手

这是一个安装在 DJI SmartFarm / DJI Agras 同一 Android 设备上的伴随 App 原型。

当前能力：

- 检查并启动 DJI SmartFarm（`com.dji.agflow`）和 DJI Agras（`com.dji.agrasx`）；
- 测试 `agworkflow://` Deep Link；
- 通过用户主动授权的无障碍服务读取当前页面控件；
- 对两个官方 App 执行页面检查、按文字/资源 ID 点击、比例坐标点击、等待页面和返回；
- 硬编码拦截锁机、解锁、起飞、任务执行、返航、降落、喷洒、播撒等动作。
- 可配置成熟系统的 HTTPS 地址、设备编号和 Bearer Token；
- 前台服务轮询 `/api/device/commands/next`，执行后向 `/ack` 回传结果。

## 命令协议

取下一条命令：

```http
GET /api/device/commands/next?deviceId=rc-t100-001
Authorization: Bearer <device-token>
```

无命令返回 `204`；有命令返回：

```json
{"id":"cmd-001","type":"CLICK_TEXT","payload":{"text":"植保机"}}
```

当前支持 `OPEN_DJI`（兼容命令，默认 SmartFarm）、`OPEN_AGRAS`、`OPEN_APP`、`OPEN_DEEPLINK`、
`INSPECT_PAGE`、`CLICK_TEXT`、`CLICK_ID`、`CLICK_RATIO`、`WAIT_PAGE`、`BACK`。

除 `OPEN_AGRAS` 外，命令可在 `payload.app` 指定 `smartfarm`、`agras` 或对应包名；省略时兼容旧行为，默认 SmartFarm。例如：

```json
{"type":"CLICK_ID","payload":{"app":"agras","resourceId":"com.dji.agrasx:id/example"}}
{"type":"CLICK_RATIO","payload":{"app":"agras","x":0.5,"y":0.8,"description":"打开安全导航页"}}
{"type":"WAIT_PAGE","payload":{"app":"agras","text":"首页","timeoutMs":10000}}
{"type":"BACK","payload":{"app":"agras"}}
```

比例坐标范围为 `0..1`，且必须提供 `description`。服务端会检查全部 payload，App 会再次检查命令参数以及坐标命中控件的文字、描述和资源 ID；任一层识别到锁机、解锁、起飞、任务执行、返航、降落、喷洒、播撒等关键词都会拒绝操作。

执行回执：

```http
POST /api/device/commands/cmd-001/ack
Authorization: Bearer <device-token>
Content-Type: application/json

{"success":true,"message":"已点击：植保机"}
```

## 构建

用 Android Studio 打开本目录，安装 Android SDK 35，然后构建 `app`。

命令行环境具备 Android SDK 和 Gradle Wrapper 后可运行：

```powershell
.\gradlew.bat assembleDebug
```

输出通常位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 安装官方 DJI SmartFarm 和/或 DJI Agras，以及本 App；
2. 打开本 App，进入系统无障碍设置；
3. 开启“T100 伴随助手”；
4. 启动 DJI SmartFarm 并登录；
5. 返回本 App读取页面，确认控件的 text、content-desc 或 resource-id；
6. 根据真机页面结果扩展安全导航状态机。

本原型不是飞控 SDK，不适合实时控制或无人值守飞行。
