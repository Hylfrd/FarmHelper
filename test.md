# FarmHelper P0 人工验收计划

## 状态

本文是**未执行的人工验收计划**。除“已获得的启动证据”外，下列 GUI 手测项目均未执行，所有复选框均保持未勾选。本文不构成验收通过证明，也不得据此解锁后续阶段。

## 固定测试对象

- Git commit：`2f59cfef889974017e570bf4342b1ffa59a745c3`
- Git tree：`06f572ae6386ef21b2fd5180a9ad51dd09e35c08`
- Minecraft：`26.1.2`
- Fabric Loader：`0.19.3`
- FarmHelper：`0.1.0`
- Java：BellSoft Liberica JDK `25.0.3+11-LTS`（JDK 25）
- runtime JAR：`build/libs/farmhelper-0.1.0.jar`
- runtime JAR 大小：`389,317` bytes
- runtime JAR SHA-256：`93F9B129A1FF33DF6122688ADA86A0889DAF60410FBEAE861EC8DD65495FF0E2`

执行任何未来人工验收前，必须重新确认工作树 clean detached、HEAD/tree 与上述值完全一致，physical/local/origin/live master 也仍指向上述 commit；不得用其他构建产物或提交替代。

## 已获得的启动证据

以下事实来自已经实际发生、随后停止的 `dcc6` 会话；它们只证明客户端启动和安全清理，不证明下方人工矩阵已经执行。

- 实际命令：在 exact clean detached worktree `dcc6` 中执行 `.\gradlew.bat --offline --no-daemon --console=plain runClient`。这不是 dry-run，没有 `clean`、`jar`、`build` 或其他启动前置任务。
- 实际窗口：`:runClient` 创建了真实的 `Minecraft* 26.1.2` 窗口；因首帧捕获失败，没有取得可用截图或可访问性树。
- 初始化日志确认：Minecraft `26.1.2`、Fabric Loader `0.19.3`、FarmHelper `0.1.0`，并出现 `FarmHelper core initialized.`、`FarmHelper client commands registered.`、`FarmHelper client initialized.`。
- `dcc6/run/logs/latest.log`：SHA-256 `5AA190F79FECF082592F3F6DACA8CDFF09E615789A4D3587BF6AC578878B390E`。
- `dcc6/run/logs/debug.log`：SHA-256 `25BAFEA61E816E0F90D366E06B86609EA89F9543860E2F266CB4DA4B91213165`。
- crash report 数量为 `0`；日志中没有 FarmHelper crash、FarmHelper/Mixin 异常或 FarmHelper 远程活动命中。Vanilla 的离线认证提示不应误记为 FarmHelper 远程活动。
- 因无法安全进行可视化退出，只终止了 exact client PID `98380`。随后 exact shell/wrapper/daemon/client PIDs `97000/85652/81708/98380` 全部退出。
- 清理后匹配 Minecraft、runClient、launcher 和本次 tester Gradle 的进程数均为 `0`。会话开始前已存在且与本次 lease 无关的 Gradle daemon 不在清理范围内。

## 未执行原因

Computer Use 在第一次强制非盲窗口捕获时返回：

```text
SetIsBorderRequired failed: 不支持此接口 (0x80004002)
```

该调用没有返回窗口 state、screenshot 或 accessibility tree。继续点击或输入将成为盲操作，因此 tester 立即停止了全部桌面输入。独立 capture-recovery 审查也确认受控客户端不存在符合 skill 约束的安全非盲恢复路径。按 Apostrophe 的明确要求，不再启动或重试 Minecraft，只创建本计划并停止 GUI 工作。因此，单人世界、命令、设置、旋转、控制释放、重载和正常退出等项目均没有实际结果。

## 未执行的人工验收矩阵

以下每项的“实际结果”和“证据”目前都必须保留为未执行。未来只能由能够稳定观察窗口的人类执行者填写。

- [ ] 1. 核对版本与加载身份
  - 操作：在客户端主界面和 Mod 信息中核对 Minecraft、Fabric Loader 与 FarmHelper 版本，并与本页固定测试对象比对。
  - 预期结果：分别显示 Minecraft `26.1.2`、Fabric Loader `0.19.3`、FarmHelper `0.1.0`；没有重复加载或错误版本。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写截图路径、时间戳和日志行。

- [ ] 2. 创建并进入单人世界
  - 操作：从 Singleplayer 创建一个新的本地测试世界并进入；等待世界和玩家完全可用。
  - 预期结果：世界正常加载，玩家可移动；FarmHelper 不崩溃、不产生初始化异常，也不自行连接任何 FarmHelper 远程服务。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写世界画面与日志。

- [ ] 3. 验证 `/farmhelper status`
  - 操作：在单人世界聊天框执行 `/farmhelper status`。
  - 预期结果：客户端命令被识别；聊天中以 `[FarmHelper]` 输出 enabled/state、macro/ticks、world/pause、target yaw/pitch 四组状态，不发送服务器命令，不抛出异常。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写聊天截图与对应日志。

- [ ] 4. 验证 `/fh open`
  - 操作：在无其他 Screen 的世界内执行 `/fh open`。
  - 预期结果：别名被识别并打开标题为 `FarmHelper Settings` 的原生设置 Screen；不存在 OneConfig 界面或远程依赖；打开 Screen 时旧控制所有权被安全取消。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写设置页截图与诊断。

- [ ] 5. 验证 `/farmhelper open`
  - 操作：关闭设置页后执行 `/farmhelper open`。
  - 预期结果：与 `/fh open` 打开同一原生设置 Screen；命令反馈为设置页已打开，客户端保持稳定。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写设置页截图与聊天反馈。

- [ ] 6. 验证默认 Right Shift 设置键
  - 操作：确保没有其他 Screen，按一次 Right Shift（默认 key code `344`）。
  - 预期结果：只打开一个 `FarmHelper Settings` Screen；按键不穿透为托管移动/攻击输入。Screen 已打开时重复按键不叠加第二个设置页。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写按键前后截图。

- [ ] 7. 验证 rotation command 与 ChatScreen 正常关闭
  - 操作：在玩家存在时执行 `/farmhelper rotation test 90 15 500`，不要在命令提交后额外关闭或替换 Screen。
  - 预期结果：显示 `Rotation test started.`；命令提交导致的 exact ChatScreen-to-absent 正常关闭只被消费一次，不把新旋转误判为 `SCREEN_CHANGED`；视角在约 `500 ms` 内平滑到 yaw `90`、pitch `15`，完成后 rotation owner 清空。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写提交前后视频/截图、角度和诊断。

- [ ] 8. 验证 toggle-on 与 ChatScreen 正常关闭
  - 操作：在无旧 owner 的状态执行 `/fh toggle` 开启；命令提交后观察至少数个 tick，不立即打开新 Screen。
  - 预期结果：显示 `Macro enabled.`；exact ChatScreen 正常关闭不会立刻停止刚创建的 macro owner，宏在没有其他取消边界时保持 enabled/running；不产生托管输入粘滞。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写聊天反馈、可视状态和日志。

- [ ] 9. 验证 `/farmhelper diagnostics`
  - 操作：在宏已安全停止、没有活动旋转时执行 `/farmhelper diagnostics`。
  - 预期结果：输出 config status/schema、玩家 rotation diagnostic、rotation active/paused 与 held input；输出不含秘密、原始玩家/服务器数据或远程凭据，且显示无活动旋转和无托管按键。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写聊天截图。

- [ ] 10. 验证 `/farmhelper input release`
  - 操作：执行 `/farmhelper input release`，随后再次读取 diagnostics；不得用内部注入人为制造按键所有权。
  - 预期结果：显示 `Input released.`；托管移动、攻击和 hotbar claim 均为空，物理键状态没有被反向按下，重复 release 保持幂等且不报错。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写命令反馈和 held input 诊断。

- [ ] 11. 验证 toggle-off
  - 操作：重新 `/fh toggle` 开启，确认命令关闭后仍保持开启，再执行 `/farmhelper toggle` 关闭。
  - 预期结果：第二次显示 `Macro disabled.`；走 `MANUAL_STOP` 边界，宏、rotation、inventory、task 与 input 所有权全部清空；后续 ChatScreen 正常关闭不把终止原因改写为较弱的 pause/screen 原因。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写反馈、状态和日志。

- [ ] 12. 验证 `/farmhelper stop`
  - 操作：在可能存在活动 macro/rotation 的状态执行 `/farmhelper stop`，并在完全空闲时重复一次。
  - 预期结果：每次显示 `Macro disabled and controls released.`；第一次取消全部 owner，第二次安全幂等；命令后的 exact ChatScreen 关闭不覆盖 `STOP` 终止语义。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写命令反馈与 diagnostics。

- [ ] 13. 验证 inventory Screen fail-safe
  - 操作：启动一次 rotation 或 toggle-on 后立即用正常玩家操作打开 inventory；关闭 inventory 后检查状态。
  - 预期结果：出现 inventory Screen 时旧 macro/rotation/input/task 所有权被取消；物品栏没有幽灵点击、错误 slot、卡住 hotbar 或自动关闭；关闭后不会恢复旧 owner。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写 inventory 前后截图、slot/hotbar 状态和 diagnostics。

- [ ] 14. 验证 settings Screen、草稿与持久化
  - 操作：打开设置页，修改 target yaw、target pitch 和 Open settings key；分别检查 Reset、Save、Done，再重新打开设置页。
  - 预期结果：编辑只影响草稿；Reset 恢复默认草稿；Save 经校验后原子保存；Done 正常返回父 Screen；重新打开显示已保存值，非法范围不能写入，设置 Screen 不持有业务运行状态。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写各状态截图、配置文件哈希和日志。

- [ ] 15. 验证 Pause Screen fail-safe
  - 操作：启动 rotation 或 toggle-on 后按 Escape 打开 Pause Screen，停留数个 tick，再返回世界。
  - 预期结果：普通 Pause Screen 边界立即取消 macro、rotation、inventory、task 和托管 input；所有移动/攻击键释放；返回世界后保持安全停止，不自动恢复旧 owner。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写暂停前后视频/截图和 diagnostics。

- [ ] 16. 验证 Save & Quit 与世界重载
  - 操作：在曾经启动过 toggle/rotation 的世界中使用 `Save and Quit to Title`；随后重新载入同一世界。
  - 预期结果：世界卸载时所有 owner、任务、旋转、inventory 操作和托管输入被取消；重载后没有旧 owner、旧 held input、旧 rotation 或旧 task，macro 默认为 disabled/stopped；只有 fresh world/connection snapshot 后才允许新的获取。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写退出前、标题页、重载后的截图与 diagnostics。

- [ ] 17. 验证整段会话日志安全
  - 操作：正常结束矩阵后检查本次 `run/logs/latest.log`、`run/logs/debug.log` 和 `run/crash-reports`。
  - 预期结果：crash report 为 `0`；不存在 Mixin apply/transform failure、FarmHelper exception、cancellation failure、秘密泄露或 FarmHelper remote/WebSocket/Webhook/Discord/analytics 活动。Vanilla 自身的离线认证提示必须与 FarmHelper 活动区分记录。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写日志路径、SHA-256、检索式和命中数。

- [ ] 18. 正常 Quit Game 并确认零进程
  - 操作：从标题页点击 `Quit Game`，等待 Gradle wrapper/daemon/client 正常退出；仅在异常情况下按 exact PID 清理本次会话进程。
  - 预期结果：Minecraft 窗口正常关闭；本次 Minecraft、runClient、launcher、wrapper 和 tester Gradle 匹配进程均为 `0`；不终止会话前已存在的无关进程。
  - 实际结果：未执行（见“未执行原因”）。
  - 证据：待未来执行者填写退出截图、exact PID 列表和零进程查询结果。

## 自动回归与 GUI 边界

危险的内部状态注入不属于 GUI 手测范围，包括强制异常、伪造 stale connection snapshot、并发或重入 owner 获取、Screen identity mismatch、inventory click/rollback 竞争、断线 latch 绕过以及故意破坏取消回调。这些路径已经由 P0 exact automated regressions 覆盖；对应 JDK 25 clean build 记录为 `46` 个 XML suites、`268` tests、`0` failures、`0` errors、`0` skipped。人工执行者不得为了重复这些内部回归而使用反射、调试器、内存修改或盲输入。

## 未来执行记录规则

若未来由人类手动执行本矩阵，必须保持 exact commit/tree/environment，逐项勾选，并把每项“实际结果”和“证据”替换为真实观察；证据至少包含截图或视频、日志路径与 SHA-256、必要的命令反馈和退出 PID/零进程结果。任何未执行、观察不稳定或与预期不一致的项目都必须原样记录，不得把本文、既有启动日志或自动回归结果当作 GUI 验收通过证明。
