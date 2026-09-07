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
- 前台服务向 `/api/device/status` 上报设备心跳、无障碍启用状态和 DJI Agras 安装状态，供网页控制台显示在线情况。
- 无障碍状态区分系统“已授权”和服务实例“已连接运行”，避免授权存在但服务崩溃时误判。
- `READ_AGRAS_INVENTORY` 会只读扫描 Agras 文件中心的“作业”“进行中作业”和“处方图-本地”列表，并上报服务端；不会开始任务或起飞。
- `PREPARE_AGRAS_JOB` 会按名称定位指定本地作业并点击该行右侧进入箭头，在地图页选择指定的已导入处方图并确认，随后点击官方页面右下角“调用”和“执行”；官方自身的飞行器连接、账号权限及安全确认仍会继续生效。

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

当前支持 `OPEN_DJI`（启动当前遥控器的 Agras）、`OPEN_AGRAS`、`OPEN_APP`、`OPEN_DEEPLINK`、
`INSPECT_PAGE`、`CLICK_TEXT`、`CLICK_ID`、`CLICK_RATIO`、`WAIT_PAGE`、`BACK`。

命令可在 `payload.app` 指定 `agras`、`smartfarm` 或对应包名；省略时默认当前遥控器的 Agras。例如：

```json
{"type":"CLICK_ID","payload":{"app":"agras","resourceId":"com.dji.agrasx:id/example"}}
{"type":"CLICK_RATIO","payload":{"app":"agras","x":0.5,"y":0.8,"description":"打开安全导航页"}}
{"type":"WAIT_PAGE","payload":{"app":"agras","text":"首页","timeoutMs":10000}}
{"type":"BACK","payload":{"app":"agras"}}
```

比例坐标范围为 `0..1`，且必须提供 `description`。服务端会检查全部 payload，App 会再次检查命令参数以及坐标命中控件的文字、描述和资源 ID；任一层识别到锁机、解锁、起飞、任务执行、返航、降落、喷洒、播撒等关键词都会拒绝操作。

服务端收到基础名称完全一致的 `.tif + .tfw` 处方图文件对后会下发内部命令 `DOWNLOAD_PRESCRIPTION`。伴随 App 再次校验文件对名称及两份文件各自的 SHA-256。检测到可写的可移除 SD 卡时保存到 SD 卡根目录 `DJI/RX/` 并允许导入；未插 SD 卡时只保存到遥控器内部 `Download/DJI-Prescriptions/`，`IMPORT_PRESCRIPTION` 会明确拒绝。Android 11 及以上需先在伴随 App 中开启“SD 卡文件访问”。导入设置弹窗出现后只选择“平均值”并点击“确定”，不会修改来源和面积单位，也不会进入任务执行阶段。

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
2. 首次使用时在本 App 点击无障碍服务开关，进入系统无障碍设置；后续启动无需重复开启；
3. 开启“T100 伴随助手”；
4. 启动 DJI SmartFarm 并登录；
5. 返回本 App读取页面，确认控件的 text、content-desc 或 resource-id；
6. 根据真机页面结果扩展安全导航状态机。

必须完整填写服务器地址、设备编号和设备访问令牌，并点击“保存配置”后，才能开启“接口命令接收”。未完成配置时，前台命令服务不会启动。

本原型不是飞控 SDK，不适合实时控制或无人值守飞行。
