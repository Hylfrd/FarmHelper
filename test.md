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

# P1 SShapeVertical GUI/gameplay 待工作电脑验收

## P1 状态与固定锚点

本节只覆盖 P1 `SShapeVerticalCropMacro` 新增行为；全部项目均为未勾选的 `NOT RUN / NOT PASS`。沙盒自动测试、GitHub CI 和无 GUI 的 `runClient` 启动/日志验证都不能替代工作电脑上的可观察 GUI/gameplay 验收，也不能使任何复选框变为 PASS。

- 已批准产品代码锚点 commit：`f44bf5238dec4c803b2354b89abc2fcc94b6109a`
- 已批准产品代码锚点 tree：`0b41b1edaaf032ae918ce63f4887504731616a04`
- 最终 P7 GUI 验收 commit：`待填写`
- 最终 P7 GUI 验收 tree：`待填写`
- 最终 P7 GUI 验收 JAR 路径/大小/SHA-256：`待填写`

执行 P1 时复用 P0 #2～#3 和 #8～#18 的单人世界、命令反馈、生命周期、控制释放、日志与退出证据步骤，不重复执行无关的通用案例。P1 对新宏对象采用可区分原因、可嵌套的 Screen pause/resume：它仅在 P1 对象上取代 P0 #13/#15 的“Screen 一律终止且关闭后不恢复”预期；`stop`、世界卸载和断线仍是 terminal boundary，绝不允许恢复。

## P1 未执行矩阵

- [ ] P1-01 development-world gate — NOT RUN / NOT PASS
  - 操作：分别组合验证 Fabric development environment、integrated server 和 JVM 属性 `farmhelper.developmentWorld=true`；只在三项同时成立的本地开发世界中触发开发传送，并逐一撤掉任一条件重试。
  - 预期结果：三项必须同时成立才显示 `[DEV WORLD]` 并允许本地 `tp`；任一条件缺失都 fail closed，不显示 `[DEV WORLD]`，也不获得本地传送权限或发送替代远程命令。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写每个组合的画面、聊天反馈、JVM 参数与日志片段。

- [ ] P1-02 config persistence and active non-mutation — NOT RUN / NOT PASS
  - 操作：在 stopped 状态依次执行 `/fh macro mode 0`、`1`、`2`、`5`、`6`、`9`，并执行 `/fh spawn set`、`/fh rewarp add`、`/fh rewarp remove`、`/fh rewarp clear`；保存前后记录配置文件 SHA-256、`/fh status` 和 `/fh diagnostics`，退出并重启验证 roundtrip。另在 active 状态尝试改 mode 和 `/fh config reset`，最后在 stopped 状态执行 reset 并模拟可观察的保存失败边界。
  - 预期结果：六种 mode、spawn 与 rewarp 在正常保存后重启一致；schema 中 `alwaysHoldW` 默认 `false`、`holdLeftClickWhenChangingRow` 默认 `true`。active mode/reset 被明确拒绝，macro generation、配置内容和文件 hash 均不变；stopped reset 要么原子提交完整默认值，要么失败并完整恢复旧配置，不得留下半写文件。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写各命令反馈、generation、重启前后配置内容与 SHA-256；不可安全复现的写盘失败保持 NOT RUN。

- [ ] P1-03 start/pause/resume/Screen overlap/stop — NOT RUN / NOT PASS
  - 操作：执行 `/fh macro start`，在 `300 ms` startup 窗口内及完成后观察 generation/输入；依次叠加 manual pause 与 Screen pause，以两种顺序逐个解除，再执行 `/fh macro stop` 后尝试 resume；复用 P0 #8、#11～#16 的可观察状态和控制释放证据。
  - 预期结果：startup 满 `300 ms` 前无 farming 输入；嵌套 pause cause 全部清除前不得恢复。只由 Screen 引起或包含 Screen 的非终止暂停在关闭 Screen 后保持同一 generation，并保留 startup/row/rewarp/rotation 已消耗时长；manual cause 仍存在时不提前恢复。`stop` 是 terminal boundary，释放全部控制，后续 Screen 关闭或 `/fh macro resume` 都不能恢复旧 generation。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写命令反馈、状态/generation 时间线、视频和 held-input diagnostics。

- [ ] P1-04 per-mode gameplay table — NOT RUN / NOT PASS
  - 操作：下表每一行都要在独立 fresh start 中，对 LEFT/RIGHT 两种横向行进方向分别搭建成熟作物，覆盖正常收割、行末、换道、掉层、rewarp、pause/resume 和 stop；记录实际 yaw/pitch、按键与攻击。分别在 `alwaysHoldW=false/true` 下观察 W，不得把 W 描述为静态 forward assist。
  - 预期结果：各 mode 只接受对应作物并满足角度区间。默认 W 是上游兼容的动态判定，`alwaysHoldW=true` 才是无条件覆盖：mode 1 南瓜/西瓜、mode 5/6 仙人掌及 Cocoa-LR 在无覆盖时不会动态加入 W；Melongkingde 仅在 exact position/front-space 规则满足时可动态加入 W。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写逐行双向视频、作物布局、角度/输入 diagnostics、行末/掉层/rewarp/pause/stop 时间线。

| 状态 | mode | 作物与角度要求 | 双向独立验收范围 |
|---|---:|---|---|
| [ ] NOT RUN / NOT PASS | `0 NORMAL` | 成熟 wheat/carrot/potato/nether wart；`2.8 <= pitch < 3.3` | LEFT、RIGHT 各自完成收割、行末、换道、掉层、rewarp、pause、stop |
| [ ] NOT RUN / NOT PASS | `1 PUMPKIN_MELON` | pumpkin/melon；`28 <= pitch < 30` | LEFT、RIGHT 各自完成全流程；默认不动态加 W |
| [ ] NOT RUN / NOT PASS | `2 MELONGKINGDE` | pumpkin/melon；`-59.2 <= pitch < -58.2` | LEFT、RIGHT 各自完成全流程；只验证 exact position/front-space 动态 W |
| [ ] NOT RUN / NOT PASS | `5 CACTUS` | 仅 cactus；`0 <= pitch < 0.5` | LEFT、RIGHT 各自完成全流程；默认不动态加 W |
| [ ] NOT RUN / NOT PASS | `6 SUNTZU` | 仅 cactus；`-39.5 < pitch <= -38` | LEFT、RIGHT 各自完成全流程；默认不动态加 W |
| [ ] NOT RUN / NOT PASS | `9 COCOA` | 成熟 cocoa；`pitch = -90` | Cocoa-LR 两方向各自完成全流程；默认不动态加 W |

- [ ] P1-05 mature compatible/incompatible crop probes and gaps — NOT RUN / NOT PASS
  - 操作：在 mode 0 精确对比 wheat/carrot/potato `age=7`、nether wart `age=3` 与各自未成熟 age；在 mode 1/2 对比 pumpkin/melon 与 stem/attached stem；在 mode 5/6 对比 cactus 与 nether wart；在 mode 9 对比 cocoa `age=2` 与未成熟 cocoa。把成熟兼容作物、成熟不兼容作物、未知/decoy 方块分别放在候选优先位置，并构造“有效作物暂时有 gap 但侧向路径仍可走”的布局。
  - 预期结果：成熟兼容且直接可收获的作物为 READY，产生攻击/移动；未成熟作物不是 READY。mode 5/6 的 nether wart 必须拒绝；优先位置出现成熟不兼容作物时 fail closed，不能越过它猜测较低优先级目标；有效作物 gap 且侧向路径可继续时继续行进而不是误判 row end；未知观察和 decoy 位置一律 fail closed。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写精确方块/age 布局、逐 tick 输入/状态、视频和诊断；未知注入不得用于 GUI 验收。

- [ ] P1-06 bidirectional row-end/lane/drop/timing — NOT RUN / NOT PASS
  - 操作：LEFT/RIGHT 两侧分别构造“无作物但通路存在”和“路径确实阻塞”的行末；测量 row dwell 与换道 rotation，分别验证 forward/backward lane、`holdLeftClickWhenChangingRow` 开关、空中小落差、严格大落差、Y 边界和 grounded 大落差，并在 drop/lane 中途 pause/resume。
  - 预期结果：row end 由路径阻塞决定，不因作物暂缺触发；row dwell 为 `400-599 ms`。forward lane 持有 `FORWARD+SPRINT` 并按设置可选 `ATTACK`，backward lane 持有 `BACKWARD`、不 sprint，并按设置可选 `ATTACK`。只有 airborne 且 drop `>0.75`、`Y<80` 才进入严格 drop，直到真实 `onGround` 前无输入；grounded drop `>1.5` 走独立分支。pause 立即释放控制，resume 保留同 generation 与剩余时序。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写双向布局、阻塞/通路截图、毫秒时间线、Y/onGround、按键/攻击与 pause/resume 视频。

- [ ] P1-07 rewarp prerequisites/dwell/retry/landing/rotation — NOT RUN / NOT PASS
  - 操作：验证空/非空 rewarp list 与未设/已设 spawn；站在 exact rewarp origin 静止触发并测量 dwell，分别在 dwell 中离开、移动或删除配置；传送后分别制造距 origin 的 `distanceSq > 2`、exact spawn、仍在原点、普通坠落/窒息和 flying 场景，观察 retry、落地、旋转与 farming 恢复。
  - 预期结果：必须同时有非空 rewarp list 和 spawn；每次在 `400-749 ms` dwell 内持续重验 exact origin 与 stationary，离开、移动或移除配置立即取消。`distanceSq > 2` 或 exact spawn 任一成立即确认，否则 `5000 ms` 后 retry；普通 fall/suffocation 等待安全落地，flying 使用 `SNEAK 350-649 ms`。确认后等待 `1500 ms`，再有 post delay `600 ms`；spawn yaw/pitch 只作 warp metadata，恢复耕作时必须回到 farming angle。rotation 基础时长 `500-799 ms`，仅 yaw delta `>90` 时翻倍。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写配置/原点、位移、distanceSq、飞行/落地、retry 与 rotation 毫秒时间线及视频；`5000 ms` retry 如无法安全稳定复现可保留 automated-only/NOT RUN。

- [ ] P1-08 lifecycle/UNKNOWN/no-input recovery — NOT RUN / NOT PASS
  - 操作：只用自然发生的世界/玩家/连接缺失和 unloaded spatial evidence，覆盖 Screen pause/close、普通 fail-closed、row/rewarp dwell、manual stop、Save & Quit、断线与重载；观察 stall 的 recovery handoff。不得用注入、反射、调试器、盲输入或自建 UI 自动化制造 UNKNOWN。
  - 预期结果：所有 pause、fail-closed、dwell、stop、world unload 和 disconnect 边界都释放托管控制。Screen-only pause 可在同 generation 恢复；stop/world/disconnect 等 terminal boundary 永不恢复旧对象。UNKNOWN 或 unloaded evidence 必须无输入并 fail closed；stall 只产生显式 no-input recovery handoff，绝不能私自按 `JUMP+ATTACK+反向移动`。自然不可复现或不安全的 UNKNOWN 路径保持 NOT RUN，可仅引用 automated regression，绝不伪造 PASS。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写自然生命周期时间线、generation/terminal reason、held input、日志与退出零进程证明；不可自然复现项标注 automated-only/NOT RUN。

## P1 填写规则

工作电脑执行者必须先把本节 P7 commit/tree/JAR 占位符替换为唯一最终对象，再逐项以真实观察填写。任何未执行、仅由自动化覆盖、无法安全复现或证据不足的步骤继续保持 `[ ] NOT RUN / NOT PASS`；不得用本节文本、沙盒日志、CI 或自动测试代替 GUI/gameplay PASS。

# P2 shared farming + Default Melon/Pumpkin 待工作电脑验收

## P2 状态与固定锚点

本节覆盖 P2 shared farming contracts 与 mode `3 DEFAULT_MELON_PUMPKIN`；全部项目均为未勾选的 `NOT RUN / NOT PASS`。Windows 10 沙盒在 `SetIsBorderRequired` 返回 `0x80004002`，因此没有执行 Computer Use、盲输入、SendKeys 或自建 UI 自动化。沙盒自动测试、CI 与无 GUI 的 `runClient` 启动/日志证据均不能替代工作电脑上的可观察 GUI/gameplay 验收。

- 已批准产品代码锚点 commit：`de715711d9ee17a193afb92e3c1b713930c77471`
- 已批准产品代码锚点 tree：`f4b26ff7a3b00fa044ec289189967ecd7ca30c87`
- 最终 P7 GUI 验收 commit：`待填写`
- 最终 P7 GUI 验收 tree：`待填写`
- 最终 P7 GUI 验收 JAR 路径/大小/SHA-256：`待填写`

执行 P2 时复用 P0 #2～#3、#8～#18 与 P1-03/P1-08 的命令反馈、生命周期、日志、控制释放和正常退出证据。任何只能由自动回归安全覆盖的 stale identity/UNKNOWN 路径继续保持 automated-only、`NOT RUN / NOT PASS`，不得通过注入或未观察行为勾选。

## P2 未执行矩阵

- [ ] P2-01 mode 3 command/config persistence and active immutability — NOT RUN / NOT PASS
  - 操作：在 stopped 状态执行 `/fh macro mode 3`，记录配置文件与 diagnostics，正常退出并重启后复查；分别修改并 roundtrip `rotateAfterWarped`、`rotateAfterDrop`、`dontFixAfterWarping`、custom pitch/yaw 开关及 level。在 mode 3 active 时尝试切换 mode 与 reset。
  - 预期结果：mode 3 及新增行为字段原子持久化并准确重载；active 修改被拒绝且 generation、运行对象、配置文件哈希和控制 owner 不变。非法范围或保存失败不得留下半写配置。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写命令反馈、重启前后配置内容/SHA-256、generation 与 held-input diagnostics。

- [ ] P2-02 recognized but unimplemented modes refuse safely — NOT RUN / NOT PASS
  - 操作：在 stopped 状态依次选择 modes `10`、`11`、`12`、`13`，确认 parser/状态显示可识别，然后逐一执行 start；每次检查 generation、macro/rotation/input owner 和日志。
  - 预期结果：四种 mode 都被稳定识别并可保存，但 start 必须以诚实的 unimplemented 反馈 fail closed；不得创建 placeholder macro、增加运行 generation、获取控制、生成旋转或发出移动/攻击。非法 modes `-1`、`14` 仍被拒绝且配置不变。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写逐 mode 命令反馈、配置/状态、owner 与输入诊断、日志。

- [ ] P2-03 fruit/stem filtering and direction scan priority — NOT RUN / NOT PASS
  - 操作：mode 3 fresh start，分别布置 melon/pumpkin fruit、stem/attached stem、未知/decoy 方块；用当前 yaw 的连续方向扫描验证首尾角度，并构造右侧与左侧都可行、仅右侧阻塞、仅左侧阻塞和无可靠观察四组布局。
  - 预期结果：只有 melon/pumpkin fruit 是作物，stem 一律不当作 READY；方向扫描使用当前-yaw frame，覆盖 `0..179`，同距离右侧证据优先。右侧阻塞选择 LEFT、左侧阻塞选择 RIGHT；两侧均无可靠结论时无输入、无 RNG 消耗并 fail closed。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写精确方块布局、起始 yaw、选边结果、视频与日志；UNKNOWN 注入不属于 GUI 手测。

- [ ] P2-04 startup alignment and custom angle behavior — NOT RUN / NOT PASS
  - 操作：从多个 cardinal/diagonal 朝向和 LEFT/RIGHT 初始选边 fresh start，测量 `300 ms` startup、默认 yaw/pitch、rotation duration 与曲线；分别启用 custom pitch/yaw level 后重启复测，途中 pause/resume。
  - 预期结果：startup 满 `300 ms` 前无耕作输入；默认 pitch 落在 `[47,53)`，yaw 对齐最近 diagonal 并按选边使用有界 jitter，rotation duration 为 `[500,800)`。mode 3 明确使用 easeOutBack 且最终准确收敛；custom 值按配置使用。pause 释放控制且 resume 不重抽已采样 RNG、不重复创建旋转。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写毫秒时间线、实际角度/曲线视频、配置、generation 与 rotation diagnostics。

- [ ] P2-05 farming ownership, row choice and recorded-direction obstruction — NOT RUN / NOT PASS
  - 操作：分别在 LEFT/RIGHT farming 状态布置相邻 fruit、后墙可走/不可走、前路/后路组合；先形成 FORWARD 再形成 BACKWARD 记录方向，并在后续 tick 改变另一方向障碍验证 probe。
  - 预期结果：只有 active macro generation 可持有 farming 控制；FARMING 使用侧向键加 `ATTACK`，仅后方不可走时加入 `FORWARD`。作物耗尽时前路优先，否则选后路并记录 FORWARD/BACKWARD；后续输入和 obstruction probe 都必须使用记录方向，不能在 BACKWARD 状态误查或误走 front。与既有方向相反的选择只产生 no-input `LANE_BLOCKED` recovery handoff，不私自翻转。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写逐布局视频、owner/generation、按键、记录方向、probe 结果与 recovery reason。

- [ ] P2-06 lane dwell, one-block completion, timeout and attack option — NOT RUN / NOT PASS
  - 操作：分别完成 FORWARD/BACKWARD lane，测量 row dwell、移动距离与超时；切换 `holdLeftClickWhenChangingRow`；构造速度和为 `<0.15` 与边界附近场景，并在 lane 中 pause/resume。
  - 预期结果：dwell 为 `400-599 ms`；FORWARD 持有 `FORWARD+SPRINT`，BACKWARD 只持有 `BACKWARD`，`ATTACK` 仅由配置决定。位移达到一格后完成并翻转 lateral row；两秒未完成以无输入 `LANE_STALLED` handoff。低运动/阻塞判断使用记录方向；pause 释放控制并保留剩余时序。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写方向、毫秒/位移/速度时间线、按键与 attack 配置、pause/resume 视频。

- [ ] P2-07 drop boundaries and rotateAfterDrop — NOT RUN / NOT PASS
  - 操作：覆盖 flying/nonflying、airborne/grounded、drop `0.75` 与严格大于边界、grounded `1.5` 与严格大于边界、`Y=80` 与 `Y<80`；启闭 `rotateAfterDrop`，落地前后检查输入、anchor 与 lane ledger。
  - 预期结果：仅 nonflying airborne、drop `>0.75` 且 `Y<80` 进入严格 drop；grounded drop `>1.5` 走独立安全分支。空中始终释放 input/rotation；确认落地后刷新 spatial anchor、清除 lane/row 状态。启用 rotateAfterDrop 时只创建一次到相反最近 cardinal 的旋转并只抽 duration；关闭时不旋转。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写 flying/onGround/Y/高度差、输入、anchor、lane、实际角度与旋转计数。

- [ ] P2-08 rewarp timing and rotation policy — NOT RUN / NOT PASS
  - 操作：复用 P1-07 的 prerequisites、origin、stationary、retry、落地/flying/suffocation布局，测量 dwell、sneak、after-warp 和 post delay；组合 `rotateAfterWarped` 与 `dontFixAfterWarping`，并在每个阶段 pause/resume 或移除配置。
  - 预期结果：保持 P1 的 `400-749 ms` dwell、`5000 ms` retry、flying `SNEAK 350-649 ms`、`1500 ms` after-warp 与 `600 ms` post delay；配置/姿态/evidence 失效立即无输入 fail closed。按两个开关只执行规定的 mode 3 diagonal/修正策略，每 tick 最多一个 rotation request，绝无 leaf/shared 重复旋转或重复 RNG 抽样。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写配置组合、完整毫秒时间线、输入/旋转请求计数、角度与日志。

- [ ] P2-09 pause/Screen/world/connection/stop and stale safety — NOT RUN / NOT PASS
  - 操作：在 startup、farming、lane、drop、rewarp、rotation 各阶段自然触发 manual pause、Screen pause/close、Save & Quit、断线、世界重载和 stop；观察 generation、spatial request identity、rotation/input owner。不得注入 stale capture 或伪造 world epoch。
  - 预期结果：pause 立即释放控制，只有可恢复的 pause causes 全部解除后才在同 generation 继续；stop/world/connection 是 terminal boundary，旧 generation 永不恢复。迟到或身份不匹配的 capture/rotation 不得影响新对象、获取控制或推进状态；无法自然观察的 stale 路径保持 automated-only、`NOT RUN / NOT PASS`。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写阶段/generation 时间线、Screen/world/connection 事件、owner、held input 与日志。

- [ ] P2-10 complete work-computer session, logs and safe exit — NOT RUN / NOT PASS
  - 操作：完成上述矩阵后检查 latest/debug/crash/hs_err 与 FarmHelper remote activity，再通过正常 Quit Game 退出并核对本次 owned Gradle/runClient/Minecraft/java/javaw 进程。
  - 预期结果：无 actionable FarmHelper/Mixin failure、crash/hs_err、秘密泄露或 FarmHelper remote/WebSocket/Webhook/Discord/analytics 活动；正常退出后 owned process 为零且不终止无关进程。所有 P0/P1/P2 尚未真实观察的项目继续保持未勾选。
  - 实际结果：NOT RUN / NOT PASS。
  - 证据：待填写日志路径/SHA-256、检索命中、退出视频、exact PID 链与零进程查询。

## P2 填写规则

工作电脑执行者必须先把 P7 commit/tree/JAR 占位符替换为唯一最终对象，再按 exact matrix 逐项记录真实观察。任何未执行、自动化-only、证据不足或无法安全复现的项目必须保持 `[ ] NOT RUN / NOT PASS`；严禁把沙盒 CI、测试、启动日志或本文预期改写成 GUI/gameplay PASS。
