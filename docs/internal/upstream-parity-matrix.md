# FarmHelper 2.10.0 上游功能对齐矩阵

## 文档边界

本文件是 S0-T2 的迁移检查表，不是实现规范的替代品，也不包含上游功能实现代码。后续任务应同时遵守 `progress.md` 的阶段边界和本矩阵；出现冲突时以 `progress.md` 的已批准产品决策为准。

- 上游仓库：<https://github.com/JellyLabScripts/FarmHelper>
- 上游发布：<https://github.com/JellyLabScripts/FarmHelper/releases/tag/2.10.0.eacb323>
- 固定基准提交：`eacb323fbde3eff94d4f2ee7baacb059d84b8e3a`
- 发布名称：`FarmHelperV2 2.10.0`
- 本地证据形式：当前 Git 对象库中的 `eacb323:<path>`；不会把上游源码复制进当前工作树。
- 清点结果：292 个受版本控制文件，其中 194 个 Java 文件、85 个 `src/main/resources` 文件。

状态约定：

| 状态 | 含义 |
| --- | --- |
| 保留 | 产品行为需要迁移；只在对应后续任务中实现。 |
| 保留并改写适配 | 保留领域行为，但 Forge/Minecraft 1.8.9/OneConfig 接口必须替换。 |
| 本地降级 | 只保留不依赖远程、分析或伪造数据的诚实子集。 |
| 已确认删除 | `progress.md` 已明确删除，禁止恢复。 |
| 待后续决策 | 不属于已确认保留或删除；不得在 S0-T2 擅自实现或删除。 |

## 计划模块键

| 矩阵模块 | 计划中的职责 | 主要计划任务 |
| --- | --- | --- |
| `platform` | Fabric 生命周期、玩家/世界/方块/实体/Menu/渲染/声音/窗口/网络适配和最小 Mixin | S2-T4～S2-T8、S9-T3 |
| `runtime` | 不可变快照、计分板/Tab/聊天/Garden 解析、时钟与调度 | S2-T1、S2-T4、S2-T5 |
| `control` | 托管输入、一次性旋转、物品栏交互和完整取消 | S2-T2、S2-T3、S2-T6 |
| `navigation` | 飞行 Node、路径执行、跟随、平滑、重算和取消 | S5-T1～S5-T4 |
| `macro` | 宏生命周期、共享状态、rewarp 和八个作物宏 | S3-T1～S3-T3、S4-T1～S4-T8 |
| `feature` | 独立自动化、工具、暂停所有权、依赖和冲突 | S7-T1～S7-T15 |
| `failsafe` | 检测器、优先级、录制反应、重启和本地提醒 | S6-T1～S6-T8 |
| `config` | 带版本号 JSON、校验、默认值和迁移 | S1-T2、S8-T2 |
| `ui` | 原生 Screen、控件、键位录制和指令适配 | S1-T3、S1-T4、S8-T2、S8-T3 |
| `hud` | 只读 ViewModel 和四个保留 HUD | S8-T1 |
| `utility` | 无 Minecraft 依赖的算法、格式化、角度、队列和纯辅助类型 | 随使用方任务迁移 |

## 宏类、模式与状态

2.10.0 的 `FarmHelperConfig.macroType` 有 14 个模式，`MacroHandler.getMacro()` 把它们映射到八个宏类。所有宏共享 `AbstractMacro.State`：`NONE`、`DROPPING`、`SWITCHING_SIDE`、`SWITCHING_LANE`、`LEFT`、`RIGHT`、`BACKWARD`、`FORWARD`、`A`、`D`、`S`、`W`；共享 `RewarpState`：`NONE`、`TELEPORTING`、`TELEPORTED`、`POST_REWARP`；共享行进轴 `WalkingDirection.X/Z`。不得改变枚举顺序所表达的上游状态语义，但新实现应使用显式状态而非依赖序号。

| 上游类 | `macroType` 模式 | 实际使用状态/行为检查点 | 依赖 | Fabric 映射与任务 | 人工验证 |
| --- | --- | --- | --- | --- | --- |
| `SShapeVerticalCropMacro` | 0 普通垂直作物；1 南瓜/西瓜；2 Melongkingde；5 仙人掌；6 Suntzu 仙人掌；9 左右可可 | `NONE/DROPPING/LEFT/RIGHT/SWITCHING_LANE`；方向、作物成熟、行末、换行、攻击、掉层、lag-back、rewarp；模式特定 yaw/pitch | `GameStateHandler`、`BlockUtils`、`CropUtils`、`AntiStuck`、输入、旋转 | `macro` + `runtime/platform/control`；S3-T3 | 每个模式双向、换行、掉层、rewarp、pause/stop |
| `SShapeMelonPumpkinDefaultMacro` | 3 默认 Plot 南瓜/西瓜 | `NONE/DROPPING/LEFT/RIGHT/SWITCHING_LANE`，另有 `ChangeLaneDirection`；破坏角度、换行、掉层、rewarp | 扫描、`AntiStuck`、输入、旋转 | `macro`；S4-T1 | 南瓜/西瓜两种角度、边界和取消 |
| `SShapeSugarcaneMacro` | 4 甘蔗/野玫瑰/向日葵；2.10.0 作物枚举另含月光花 | `NONE/DROPPING/A/D/S`；双行/斜向移动、水边、障碍、换行、rewarp | 方块/碰撞扫描、输入、旋转 | `macro`；S4-T2 | 两个方向、水边、未加载 Chunk、换行 |
| `SShapeCocoaBeanMacro` | 7 可可；8 带 trapdoor 可可 | `NONE/BACKWARD/FORWARD/SWITCHING_SIDE/SWITCHING_LANE`；成熟度、垂直目标、trapdoor、掉层、rewarp；上游没有处理共享 `DROPPING` | 作物扫描、输入、旋转 | `macro`；S4-T3 | 两模式、成熟度、trapdoor、方向变化、安全掉层 |
| `SShapeMushroomMacro` | 10 45° 蘑菇 | `NONE/DROPPING/LEFT/RIGHT`，内部 `LookDirection`；交替移动、目标、换行、掉层 | 扫描、输入、旋转 | `macro`；S4-T4 | 双向、有/无作物、pause/stop |
| `SShapeMushroomRotateMacro` | 11 30° 旋转蘑菇 | `NONE/DROPPING/LEFT/RIGHT`；旋转驱动换行、时序、恢复、rewarp | 一次性旋转所有权、输入 | `macro` + `control`；S4-T5 | 每次旋转、取消时释放输入 |
| `SShapeMushroomSDSMacro` | 12 Mushroom SDS | `NONE/DROPPING/LEFT/RIGHT/SWITCHING_LANE`；特殊移动序列、时序、旋转和隔离 | 时钟、输入、旋转 | `macro`；S4-T6 | 完整序列、部分取消、模式隔离 |
| `CircularCropMacro` | 13 环形普通作物 | `NONE/DROPPING/A/D/S/W`；环形移动、目标角度、漂移/障碍恢复、掉层、rewarp | 扫描、输入、旋转 | `macro`；S4-T7 | 正常环行、漂移、障碍、pause/stop |

`AbstractMacro` 可把所有宏切到共享 `DROPPING`。七个宏类显式处理该状态；`SShapeCocoaBeanMacro` 没有对应 `switch` 分支，可能停留在无法恢复的状态。这是 2.10.0 的明确缺口，S4-T3 必须实现可测试的安全掉层/停止行为，不能照搬遗漏。

共享宏契约：

| 上游责任 | 保留行为 | 新模块/任务 | 关键边界 |
| --- | --- | --- | --- |
| `MacroHandler` | 选择、toggle、enable/disable、pause/resume、功能交接、作物选择 | `macro`，S3-T1、S4-T8 | 运行中不得静默换宏；stop 取消所有控制。 |
| `AbstractMacro` | 保存状态、当前状态、目标 yaw/pitch、行进方向、rewarp 生命周期 | `macro`，S3-T1、S3-T2 | 领域状态不得直接访问 Minecraft。 |
| `FarmHelperConfig.CropEnum` | `NONE/CARROT/NETHER_WART/POTATO/WHEAT/SUGAR_CANE/MELON/PUMPKIN/PUMPKIN_MELON_UNKNOWN/CACTUS/COCOA_BEANS/MUSHROOM/MUSHROOM_ROTATE/SUNFLOWER/MOONFLOWER/ROSE` | `config` + `runtime/macro` | 作物识别由统一解析/扫描服务提供。 |
| rewarp 与 spawn | rewarp 列表、位置高亮、spawn 坐标/yaw/pitch/Plot、warp 确认、重试、传送后延迟 | `macro/config/platform/control`，S3-T2 | 发送指令不等于成功；未知状态安全停止。 |
| `RotationHandler`/`KeyBindUtils` | 显式缓动旋转和集中按键意图 | `control`，S2-T2、S2-T3 | 不复制直接写键状态；所有权和取消优先。 |

## Failsafe 对齐矩阵

`FailsafeManager` 在上游固定注册下列 16 个检测器。数字越小优先级越高；候选进入 `emergencyQueue` 后延迟选择。新实现必须保持确定性优先级、去重、reset、反应完成和可取消重启，不得保留 Webhook/tag-everyone 输出。

| 检测器 | 优先级 | 2.10.0 检测信号 | 保留依赖/适配 | Fabric 映射与任务 | 特殊结论/测试 |
| --- | ---: | --- | --- | --- | --- |
| `BadEffectsFailsafe` | 1 | 客户端 tick 的异常状态效果 | 玩家效果快照、宏/Feature 暂停、录制、本地提醒 | `failsafe`，S6-T5 | 合成效果、清除、reset |
| `BanwaveFailsafe` | 6 | `ReceivePacketEvent` 只作为轮询入口，真实判定完全来自 `BanInfoWS.isBanwave()` | 仅保留检测器注册与 pause/leave/reconnect 契约 | `failsafe`，S6-T6 | **本地降级**：删除 `BanInfoWS` 后无可信本地信号时必须显示 unavailable/禁用，禁止伪造 banwave 数据。 |
| `BedrockCageFailsafe` | 1 | `S08PacketPlayerPosLook` 高 Y 传送后扫描附近 bedrock 数量 | 收包事件、位置/方块快照、移动录制 | `failsafe/platform`，S6-T4 | 正常传送、bedrock 阈值、未加载 Chunk、取消 |
| `CobwebFailsafe` | 3 | `GameStateHandler` tick 检测接触/缓存 cobweb；并非直接覆写 Failsafe 事件方法 | 方块/碰撞快照、AntiStuck 协调、录制 | `failsafe/runtime`，S6-T4 | 放置、持续、移除、误报取消 |
| `DirtFailsafe` | 3 | `BlockChangeEvent`：空气/作物变为可碰撞实心块，排除玩家破坏回滚与 lag | 方块变化、LagDetector、CropUtils、录制 | `failsafe/platform`，S6-T4 | 正常农场变化、移除、lag、未知 Chunk |
| `DisconnectFailsafe` | 1 | Forge 客户端断线生命周期事件；上游在远程 Banwave pause 和 Scheduler 主动休息断线时抑制检测 | 连接/Screen 快照、AutoReconnect、Scheduler；删除 `BanInfoWS` 抑制条件 | `failsafe/platform`，S6-T7 | 主动/意外断线、Scheduler 计划断线、释放控制、重连所有权 |
| `EvacuateFailsafe` | 1 | 服务器关闭聊天和 tick 倒计时 | 聊天/计分板解析、连接服务 | `failsafe/runtime`，S6-T7 | 倒计时、`/evacuate`、timeout |
| `FullInventoryFailsafe` | 3 | tick 检查背包满；上游反应状态名误写为 `ItemChangeState` | 物品栏摘要、AutoSell 交接 | `failsafe`，S6-T5 | 时间窗口、短暂满包、reset；不要复制错误命名。 |
| `GuestVisitFailsafe` | 1 | Tab/聊天形成 Guest 状态，tick/chat 触发 | Garden/Guest 解析、宏暂停 | `failsafe/runtime`，S6-T6 | 到访、离开、名称、重复消息 |
| `ItemChangeFailsafe` | 3 | 收到 Slot 更新包且当前手持物异常变化 | Item Component/物品栏快照、主动换物品抑制 | `failsafe/platform`，S6-T5 | 主动换物、意外换物、Screen/reset |
| `JacobFailsafe` | 7 | tick 读取竞赛作物与计数并比较各作物上限 | Jacob 解析、作物配置、连接/暂停动作 | `failsafe/runtime`，S6-T6 | 全作物阈值、竞赛结束、当前作物限制 |
| `KnockbackFailsafe` | 4 | `S12PacketEntityVelocity` 的玩家垂直速度阈值 | 收包事件、运动快照、录制 | `failsafe/platform`，S6-T3 | 正常运动、阈值、误报、取消 |
| `LowerAvgBpsFailsafe` | 9 | tick 读取 `BPSTracker` 窗口，低于 `minBpsThreshold` | BPS 服务、宏生命周期 | `failsafe/feature`，S6-T5 | 暂时下降、平均窗口、pause/reset |
| `RotationFailsafe` | 4 | `S08PacketPlayerPosLook` yaw/pitch 与预期缓存比较，再由 tick 时间窗确认 | 收包事件、旋转所有权、导航/rewarp 豁免 | `failsafe/control`，S6-T3 | 正常旋转、寻路/传送缓存、lag、误报取消 |
| `TeleportFailsafe` | 5 | `S08PacketPlayerPosLook` 位置与预期缓存比较，tick 检查距离/时间窗 | 位置快照、导航/rewarp 豁免、宏状态恢复 | `failsafe/navigation`，S6-T3 | 相对坐标 flags、正常 rewarp、缓存、timeout |
| `WorldChangeFailsafe` | 2 | `MacroHandler` tick 在离开 Garden 且非正常传送时入队；检测器自身 tick 另检查 Limbo，聊天处理 warp/连接失败后的恢复 | 世界/位置/聊天快照、AutoReconnect | `failsafe/platform`，S6-T7 | 世界卸载/加载、Garden 丢失、正常传送豁免、Limbo、warp 错误、取消 |

通知配置页还含 `notifyOnLagBackFailsafe`/`tagEveryoneOnLagBackFailsafe` 字段，但上游没有第 17 个 `LagBackFailsafe` 类。它们不得被错误登记为额外检测器；lag-back 行为属于宏/AntiStuck/Teleport-Rotation 协调。

## 保留 Feature 与工具

### 核心、统计与经济

| 组件 | 2.10.0 行为/状态 | 主要依赖 | Fabric 映射与任务 | 人工验证 |
| --- | --- | --- | --- | --- |
| `FeatureManager`/`IFeature` | 注册、start/resume/stop/reset、暂停集合、Failsafe 检查策略 | Macro/Failsafe 生命周期 | `feature`，S7-T1 | 重复注册、依赖顺序、嵌套暂停、stop |
| `AntiStuck` | `UnstuckState`；相交方块、方向恢复、lag-back 计数、有限尝试、rewarp fallback | 宏状态、方块扫描、Rotation/Teleport/Cobweb/BadEffects 检测 | `feature/control`，S5-T5 | 停滞、障碍、成功、耗尽、所有权冲突 |
| `DesyncChecker` | 记录 `ClickedBlockEvent`，检测服务端方块未同步并暂停恢复 | 点击/方块事件、宏暂停 | `feature/runtime`，S5-T5 | 点击记录、延迟、恢复、reset |
| `LagDetector` | 收包/tick 时间窗、刚刚 lag 状态和连接重置 | 网络与客户端 tick | `feature/runtime`，S7-T13 | lag 开始/结束、陈旧状态、换服 |
| `AutoReconnect` | `State`；断线 Screen、延迟、服务器重连、回到 Garden | 连接/Screen、Failsafe、Macro、GameState | `feature/platform`，S7-T13 | 成功、失败、timeout、手动取消 |
| `MovRecPlayer` | 解析和播放位置/旋转/按键时间线，随机选择录制，pause/stop 清理 | movement 资源、输入/旋转所有权 | `failsafe/control`，S6-T2 | 所有 51 个资源加载、时间线、损坏文件、取消 |
| `Scheduler` | `SchedulerState`；耕种/休息随机时长、Jacob 暂停、休息动作、等待 rewarp | Macro、GameState、Rotation、连接 | `feature/runtime`，S7-T3 | 完整循环、竞赛、断线、配置变化 |
| `LeaveTimer` | 本地倒计时后离开，和 Failsafe/Macro 协调 | 时钟、连接、Macro | `feature`，S7-T3 | 倒计时、取消、重启 |
| `BPSTracker` | 从破坏事件计数 BPS，遵守宏 pause/resume | `PlayerDestroyBlockEvent`、GameState、Macro | `feature`，S7-T2 | 窗口、pause、重复事件 |
| `ProfitCalculator` | 作物/稀有掉落/培养附魔/价格与每小时收益，定期 Bazaar 价格 | Item/聊天/包/计分板、BPS、公开价格 fallback | `feature`，S7-T2 | 重复计数、价格失败、无网络 |
| `UsageStatsTracker` | 24h/7d/30d/lifetime 本地使用统计 | 本地文件、时钟 | `feature`，S7-T2 | 损坏恢复、隐私、reset |
| `AutoBazaar` | `MainState/BuyState/SellState`；查找、数量、价格、确认、上限、回调；`sell()`/`SellState` 在 2.10.0 仓库内没有调用点，但不是已确认删除项 | Menu/Slot、货币解析、时钟 | `feature/control`，S7-T4 | 买入与卖出状态、成功、价格/资金/Menu/timeout 失败 |
| `AutoSell` | `MarketType/NPCState/BazaarState/SacksState`；满包触发、NPC/Bazaar/Sack、定制物品、Compactor | 上游内嵌 NPC/Bazaar/Sack Menu 状态、Macro、GameState、Visitors/AutoComposter 调用方；不依赖 `AutoBazaar` | `feature/control`，S7-T5 | 每条市场路径、白名单、父子调用、冲突、取消 |

### 装备、消耗品与 Garden 维护

| 组件 | 2.10.0 行为/状态 | 主要依赖 | Fabric 映射与任务 | 人工验证 |
| --- | --- | --- | --- | --- |
| `AutoWardrobe` | `State`；衣柜槽位选择、切换与验证 | Menu/Slot、物品识别 | `feature/control`，S7-T6 | 正确/错误装备、Menu 变化、timeout |
| `PetSwapper` | `State`；Jacob 前后按名称选宠物、延迟和恢复 | Jacob 解析、Menu/Slot、Macro | `feature`，S7-T6 | 缺失宠物、竞赛边界、取消 |
| `RancherSpeedSetter` | `NONE/START/INPUT/CONFIRM/END`；读取和设置 Rancher's Boots 速度 | Item Component、Sign/Menu 输入、Macro | `feature/control`，S7-T6 | 正确速度、错误物品、输入/确认失败 |
| `AutoCookie` | `State/BazaarState/MoveCookieState`；Buff 检测、库存/购买、移动和消费验证 | Buff、上游内嵌 Bazaar/Menu 状态、物品栏、Macro/Failsafe；新实现应复用共享交易服务 | `feature`，S7-T7 | 已激活、缺物品、购买、消费验证 |
| `AutoGodPot` | `GodPotMode/AhState/GoingToAHState/ConsumePotState/MovePotState/BackpackState/BitsShopState` | Buff、Storage/Backpack、Bits Shop、Auction House、物品栏；上游不依赖 Bazaar | `feature`，S7-T7 | 每个保留来源、上限、验证、timeout |
| `AutoComposter` | `MainState/TravelState/ComposterState/BuyState`；资源阈值、购买、旅行、坐标、AutoSell | GameState、AutoBazaar、AutoSell、导航、Menu | `feature/navigation`，S7-T8 | 有/无资源、两种旅行、竞赛、失败 |
| `AutoSprayonator` | `State`；喷剂 Buff、材料、自动购买、起始/额外延迟 | Tab 状态、AutoBazaar、物品栏 | `feature`，S7-T8 | 材料、购买、Tab 更新、取消 |
| `AutoRepellent` | `State/MoveRepellentState`；Buff、库存移动、Desk/SkyMart 购买和使用 | GameState/copper、Desk/SkyMart Menu、物品栏、Macro/Failsafe；上游不依赖 AutoBazaar | `feature`，S7-T8 | 两种 repellent、铜币不足、缺物品、消费验证 |
| `AutoPestExchange` | `NewState`；数量/竞赛触发、旅行、Desk 坐标、兑换和返回 | Pest 状态、导航、Menu、Macro/Failsafe | `feature/navigation`，S7-T8 | 触发、Desk、旅行、兑换、失败 |

### Visitor、Pest 与本地工具

| 组件 | 2.10.0 行为/状态 | 主要依赖 | Fabric 映射与任务 | 人工验证 |
| --- | --- | --- | --- | --- |
| `VisitorsMacro` | `MainState/TravelState/CompactorState/VisitorsState/Rarity/BuyState`；旅行、排序、名称/稀有度过滤、Offer、花费上限、接受/拒绝、AFK | AutoSell、AutoBazaar、导航、实体、Menu、GameState | `feature/navigation`，S7-T9 | 全状态、过滤、上限、实体/Menu 身份、恢复 |
| `PestsDestroyer` | `States/RotationState/EscapeState`；Plot/Desk/传送、粒子/实体定位、Vacuum、寻路、击杀、逃脱、装备、返回 | 粒子/实体事件、Plot、导航、输入/旋转、装备 | `feature/navigation`，S7-T10 | 合成 Pest、目标丢失、路径失败、完整返回 |
| `PestsDestroyerOnTheTrack` | FOV/停留/停滞窗口，在耕种轨迹上检测 Pest 并交接 | GameState、Macro、Rotation、PestsDestroyer | `feature`，S7-T11 | 阈值、Jacob 抑制、交接/reset |
| `PestFarmer` | `MainState/State/ReturnState`；装备循环、生成等待、spawn、鱼竿动作、阈值和 Destroyer 交接 | PestsDestroyer、导航、装备、Macro/Failsafe | `feature`，S7-T11 | 完整/中断循环、控制排斥、装备恢复 |
| `PlotCleaningHelper` | 方块目标、工具选择、旋转/破坏、渲染、停止 | 方块扫描、reach、输入/旋转、渲染 | `feature/platform`，S7-T12 | 目标、未加载 Chunk、Screen、stop；删除 1ms 轮询。 |
| `PerformanceMode` | 降低 FPS、Fast Render 和渲染减负 | 客户端渲染能力 | `feature/platform`，S7-T14 | 开关、异常/退出恢复、不支持环境 |
| `Freelook` | 独立相机 yaw/pitch、第三人称距离和模型视角 | 相机/渲染适配、按键 | `feature/platform`，S7-T14 | 开关、视角恢复、宏输入不受影响 |
| `PiPMode` | 窗口/PiP 状态切换 | 窗口能力 | `feature/platform`，S7-T14 | 能力检测、焦点/退出恢复 |
| `UngrabMouse` | AFK 时释放鼠标并安全恢复 | 窗口/输入适配 | `feature/platform`，S7-T14 | Windows/macOS 能力、焦点、异常恢复 |

上游 `FeatureManager.fillFeatures()` 把 `UsageStatsTracker` 注册了两次。这是明确缺陷，后续 S7-T1/S7-T15 必须只注册一次，不能为了字节相似复制。`RancherSpeedSetter` 是按需辅助状态机而非长期 Feature；新模块仍应由 Feature/控制服务拥有其生命周期。

## HUD 对齐

| HUD | 保留数据显示 | 必须删除/隔离的上游分支 | Fabric 映射与任务 | 人工验证 |
| --- | --- | --- | --- | --- |
| `StatusHUD` | 宏/暂停/调度/离开倒计时、位置、Pest、本地连接状态 | `BanInfoWS` ban 统计、analytics server 连接文案、Discord remote control 状态 | `hud`，S8-T1 | 缺失数据、Garden 外可见性、缩放/边界 |
| `ProfitCalculatorHUD` | 总收益、每小时、BPS、运行时间、作物与稀有掉落图标 | 无远程输出；价格失败显示 unknown/fallback | `hud`，S8-T1 | 布局、streamer mode、缺价格 |
| `DebugHUD` | GameState、Buff、位置、宏状态、可行走方向、Feature/Failsafe/寻路诊断 | 秘密、远程连接状态和无限制日志不得显示 | `hud`，S8-T1 | 开发模式保护、unknown、性能 |
| `UsageStatsHUD` | 24h/7d/30d/lifetime 本地统计和标题选项 | 任何上传/遥测 | `hud`，S8-T1 | 各时间窗、损坏统计文件、隐私 |

所有 HUD 只读取不可变 ViewModel，不能拥有宏或 Feature 状态。

## 指令组对齐

| 上游指令 | 2.10.0 行为 | 结论 | 新入口/任务 |
| --- | --- | --- | --- |
| `/fh`、`/farmhelper` | 打开 OneConfig GUI | 保留入口语义，替换 UI | `/farmhelper` 与 `/fh` 打开原生设置；S1-T3/S1-T4 |
| `/fh pathfindmob` (`pfm`) | 按实体名称寻路，可 follow/smooth/Y offset | 保留为导航诊断，普通/开发权限待 S8 明确 | Manager 委托；S5-T3/S5-T4、S8-T3 |
| `/fh pathfind` (`pf`) | 坐标寻路，可 follow/smooth/threshold/sprint | 保留为导航诊断 | Manager 委托；S5-T3、S8-T3 |
| `/fh stoppath` (`sp`) | 停止路径 | 保留并纳入全局取消 | `/fh navigation stop` 或兼容 alias；S5-T3/S8-T3 |
| `/fh markSpawnChanged` (`msc`) | PestFarmer 开发标志 | 保留意图，仅开发模式 | 受保护的诊断动作；S8-T3 |
| `/fh update` (`up`) | 打开内置更新器 | **已确认删除** | 不提供替代指令 |
| `/fhrewarp add/remove/removeall` | 管理当前位置 rewarp | 保留行为并并入根树 | `/fh rewarp add/remove/clear`；S3-T2/S8-T3 |
| `remote/command/**` | WebSocket/JDA 的 autosell、disconnect、info、reconnect、screenshot、send command、set speed、toggle | **已确认删除** | 禁止存在远程等价物 |

上游指令直接调用单例和 Minecraft；新指令只能校验参数并委托 Manager/服务，不得拥有业务逻辑。

## 事件与信号对齐

上游有 16 个自定义事件源文件；`MotionUpdateEvent` 还定义 `Pre/Post` 两个具体事件类型。下表为 15 行，是因为 `UpdateTablistEvent` 与 `UpdateTablistFooterEvent` 合并登记。它们必须按“所需语义”迁移，不复制 Forge Event 类型。Minecraft 状态变更只能在客户端线程交付。

| 上游事件 | 生产者/消费者证据 | 保留语义 | Fabric 映射 |
| --- | --- | --- | --- |
| `BlockChangeEvent` | `MixinChunk` → FailsafeManager/DirtFailsafe | 方块旧/新状态和位置 | 优先 Fabric/世界事件，必要时窄 Mixin；S2-T8 |
| `ChunkServerLoadEvent` | `MixinChunkProviderClient`；上游无有效消费者 | Chunk 服务端加载通知 | 只有导航证明需要时保留，否则不注册死事件 |
| `ClickedBlockEvent` | `MixinPlayerControllerMP` → DesyncChecker | 玩家点击方块记录 | 方块交互事件/窄 Mixin；S2-T8 |
| `DrawScreenAfterEvent` | `MixinGuiContainer` → AutoRepellent/PestsDestroyer | Menu 绘制后覆盖层 | Screen/render callback；S2-T8 |
| `InventoryInputEvent` | `MixinGuiContainer` → AutoSell | 容器内键盘输入与可取消处理 | Screen key event；S2-T6/S2-T8 |
| `MillisecondEvent` | `FarmHelper` → PlotCleaningHelper | 1ms 轮询 | **不保留事件形式**；改为客户端 tick/预算化调度；S2-T1/S7-T12 |
| `MotionUpdateEvent.Pre/Post` | `MixinEntityPlayerSP` → RotationHandler | 发送移动包前后旋转观察 | 公开 tick/movement hook，必要时窄 Mixin；S2-T3/S2-T8 |
| `PlayerDestroyBlockEvent` | `MixinPlayerControllerMP` → BPSTracker | 玩家破坏方块 | 方块破坏回调；S2-T8/S7-T2 |
| `ReceivePacketEvent` | `MixinNetworkManager` → 宏、GameState、Lag、Profit、导航、Failsafe 等 | 只读收包信号 | 网络适配层归一化到不可变内部事件；S2-T8 |
| `SendPacketEvent` | `MixinNetworkManager`；上游无有效消费者 | 出包观察 | 没有消费者时不迁移；需要时必须只读、限域 |
| `SpawnObjectEvent` | `MixinNetHandlerPlayClient` → PestsDestroyer | 实体/物体生成位置与速度 | 实体加载/包适配；S2-T8/S7-T10 |
| `SpawnParticleEvent` | `MixinNetHandlerPlayClient` → PestsDestroyer | 粒子类型、位置、偏移 | 粒子适配事件；S2-T8/S7-T10 |
| `UpdateScoreboardLineEvent` | `MixinNetworkManager` → GameState/Profit | 单行计分板更新 | 统一计分板适配/解析；S2-T5/S2-T8 |
| `UpdateScoreboardListEvent` | `ScoreboardUtils` → GameState | 完整/清理后的计分板快照 | 不可变列表快照；S2-T5 |
| `UpdateTablistEvent`、`UpdateTablistFooterEvent` | `MixinNetHandlerPlayClient` → GameState/AutoSprayonator | Tab 列表和 footer 快照 | 玩家列表/网络适配与纯解析；S2-T5/S2-T8 |

还需归一化 Forge tick、聊天、世界 load/unload、连接/断线、GUI、渲染、实体死亡和键盘事件。领域层不得订阅 Fabric/Minecraft 类型。

## Mixin/旧注入对齐

以下清单覆盖 `mixins.farmhelperv2.json` 的全部条目。结论是迁移意图，不授权在 S0-T2 创建 Mixin。

| 上游 Mixin/Accessor | 2.10.0 用途 | 结论与目标模块 |
| --- | --- | --- |
| `block.IBlockAccessor` | 修改 block bounds | 待后续决策；若保留作物 hitbox 行为，先找公开 BlockState/Shape API；`platform`/S9-T3 |
| `MixinBlockCocoa`、`MixinBlockCrops`、`MixinBlockMushroom`、`MixinBlockNetherWart` | 增大作物碰撞/选择框 | 待后续决策；S2-T7 明确暂不处理增大 hitbox，禁止提前复制 |
| `MixinBlockRendererDispatcher` | Performance/Fast Render 下跳过部分渲染 | 保留性能意图，优先渲染 API/能力检测；S7-T14/S9-T3 |
| `client.EntityPlayerAccessor` | 访问玩家内部字段 | 用现代公开 API/`platform` adapter 替代；仍不足时才用窄 Accessor |
| `client.EntityPlayerSPAccessor` | 访问 lastReported 位置/旋转 | 用运动快照/网络适配替代；Failsafe 不直接读 Mixin |
| `client.MinecraftAccessor` | timer、leftClickCounter | 时钟改 `runtime`；攻击行为走 `control`；尽量删除 Accessor |
| `MixinChunk` | 产生方块变化事件 | 保留事件语义，优先 Fabric 回调；S2-T8 |
| `MixinEntityPlayerSP` | 产生 Motion Pre/Post | 保留旋转观察语义；公开回调不足时窄 Mixin；S2-T8 |
| `MixinKeyBinding` | 拦截物理按键/托管输入 | 重构为唯一输入所有者；避免全局覆盖 `isPressed`；S2-T2 |
| `MixinMinecraft` | 点击方块、Ungrab/focus 等多种行为 | 按职责拆到 `control/platform`；公开 API 优先；S2-T2/S7-T14 |
| `MixinMouse` | PiP/滚轮行为 | 能力检测后的窗口输入适配；S7-T14 |
| `MixinPlayerControllerMP` | 点击/破坏事件、cactus 特例 | 保留交互事件语义；Fabric callback/窄 Mixin；S2-T8 |
| `MixinScoreboard` | 容忍重复/缺失 team/objective 的 1.8.9 异常 | 不复制 1.8.9 bug workaround；现代版本只有证据表明需要时才适配 |
| `MixinSoundManager` | Failsafe 声音音量/静音调整 | 本地声音服务与失败隔离；S8-T4 |
| `fml.MixinFMLHandshakeMessage` | 从 FML 握手 mod list 隐藏 FarmHelper | **已确认删除**；Forge/FML 遗留且不属于保留行为 |
| `gui.AccessorGuiEditSign` | 自动输入/确认 Sign（Rancher speed 等） | 用现代 Screen/Menu 适配，必要时窄 Accessor；S2-T6/S7-T6 |
| `gui.IGuiPlayerTabOverlayAccessor` | 读取 Tab header/footer | Tab 列表用现代公开 player-list API；26.1.2 footer 无公开 getter，保留一个只读 footer Accessor，唯一消费者为有界 GameState parser 输入；S2-T5 |
| `MixinGuiContainer` | 绘制后与库存按键事件 | Screen callback/adapter；S2-T6/S2-T8 |
| `MixinGuiDisconnected` | AutoReconnect 倒计时和按钮 | 保留本地重连行为，使用现代 Screen；S6-T7/S7-T13 |
| `MixinGuiMainMenu` | 显示 Welcome GUI | 不属于确认删除；保留本地安全提示意图与否待 S8 UI 决策，禁止外部控制 |
| `MixinGuiMultiplayer` | Proxy UI/连接接管 | **已确认删除** Proxy 分支；普通连接不需替代 |
| `MixinNetHandlerPlayClient` | 粒子、物体、Tab/footer 事件 | 保留事件语义，优先 Fabric/公开 API；S2-T8 |
| `MixinNetworkManager` | Proxy 连接接管、收/发包和计分板信号 | 删除 Proxy 接管；只在必要范围保留只读事件适配；S2-T8/S8-T5 |
| `MixinChunkProviderClient` | Chunk load 事件 | 只有导航/快照需要时保留；S2-T8 |
| `PathfinderAccessor` | 调用原版 `PathFinder.createEntityPathTo` | 封装在 `navigation`，依赖策略由 S5-T1 决定 |
| `MixinActiveRenderInfo`、`MixinEntityRenderer`、`MixinRenderManager` | Freelook 相机 yaw/pitch/距离 | 保留 Freelook 意图，优先现代相机事件；S7-T14/S9-T3 |
| `MixinEffectRenderer` | PerformanceMode 跳过破坏粒子 | 保留性能意图并做能力检查；S7-T14 |
| `MixinModelBiped` | Freelook 时模型头部/身体旋转 | 保留视觉一致性，必要时最小渲染注入；S7-T14/S9-T3 |

`transformer/FMLCore`、`NetworkManagerTransformer`、`Tweaker` 不是 Mixin 配置项，但同属 Forge/LaunchWrapper 注入层，已确认在 Fabric 迁移中删除。

## 资源对齐

| 资源类型 | 2.10.0 清点 | 状态/用途 | Fabric 映射与任务 |
| --- | ---: | --- | --- |
| Failsafe movement | 51：`BEDROCK_CHECK` 18、`DIRT_CHECK` 8、`ITEM_CHANGE` 4、`ROTATION_CHECK` 8、`TELEPORT_CHECK` 13 | 保留；解析校验后通过托管输入/旋转播放 | `failsafe/control`，S6-T2 |
| 本地声音 | 4：`AAAAAAAAAA.wav`、`loud_buzz.wav`、`metal_pipe.wav`、`staff_check_voice_notification.wav` | 保留为本地 Failsafe 声音；加载失败不得影响 tick | `platform`/S8-T4 |
| HUD/作物纹理 | `textures/gui` 26 个 PNG，覆盖 BPS、profit/runtime、普通/附魔作物及 Greenhouse 作物 | 保留资源类型；S8-T1 核对实际引用，未引用文件不得被误当行为 | `hud/platform`，S8-T1/S8-T5 |
| 图标 | `icon-mod/icon.png`、`icon-mod/rat.png` | `icon.png` 为 mod UI 图标；`rat.png` 被本地系统通知使用，保留本地用途 | `platform/ui`，S8-T4 |
| `textures/gui/test.png` | 上游源码无引用 | 待后续资源审计；不是已确认删除项 | S8-T5/S9-T5 |
| `mcmod.info` | Forge metadata | 替换为 `fabric.mod.json`，不复制 | `platform`，S8-T5 |
| `mixins.farmhelperv2.json` | 1.8.9 Mixin 清单 | 仅作证据；按上表逐项重新论证 | S2-T8/S9-T3 |

上游没有语言包。所有新 UI 文案应由项目自有资源/代码管理，不得依赖 OneConfig 资源。

## 配置组对齐

上游 `FarmHelperConfig` 约 2900 行，使用 OneConfig 静态字段和运行时依赖隐藏。新配置必须使用稳定 ID、schema version、默认值、范围校验和显式依赖；不导入旧 `/farmhelper/config.json`。

| 上游配置组 | 字段清单/代表字段 | 状态 | 新模块/任务 |
| --- | --- | --- | --- |
| General / macro | `macroType`、`alwaysHoldW`、`holdLeftClickWhenChangingRow`、`customFarmingSpeed`、`farmingSpeed` | 保留 | `config/macro`，S1-T2、S3-T3、S4-T8 |
| Rotation | `rotateAfterWarped`、`rotateAfterDrop`、`dontFixAfterWarping`、`customPitch/customPitchLevel`、`customYaw/customYawLevel` | 保留 | `config/control`，S2-T3、S8-T2 |
| Rewarp / spawn | `rewarpList`、`highlightRewarp`、spawn X/Y/Z/yaw/pitch/Plot、`drawSpawnLocation` | 保留 | `config/macro/ui`，S3-T2、S8-T2 |
| Keybinds | toggle、open GUI、Freelook、cancel Failsafe、tp infested Plot、Plot Cleaning | 保留并重建键位录制 | `config/ui/control`，S1-T4、S8-T2 |
| Macro delays | 换行、Jacob 换行、旋转、寻路旋转、Pest、GUI、Plot Cleaning、rewarp 的 base/randomness | 保留并校验范围 | `config/runtime`，各使用方任务、S8-T2 |
| AntiStuck/Desync | `tmpAntiStuckEnabled`、`antiStuckTriesUntilRewarp`、`checkDesync`、`desyncPauseDelay` | 保留；重命名临时 ID 时提供 schema 映射 | `feature/config`，S5-T5/S8-T2 |
| Performance/comfort | `performanceMode`、`fastRender`、max FPS、`muteTheGame`、`autoUngrabMouse`、`pipMode`、窗口标题 | 保留可支持的本地行为 | `feature/platform`，S7-T14/S8-T2 |
| Crop hitbox/pingless | `increasedCocoaBeans`、`increasedCrops`、`increasedNetherWarts`、`increasedMushrooms`、`pinglessCactus` | 待后续决策；S2-T7 明确不提前处理增大 hitbox | S2-T7/S9-T3 |
| Failsafe general/detection | popup、alt-tab、反应开关/停止延迟、world/evacuate/reconnect/guest、teleport/rotation/knockback/BPS 阈值、合成测试类型 | 保留本地配置；开发触发器受保护 | `failsafe/config`，S6、S8-T2 |
| Failsafe notifications | 每个检测器的 `notify`、`alert`、`autoAltTab` | 保留本地输出 | `failsafe/platform`，S6-T8/S8-T4 |
| Failsafe tag-everyone | 每个检测器的 `tagEveryone...` | **已确认删除**，只服务 Discord/Webhook | S8-T2/S8-T5 |
| Failsafe messages | `sendFailsafeMessage`、Jacob/continue/rotation/teleport/knockback/bedrock/dirt 自定义消息与概率 | 保留游戏内反应；不得转发远程 | `failsafe/config`，S6-T8/S8-T2 |
| Clip capturing | `captureClipAfterFailsafe`、类型、快捷键、延迟 | 保留可支持的本地快捷键动作；`captureClipAfterGettingBanned` 在 2.10.0 只有配置依赖、没有运行时读取，且删除 `BanInfoWS` 后没有可信触发信号，不得宣称已保留该行为 | `failsafe/platform`，S6-T8/S8-T2 |
| Failsafe sound | sound type、Minecraft/custom sound、次数、音量、最大化游戏音量 | 保留本地 | `platform/config`，S8-T4 |
| Restart after failsafe | enable、delay、always Garden | 保留并由单一重连所有者执行 | `failsafe/feature`，S6-T8/S7-T13 |
| Banwave | checker、pause/leave、阈值类型/值、reconnect delay、Jacob 抑制 | 本地降级；远程阈值来源删除，无本地证据时禁用 | `failsafe/config`，S6-T6/S8-T2 |
| Auto Cookie | `autoCookie` | 保留；购买/移动/消费的配置入口必须随 S7-T7 状态机一起迁移 | `feature/config`，S7-T7/S8-T2 |
| Scheduler/LeaveTimer | farm/break 时间与随机性、Jacob、物品栏、断线、等待 rewarp、reset、leave time | 保留 | `feature/runtime`，S7-T3 |
| Jacob/Pet | PetSwapper、各作物上限、Failsafe action、当前作物限制 | 保留 | `feature/failsafe`，S6-T6/S7-T6 |
| Visitors | enable、最少 Visitor、AutoSell、Jacob、资金/花费、AFK、旅行、触发、full inventory action | 保留 | `feature`，S7-T9 |
| Visitor filters | 名称白/黑名单、action、稀有度及各 rarity action | 保留 | `feature/config`，S7-T9/S8-T2 |
| Pests Destroyer | enable、数量阈值、GUI delay、sprint/AOTE、Plot 传送、Jacob、AFK、on-track FOV/时间/卡住、keybind | 保留 | `feature/navigation`，S7-T10/S7-T11 |
| Pest equipment/render | armor/equipment 前后槽、rewarp/spawn/ESP/tracer/Plot 颜色 | 保留本地 | `feature/platform`，S7-T10/S8-T2 |
| Pest logs | 本地 notification 可保留；`sendWebhook...`、`pingEveryone...` 删除 | 拆分保留/删除 | S7-T10/S8-T4/S8-T5 |
| Pest Farmer | enable、mousemat、装备槽、等待、spawn、equipment、击杀/鱼竿/阈值 | 保留 | `feature`，S7-T11 |
| Plot Cleaning | `plotCleaningHelperKeybind`、`autoChooseTool`、旋转 base/randomness | 保留 | `feature/config/control`，S7-T12/S8-T2 |
| Auto Pest Exchange | enable、Jacob/相关性/旅行/目标/提前量/最少 Pest、Desk 坐标、highlight、本地日志 | 保留 | `feature/navigation`，S7-T8 |
| Auto God Pot | enable、Backpack/Storage、Bits、AH | 保留支持的来源路径 | `feature`，S7-T7 |
| Auto Sell | enable、market、Sacks/placement、Jacob、满包时间/比例、rune/dead bush/hoe/vinyl/custom items | 保留 | `feature/control`，S7-T5 |
| Auto Repellent | enable、类型、Jacob | 保留 | `feature`，S7-T8 |
| Auto Sprayonator | enable、材料、延迟、自动购买/数量 | 保留 | `feature`，S7-T8 |
| Auto Composter | enable、Jacob、资金/花费/资源阈值、AutoSell、旅行、Desk 坐标、highlight、本地日志 | 保留 | `feature/navigation`，S7-T8 |
| HUD/statistics | streamer、四 HUD、Garden 外显示、RNG/profit、reset、24h/7d/30d/lifetime/title | 保留本地 | `hud/config`，S7-T2/S8-T1/S8-T2 |
| Debug | debug keybinds/mode/new fly/DebugHUD | 保留但受开发模式保护 | `ui/hud`，S8-T3 |
| Experimental fastbreak | fastBreak、randomization/chance/speed、Banwave/Jacob 抑制 | 待后续决策，不是已确认保留项 | S8-T5/S9-T3 |
| Misc experimental | `autoSwitchTool`、cultivating profit、Jacob current crops、PDOTT logs | 保留与已批准组件直接相关的本地子项 | 对应 S6/S7/S8 任务 |
| Config metadata | 上游 `configVersion=6`、`shownWelcomeGUI2` | 不导入旧配置；新 schema 自己版本化；Welcome 提示待 UI 决策 | S1-T2/S8-T2 |

### 已确认删除的配置字段

- Proxy：`proxyEnabled`、`proxyAddress`、`proxyUsername`、`proxyPassword`、`proxyType`。
- Analytics：`sendAnalyticData`，以及 BanInfoWS 统计/上传所需的隐式字段和文件格式。
- Discord Webhook：`enableWebHook`、`sendLogs`、`sendStatusUpdates`、`statusUpdateInterval`、`sendVisitorsMacroLogs`、`pingEveryoneOnVisitorsMacroLogs`、`sendMacroEnableDisableLogs`、`sendFHBanLogs`、`webHookURL`、Pest 的 `sendWebhook...`/`pingEveryone...` 字段。
- Discord/JDA 远程控制：`enableRemoteControl`、`discordRemoteControlToken`、`discordRemoteControlAddress`、`remoteControlPort` 及相关 info 字段。
- Updater：`autoUpdaterDownloadBetaVersions` 和 update action。
- OneConfig：所有注解、Page/HUD 存储、依赖表达和旧 JSON 路径；只保留字段所代表且已批准的产品行为。
- Failsafe `tagEveryone...`：仅为 Webhook 服务，全部删除。

## 跨功能依赖与所有权

| 链路 | 上游耦合 | 新契约 |
| --- | --- | --- |
| Macro → GameState/扫描 → 输入/旋转 → rewarp | 宏直接读取单例和 Minecraft | 宏只读快照；控制服务独占输入/旋转；rewarp 有确认和取消。 |
| Failsafe → Macro/Feature → MovRec → 本地提醒/重启 | Manager 直接暂停单例并可能发 Webhook | `failsafe` 拥有暂停 token；录制使用控制所有权；输出只本地。 |
| Scheduler → Macro/AutoSell/rewarp/连接 | tick 内隐式选择动作 | 显式依赖和冲突；离开前释放控制。 |
| Visitors → AutoSell/AutoBazaar/Compactor/导航 | 多个 Feature 共享 Menu 和路径 | 单一物品栏/导航所有者；每步验证实体、Menu、Slot、Offer。 |
| AutoBazaar → Visitors/AutoComposter/AutoSprayonator | 三个调用方共享交易状态；`AutoCookie` 自带另一套 Bazaar 状态 | 统一交易所有权与事务验证；AutoCookie 不得并行打开第二套 Menu 流程。 |
| AutoCookie/AutoGodPot → 物品栏/Buff/购买来源 | AutoCookie 内嵌 Bazaar；AutoGodPot 使用 Backpack/Storage、Bits Shop、Auction House | 复用共享 Menu/交易服务，保持来源区分、花费上限和 Buff 更新确认。 |
| Garden upkeep → AutoBazaar/AutoSell/导航/Menu | 四个 Feature 可互相重叠 | FeatureManager 声明依赖/冲突；共享服务可取消。 |
| AutoPestExchange ↔ Visitors、AutoSell ↔ Visitors/AutoComposter | 上游用例外列表允许调用方在暂停集合中共存 | 显式父子调用/冲突关系；子流程结束不得提前恢复宏。 |
| PestFarmer → Scheduler/AutoWardrobe/PestsDestroyer → 导航/装备/实体粒子 | 只在 Scheduler farming 时启动，多阶段交接且共享控制 | 明确调度 gate 和交接 token；中断恢复装备和 farming。 |
| AutoReconnect → Scheduler/UngrabMouse | Scheduler 休息断线保留；重连后重新应用鼠标释放状态 | 单一重连所有者；区分计划断线，恢复窗口/鼠标状态。 |
| Freelook ↔ UngrabMouse | 进入/退出相机模式时临时重抓并恢复鼠标 | 用组合所有权恢复进入前状态，异常路径也清理。 |
| FullInventoryFailsafe → AutoSell | Failsafe 与 Feature 可能竞争 | 由策略决定暂停/AutoSell，禁止双重拥有 Menu。 |
| Rotation/Teleport Failsafe ↔ navigation/rewarp | 预期移动缓存分散在执行器 | 控制/导航发布带所有者的预期事件，检测器只读。 |
| StatusHUD/DebugHUD → 全部单例 | HUD 可直接窥视可变状态 | 只读、限量 ViewModel，不改变业务状态。 |

所有 `stop`、世界丢失、断线、意外 Screen、手动冲突和异常路径都必须取消：输入、旋转、Menu 事务、导航、调度任务和 Feature 暂停所有权。

## 已确认删除矩阵

| 删除区域 | 2.10.0 证据 | 必须删除的边界 | 后续负向检查 |
| --- | --- | --- | --- |
| `BanInfoWS` | `feature/impl/BanInfoWS.java`；连接已删除的外部分析 WebSocket，接收 banwave，上传 ban/failsafe/统计 | 类、注册、连接、ban/分析上传、`FailsafeManager.banInfoWSLastFailsafe`、StatusHUD ban stats、相关配置 | 源码/依赖/配置/HUD 搜索 `BanInfoWS`、analytics URL、ban upload 为零 |
| 分析与封禁数据上传 | `sendAnalyticData`、BanInfoWS stats 文件/消息 | 所有 UUID/会话/ban/failsafe/使用数据远程发送 | 仅允许本地 UsageStats；无遥测目的地 |
| 外部分析 WebSocket | BanInfoWS 与 `org.java-websocket` | WebSocket 客户端、重连、消息协议和依赖 | `Java-WebSocket` 依赖仅在无其他保留用途时完全移除；计划要求最终移除 |
| Discord Bot/JDA | `remote/DiscordBotHandler.java`、`remote/event/**`、`remote/command/discordCommands/**` | JDA 依赖、Bot token、interaction、waiter、Discord command | 源码、依赖、配置搜索 `JDA`/Discord remote 为零 |
| Discord Webhook | `config/struct/DiscordWebhook.java`、`LogUtils.webhook*`、Webhook 配置 | 所有 Webhook 请求、URL、ping/tag everyone、远程日志分支 | 保留日志只能是本地聊天/文件/系统通知 |
| 远程 WebSocket 控制 | `remote/WebsocketHandler.java`、`remote/struct/WebsocketClient/Server`、`remote/command/commands/**` | 远程 autosell/disconnect/info/reconnect/screenshot/send command/set speed/toggle | 无监听端口、token、remote command/config |
| 内置更新器 | `gui/AutoUpdaterGUI.java`、`FarmHelperConfig.checkForUpdate()`、`/fh update` | UI、版本网络请求、命令、beta 配置 | 不创建替代 updater；发布流程不受 mod 控制 |
| Proxy | `feature/impl/Proxy.java`、`gui/ProxyManagerGUI.java`、`MixinNetworkManager`/`MixinGuiMultiplayer` 分支 | 代理配置、凭据、连接接管、UI | 网络层无 SOCKS/HTTP proxy 逻辑或凭据 |
| DevAuth | `me.djtheredstoner:DevAuth-forge-legacy` 与 Azure Maven | 生产依赖、仓库、启动配置 | 依赖报告和仓库搜索为零 |
| OneConfig | `FarmHelperConfig extends Config`、注解/Pages/HUD、命令工具、wrapper/tweaker | OneConfig 依赖、LaunchWrapper wrapper、旧配置导入、UI/命令实现 | 只存在项目 JSON、原生 Screen 和 Fabric 指令 |
| Forge/FML/LaunchWrapper/coremod/Transformer | build 的 Forge/tweakClass、`transformer/**`、`mcmod.info`、FML Mixin | 旧生命周期、EventBus、Transformer/Tweaker、FML metadata | 以 Fabric API/最小 Mixin 替换；S8-T5 最终零遗留 |
| FML mod-list 隐藏 | `MixinFMLHandshakeMessage` 删除 mod tag | 整个 Mixin | 不在 Fabric 建立等价隐藏行为 |

“删除远程输出”不等于删除本地声音、系统通知、窗口前置、聊天诊断或本地日志；这些在 S8-T4 迁移并加脱敏、限流和失败隔离。

## 上游类包清点

以下包级清单用于确保后续审查者不必重新遍历整个仓库：

- 入口/配置/指令：`FarmHelper`、`FarmHelperConfig`、`CustomFailsafeMessagesPage`、`FailsafeNotificationsPage`、`Rewarp`、`DiscordWebhook`、`FarmHelperMainCommand`、`RewarpCommand`。
- 事件：本文件“事件与信号对齐”列出的 16 个事件源文件（表中合并登记为 15 行）。
- Failsafe：`Failsafe`、`FailsafeManager` 和本文件列出的 16 个实现。
- Feature：`FeatureManager`、`IFeature`；保留实现均已在 Feature 表登记；已确认删除 `BanInfoWS`、`Proxy`。
- Handler：`BaritoneHandler`、`GameStateHandler`、`MacroHandler`、`RotationHandler`，分别映射 `navigation/runtime/macro/control`。
- HUD：四个类全部已登记。
- Macro：`AbstractMacro` 和八个实现全部已登记。
- Pathfinder：`FlyNodeProcessor`、`FlyPathFinderExecutor`，映射 S5-T1～S5-T4。
- GUI：`AutoUpdaterGUI`、`ProxyManagerGUI` 已确认删除；`WelcomeGUI` 仅本地安全提示，保留与否待 S8 UI 决策。
- Remote：`DiscordBotHandler`、`WebsocketHandler`、两套 remote command、interaction、struct、util、waiter 全部已确认删除。
- Transformer：`FMLCore`、`NetworkManagerTransformer`、`Tweaker` 全部作为旧平台层删除。
- Utility：`APIUtils`、`AngleUtils`、`AvatarUtils`、`BlockUtils`、`CropUtils`、`FailsafeUtils`、`InventoryUtils`、`KeyBindUtils`、`LogUtils`、`MarkdownFormatter`、`OldRotationUtils`、`PlayerUtils`、`PlotUtils`、`ReflectionUtils`、`RenderUtils`、`ScoreboardUtils`、`TablistUtils`；其中只迁移保留行为需要的纯算法/本地输出，远程/反射/旧 API 分支不得照搬。
- Helper：`AudioManager`、`BaritoneEventListener`、`Clock`、`FifoQueue`、`KeyCodeConverter`、`PlayerSimulation`、`Rotation`、`RotationConfiguration`、`SignUtils`、`Target`、`TickTask`、`Timer`；分别映射本地声音、导航、时钟/队列、旋转、Menu 和测试辅助，必须去 OneConfig/Forge/Minecraft 1.8.9 耦合。
- Mixin/Accessor：全部已在 Mixin 表登记。

## GameState/解析快照要求

上游 `GameStateHandler` 读取世界生命周期、聊天、Tab、计分板、footer、tick 和收包，并维护以下共享信息；新实现必须拆为原始数据适配与纯解析器：

- Location：Garden、Hub、Lobby、Limbo、Teleporting、Unknown 以及其他 SkyBlock 区域；领域层至少可靠区分计划要求的 Garden/Hub/Lobby/Barn/Limbo/传送中/未知。
- Movement：前/后/左/右可走、`dx/dy/dz`、停止窗口、rewarp 条件。
- Buff：Cookie、God Pot、Pest Repellent、Pest Hunter、Sprayonator、Composter，状态为 `ACTIVE/FAILSAFE/NOT_ACTIVE/UNKNOWN`。
- Economy：purse、previous purse、bits、copper。
- Garden：当前 Plot、感染 Plot、总/当前 Plot Pest、Vacuum、organic matter、fuel、Guest。
- Jacob：当前/下一作物、计数、剩余时间、奖牌、是否在竞赛。
- Connection/server：server IP、server closing seconds、世界 load/unload、位置切换。
- Item：当前 speed、cultivating、Slot 更新和物品栏摘要。

缺行、格式变化、无玩家/世界、未加载 Chunk 和未知值必须显式表达，禁止用合法零值冒充“缺失”。

## 自动与人工验证索引

S0-T2 本身只产生文档；后续阶段应把这些检查映射回相应条目：

1. 宏：14 个模式全部能选择到八个类；每类覆盖 enable/pause/resume、Screen/rotation block、换行/掉层、rewarp、世界丢失和 stop。
2. Failsafe：16 个检测器各注册一次，覆盖 trigger/cancel/react/finish/reset/restart；Banwave 明确验证本地降级，不连接远程。
3. Feature：逐项验证独立开关、依赖、冲突、暂停所有权、失败恢复和全局 stop；重点链路为 Scheduler→AutoSell、Visitors→AutoBazaar、PestFarmer→PestsDestroyer。
4. 导航：坐标、实体跟随、平滑、未加载 Chunk、目标消失、timeout、Screen/世界变化和取消。
5. Menu：每步校验 Screen、Slot、物品、价格/上限和事务结果；任何变化取消旧操作。
6. 资源：51 个 movement 全部可解析；4 个声音可加载且失败隔离；HUD 纹理引用与 JAR 内容一致。
7. HUD/UI/指令：四 HUD、全部保留配置组、搜索/重置/键位录制、alias、开发保护、缩放和缺失数据。
8. 删除项：依赖报告、源码、资源、配置、指令、metadata 和 JAR 搜索必须证明远程/分析/Discord/Webhook/updater/Proxy/DevAuth/OneConfig/Forge 遗留不存在。
9. 全局清理：手动 stop、Failsafe、断线、世界卸载、意外 Screen、异常和退出都释放所有控制与任务。

## S0-T2 结论

- 八个宏类、14 个宏模式、16 个 Failsafe、全部已确认保留 Feature、四个 HUD、指令组、16 个自定义事件类、全部 Mixin/Accessor、资源类型和重要配置组均已登记并映射到计划模块/任务。
- 所有 `progress.md` 已确认删除区域都在删除矩阵中明确标记，且 Banwave 的远程信号缺口已记录为诚实本地降级。
- 已识别关键跨功能依赖、所有权/取消边界和人工验证点。
- 本文没有复制上游实现，也没有开始 Stage 1。
