# LiveLoop 开发驾驶舱 — 使用说明

**目标**：AI 编辑 mod/代码 → 游戏内即时生效 → 指令直达场景 → 结构化反馈。全程不重启游戏。

## 启动

1. 双击根目录 `Miko_LiveLoop.bat`（= 正常游戏 + 热更新 agent + SimArena mod）
2. 启动器窗口点"启动游戏"（仅此一次点击；之后的热循环不再需要任何界面操作）
3. agent 控制端口：`http://127.0.0.1:17999`

## 核心 API（全部 JSON，仅监听 127.0.0.1）

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/status` | GET | JVM/热更新能力/当前游戏状态/bridge 注册情况 |
| `/api/scenarios` | GET | 场景清单（`mods/SimArena/scenarios/*.json`） |
| `/api/variants` | GET | 当前全部舰船 variant id（含 mod） |
| `/api/run` | POST `{"scenario":"midweight_skirmish"}` | 从任意界面直达该场景战斗（自动部署、双方 AI） |
| `/api/goto` | POST `{"where":"title"}` | 回标题画面（"combat"=重跑上个场景） |
| `/api/end` | POST | 强制结束当前战斗（写结果 CSV） |
| `/api/reload-specs` | POST | 热重载 variant 数据文件（改 `.variant` 后用） |
| `/api/redefine` | POST | 立即编译 `mods/SimArena/src` 并热替换已加载类 |
| `/api/stats` | GET | 实时/最近一场战斗统计（双方输出/承伤/逐舰状态） |
| `/api/stats.csv` | GET | 最近一场结果的 CSV |
| `/api/log` | GET | agent 日志（编译/替换/桥接事件） |
| `/api/classes?q=xxx` | GET | 查询 JVM 已加载类（确认目标类名） |

## 两条热反馈环

### 代码热更新（方法体级，~2 秒生效）
1. 直接编辑 `mods/SimArena/src/simarena/*.java`（如 `TestFormula.banner()`）
2. 保存 → agent 的文件监视器自动触发"编译+热替换"；也可手动 `POST /api/redefine`
3. `POST /api/run` 重开战斗 → 新代码立即生效（HUD 横幅会显示 `TestFormula.banner()` 的返回值，肉眼可验证）
- 限制：标准 JVM 的 redefine 只支持**方法体内部修改**；新增方法/字段需要阶段 2 的 DCEVM 运行时（`/api/status` 会报告当前能力）
- 核心类同样可以热替换：把改好的字节码放进 `mods/SimArena/build/classes`（class 名与目标一致）再 `/api/redefine`

### 数据热更新（场景/舰船配置）
- 编辑 `mods/SimArena/scenarios/*.json` → `POST /api/run` 直接用新参数开战
- 编辑任意 mod 的 `.variant` 文件 → `POST /api/reload-specs` → `/api/run`
- （devMode 已开启，游戏内 Ctrl+A=重读 settings.json，Ctrl+B=热重载并原地重建舰船）

## 场景 JSON 格式

```json
{
  "id": "paragon_vs_onslaught",
  "map": { "minX": -10000, "maxX": 10000, "minY": -7500, "maxY": 7500 },
  "fleets": {
    "player": { "tag": "A", "goal": "ATTACK", "commandPoints": 300,
                "ships": [ { "variant": "paragon_Elite", "count": 1 } ] },
    "enemy":  { "tag": "B", "goal": "ATTACK", "commandPoints": 300,
                "ships": [ { "variant": "onslaught_Standard", "count": 2 } ] }
  },
  "env": { "nebula": [[x,y,r]], "objectives": [[x,y,"nav_buoy"]],
           "asteroids": { "cx":0, "cy":0, "width":14000, "count":80 } }
}
```
- variant 清单：`GET /api/variants`；舰载机 variant 以 `_wing` 结尾（或写 `"wing":true`）
- 战斗结束条件：一方全灭（或 `/api/end` 强制结束）→ 结果写入 `mods/SimArena/results/*.csv`

## 文件构成

| 位置 | 内容 |
|---|---|
| `_work/explore/liveloop/agent/` | 热更新 agent 源码（`build_agent.sh` 构建 → `hotswap-agent.jar`） |
| `mods/SimArena/` | 驾驶舱 mod（`src/` 源码、`scenarios/` 场景、`results/` 战报、`jars/` 构建产物） |
| `_work/explore/liveloop/build_simarena.sh` | mod 构建脚本（改了源码也可手动重建 jar） |
| `_work/explore/liveloop/Miko_LiveLoop.txt` | 启动参数（= Miko_Simple.txt + agent；agent 排在 Prepatcher **之前**，后者强制要求最后） |

## 已知边界

- 战斗进行中发出的 `/api/run` 会排队到回到标题画面后执行（引擎不能在遍历中途销毁）
- 首次进游戏需在启动器点一次"启动游戏"（跳过启动器的直启路径在调查中，属后续优化）
- `fr.agent`（Fast Rendering）会对 `starfarer_obf.jar` 做 SHA-256 记录——我们已在用去混淆 jar，属预期日志，非错误
- 端口若被占用（如代理软件抢占 789x 段），改 `Miko_LiveLoop.txt` 里的 `port=17999` 即可

## 2026-11-17 端到端验证记录（全部通过）

- 自动启动（liveloop_auto.ps1）：启动器合成点击 → 激活伪装防冻结 → 就绪判定（commandsAvailable + Title 状态，不依赖音乐标记）→ PASS 后进入保活循环
- `/api/run` 从标题直达 AI-vs-AI 战斗，无部署框（engine.setShowDeploymentDialog(false) + 自调 admiralAI.preCombat）
- 战斗结束自动写 `mods/SimArena/results/<ts>_<scenario>.csv`（逐舰 dealt/taken/存活/摧毁）
- 热更新实测：编辑 `TestFormula.java` + `ArenaEnginePlugin.java` → watcher 编译 → 8 类 redefine 0 错误 → 战斗内 HUD 横幅即时变为 `v2-HOTFIXED`（截图 battle_v2.png / final_v2.png）

## 已知限制（下一迭代）

1. 战斗结束的结算对话框（惨重的失败/胜利）需手动点"继续"——对话框模态期间泵不执行队列命令（注入点待加到插件分发器 ObfC0305 上即可穿透）
2. CSV 偶有一行 side=100 的战斗机/残骸记录（跟踪过滤待完善）
3. 场景未调用 setFleetTagline（结算页显示占位文本，视觉小瑕疵）
4. 重建 jar 前必须先关游戏（-javaagent 锁文件）；热路径不受影响

## 2026-11-17 UI 热调探索结论

需求：玩家停在某个 UI 页面时，AI 改代码即时看到效果。

### 分层答案
- **SimArena 自有 UI/战斗 HUD**：✅ 已验证——方法体热替换下一帧生效（v2-HOTFIXED 横幅）
- **游戏核心 UI（yGuard 混淆类）**：机制通（ redefine 下一帧生效），但"改源码→重编译"被堵死：
  - `screen_class.sh` 筛查工具实测：ObfC1049/1048（部署框）、ObfC0351（结算框）、CodexDialog、
    TitleScreenState、PersonNameStore 全部 FAIL——yGuard 把包/类/字段重命名成关键字和
    非法标识符（`class` 包、`loading.String` 影子类、`do.String$new` 字段），javac 无法声明或引用
  - 全库 1298 类中 CFR 标记干净的仅约 346 个，且跨类引用仍可能失败（逐类实测为准）
  - Vineflower 反编译器（vineflower.jar）质量优于 CFR，单类反编译：
    `unzip -j starfarer_obf.jar <路径>.class -d vf_src && java -jar vineflower.jar vf_src/X.class vf_out`
    （整 jar 反编译会撞 Windows 260 路径上限，超长 OoOO 类名必炸）
- **数据层 UI 调整**（文本/提示/tips）：数据文件改动 + StarfarerStrings 重载可绕开编译问题（本地化场景最实用）

### 下一迭代路线（按性价比）
1. `/api/patch-const`：字节码常量补丁（颜色/偏移/阈值/字符串常量直接改，绕开编译，覆盖多数 UI 微调）
2. `/api/reload-strings`：StarfarerStrings + tips 数据热重载（本地化文本即时生效）
3. DCEVM/JBR（结构改动）+ 标识符映射管线（MCP 式，解锁全部类的源码级编辑）

## 2026-11-17 实战演示：主菜单"战役模式"→"任务"（已验证）

- 文本源头：汉化组把菜单文本直接补进 starfarer_obf.jar 的字节码常量池——
  全 jar 唯一命中 `title/ObfC0871.class`（各菜单标签各一个 `static final String` 的 ConstantValue）
- 新增工具：
  - `POST /api/patch-string {"class":...,"old":...,"new":...}` —— 运行中改 LDC 常量（方法体内字符串，下一帧生效）
  - 离线持久化（游戏关闭时运行）：`java -cp hotswap-agent.jar liveloop.agent.PatchString <jar> <内部类路径> <旧> <新>`
    （自动备份为 `<jar>.bak_patchstring`）
- ConstantValue 字段常量（本例）不走活体 redefine（已初始化类不重新 prepare，值不会变），
  必须走离线 jar 补丁 + 重启；重启后已验证主菜单显示"任务"（截图 menu_task.png）
- 查找类的方法：对 jar 全条目 grep 字节（`unzip -p <jar> <entry> | grep -qa <文本>`），
  本例 3030 条目中仅 ObfC0871 命中

## 2026-11-17 夜间子代理成果与集成验证（全部通过）

### A：DCEVM 结构性热更新（dcevm/dcevm.md）
- JBR 21.0.11（DCEVM 内置，需 `-XX:+AllowEnhancedClassRedefinition`，默认 false）装于 `dcevm/jbr/`
- `Miko_LiveLoop_DCEVM.txt/.bat`：与原参数文件仅 3 行差异；两个冷启动均一次 PASS（136s/143s 到就绪）
- 结构改动实测：给运行中已加载类新增字段+方法 → redefine 成功 → 新方法在战斗 HUD 被真实调用
  （横幅 DCEVM-OK:...:6 计数递增截图为证）；三个 javaagent 全保留（fr.agent 在 JBR21 正常）
- `dcevm_session.ps1` 会话脚本；游戏当前在 JBR/DCEVM 路线运行

### B：数据层文本热重载（datareload/datareload.md）
- 机制（javap 实证）：加载方法真名就叫 `super`（StarfarerStrings），title.String.loadTips()
  只加不清 → 重载需先 picker.clear()；"下一个提示"每次点击实时读池 → **重载后无需重开 UI**
- `POST /api/reload-strings`：快照+clear+重读+失败自动还原（坏 JSON 不会清空提示池）
- **集成实测**（本机，tips.json 编辑 → 重载）：tipCount 99→100→101→99 与文件编辑完全同步，
  文件 md5 还原一致。数据层文本热调闭环完成。

### 当前运行状态
游戏在 JBR/DCEVM 路线 + 保活循环中停在主菜单；TestFormula.java 保留 A 的实验性改动
（回滚方法见 dcevm.md §6.4）。

## 2026-11-17 jar 占用问题的工程解（已实现）

构建脚本改为**写 .next 暂存 + 自动晋级**：游戏运行时正式 jar 被锁定也能随时构建
（`hotswap-agent.jar.next` / `SimArena.jar.next`），`liveloop_auto.ps1` /
`dcevm_auto.ps1` 在游戏尚未启动的瞬间把 .next 晋级为正式名。流程：
构建（任意时刻，含游戏中）→ .next 暂存 → 下次启动前自动晋级 → 新代码随该次启动生效。
补充：已加载类的改动不需要等晋级（热 redefine 即时生效）；.next 只影响"尚未加载的类"
与"重启后"的代码版本。

### 生涯模式（CampaignState）的能力矩阵
- 泵已注入 CampaignState.processInput → 队列命令在生涯界面正常执行
- mod 方法体/结构改动：生涯中 redefine 立即生效（红线：别动存档序列化字段）
- 未加载类（如尚未进入战斗的战斗类）：编辑后显示 notLoadedYet，随 .next 晋级在下次启动生效
- 第三方 mod：非混淆 jar，Vineflower 反编译+重编译可行性高（screen_class.sh 换 CP 即可筛查）
