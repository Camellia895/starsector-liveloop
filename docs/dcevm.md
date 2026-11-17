# DCEVM 结构性热更新路线 — 实施与验证报告

日期：2026-11-17（凌晨会话）
目标：让运行中的 Starsector 0.98a-RC8 支持**对已加载类新增字段/方法后热替换**（标准 JVM redefine 只允许方法体修改）。
结论：**全部验证通过**。JBR 21.0.11 (b1163.125) 内置 DCEVM 经 `-XX:+AllowEnhancedClassRedefinition` 开启后，
对运行中已加载类的新增静态字段 + 新增静态方法 redefine 成功，且新方法/新字段在战斗 HUD 中被真实调用（截图计数器递增）。
游戏结束时保持运行（保活循环中）。

---

## 1. JBR 获取

| 项 | 值 |
|---|---|
| 版本 | **jbrsdk-21.0.11-windows-x64-b1163.125**（java -version：`JBR-21.0.11+1-1163.125-nomod (build 21.0.11+1-b1163.125)`） |
| 来源 | JetBrainsRuntime GitHub Release `jbr-release-21.0.11b1163.125` 的资产表 → JetBrains CDN：`https://cache-redirector.jetbrains.com/intellij-jbr/jbrsdk-21.0.11-windows-x64-b1163.125.tar.gz`（GitHub 仓库实际为 `JetBrains/JetBrainsRuntime`；release 正文表格给出 CDN 直链，GitHub 上该 release 本身未挂 assets） |
| 校验 | sha512 官方 checksum 匹配：`0442a85f…4022`（见 `tmp/jbrsdk.tar.gz.checksum`） |
| 安装位置 | `_work/explore/liveloop/dcevm/jbr/`（bin/java、javac、jstack、jcmd 等齐全，jbrsdk 变体=完整 JDK，LiveCompiler 需要的 in-JDK 编译器可用） |
| 备注 | 选 21 而非 25：与既定任务一致（JBR 21 DCEVM 路线），且 `--release 17` 编译 + 游戏兼容性最稳 |

## 2. DCEVM 确认（如何确认）

1. **flag 存在**：`jbr/bin/java -XX:+PrintFlagsFinal -version` 显示
   `bool AllowEnhancedClassRedefinition = false {product}`（JBR 的 DCEVM 总开关，**默认 false，必须显式开启**）
   与 `bool AllowRedefinitionToAddDeleteMethods = false {product}`（本路线未用它，DCEVM 已覆盖方法增删）。
2. **离线探针**（`dcevmsanity/`，同一个 Instrumentation.redefineClasses API、同一类结构变更：新增 `public static int dcevmCounter` + `public static String dcevmEcho(String)`）：见 `ev_sanity_probe.txt`
   - JBR21 默认（无 flag）：`FAILED … attempted to change the schema (add/remove fields)` → STRUCTURAL-REJECT
   - JDK28+13（现生产路线，对照组）：同样 `schema (add/remove fields)` → STRUCTURAL-REJECT
   - JBR21 + `-XX:+AllowEnhancedClassRedefinition`：`redefine: OK`，新方法返回 `DCEVM-OK:x:1`，新字段可读写 → STRUCTURAL-ACCEPT
3. **游戏进程内确认**：`jcmd <pid> VM.flags` 输出含 `-XX:+AllowEnhancedClassRedefinition`（`ev2_jcmd_flags.txt`）；
   `jcmd <pid> VM.command_line` 确认实际 jvm_args 全量来自 DCEVM 参数文件（`ev2_jcmd_cmdline.txt`）；
   `/api/status` 的 `java` 字段 = `OpenJDK 64-Bit Server VM 21.0.11`。

## 3. 启动文件

### 3.1 `Miko_LiveLoop_DCEVM.txt`（相对 `Miko_LiveLoop.txt` 的完整差异）
```
2a3
> -XX:+AllowEnhancedClassRedefinition          ← 新增：DCEVM 总开关
6d6
< -XX:+UseCompactObjectHeaders                 ← 裁剪：JDK28(Lilliput) 专属
9d8
< -XX:+HotCodeHeap                             ← 裁剪：JDK28 专属
```
其余**全部保留**，逐一在 JBR 21 上预验证过：
- `-XX:CompilerDirectivesFile=..\mikohime/.rouge_owo` **保留**（JBR 解析成功：`2 compiler directives added`；诊断 flag，位于 UnlockDiagnosticVMOptions 之后，顺序正确）
- `-XX:+AlwaysPreTouchStacks` **保留**（JBR 21 存在，诊断 flag，已由前置 unlock 放行）
- `--enable-native-access=ALL-UNNAMED`、`--enable-final-field-mutation=ALL-UNNAMED` **保留**（21 上实测可解析）
- `-Xlog:async`、`-XX:+ExtensiveErrorReports`、`-XX:+ErrorLogSecondaryErrorDetails`（后者为诊断）等均可用
- 备注：`UseCompactObjectHeaders`/`HotCodeHeap` 在该 JBR build 里其实也能解析（JetBrains 有回填），但 21 下均为默认 off、无意义，按任务要求裁剪
- 未发现需要 `-XX:-AOT*` 裁剪的行（基础文件里没有 AOT 系列 flag）

### 3.2 `Miko_LiveLoop_DCEVM.bat`（`_work/explore/liveloop/` 下，CRLF）
```bat
@ECHO OFF
@TITLE LiveLoop DCEVM Arena
CD /D C:\game\StarSector.v0.9.8a-RC8\starsector-core

"..\_work\explore\liveloop\dcevm\jbr\bin\java.exe" @..\_work\explore\liveloop\Miko_LiveLoop_DCEVM.txt
if errorlevel 1 pause
```
（CWD=starsector-core，@argfile 相对路径语义与原路线一致；因 bat 位于 liveloop 子目录，用绝对 CD 保证 CWD。）

### 3.3 javaagent（顺序保持，三个全部保留）
`fr.agent.jar` → `hotswap-agent.jar(port=17999,src=mods/SimArena/src)` → `StarsectorPrepatcherAgent.jar`（最后）。
- **fr.agent 在 JBR 21 下正常**：premain 成功加载（日志 `Fast Rendering: v0.9.8rc1` + starfarer_obf.jar SHA-256 校验行），全程无 nest 属性报错 → 无需去除
- Prepatcher 0.18.4 在 JBR 21 下正常启动（`…javaagent starting`），保持最后一位
- agent jar 未重建、agent 源码未改动（锁定与约束均遵守）

## 4. 自动启动脚本（`dcevm/` 下）

| 文件 | 说明 |
|---|---|
| `dcevm_auto.ps1` | liveloop_auto.ps1 改造：java/bat/args 换成 DCEVM 路线；**就绪判定改为任务事实4**：`commandsAvailable=true` 且 `starsector.log` 连续 **75s** 无新行（日志轮转到 `.pre_dcevm`）；保留启动器合成点击(50.1%/65.8%)、重试点击、Spoof-Activate(WM_NCACTIVATE+WM_ACTIVATEAPP+WM_ACTIVATE+WM_SETFOCUS+鼠标停(5,5))、致命日志监控、保活循环、崩溃现场 jstack（存 `threads_dcevm.txt`）。音乐标记仅作信息展示（事实7） |
| `dcevm_session.ps1` | 会话包装：失败(TIMEOUT/CRASH)自动重试 ≤3 次 |
| `click_game.ps1` | 调试用：对游戏窗口做 SendInput 真实点击（见 §7 已知问题，证明合成输入到不了 LWJGL 游戏窗） |

启动实测（两次独立冷启动，均为**尝试 1 即 PASS**）：
- 启动1：点击 3s → 点击生效 6s → commandsAvailable 60s → **PASS 136s**（75s 日志静默）
- 启动2：同型 → **PASS 143s**
- jdk-28 路线的旧就绪判据（Title Screen State）在 JBR 下同样工作，但本脚本按任务要求用 75s 静默作为最终判据

## 5. 结构改动验证

### 5.1 目标类选择
- 按任务示例筛查 `screen_class.sh`：loading 包 10 个候选（ObfC0770/0771/0772/0773/0777/LoadingUtils/FactionDialogSpec/FleetCreationSpec/PersonNameStore/Objectsuper）**全部 FAIL** —— 原因即 corepatch/README.md 记录的既知限制：yGuard 关键字字段名（`class`/`new`/`return`/`super`/`null`）javac 无法编译，另有 CFR 类型冲突（`com.fs.starfarer.loading.String` 与 java.lang.String 混淆）；用 `_work/deobf_out_src` 源码 + 修正 classpath 复核，结论相同
- 故按任务的授权回退路径，采用 mod 类 **`mods/SimArena/src/simarena/TestFormula.java`**（必然可编译、启动即加载、且直接挂接战斗 HUD banner 渲染链）
- `corepatch/src/` 保持为空（未塞入无法编译的类）

### 5.2 实验改动（保留在源码中，见 §8 回滚）
`TestFormula.java` 新增（结构变更）：
```java
public static int dcevmCounter = 0;
public static String dcevmEcho(String s) { dcevmCounter++; return "DCEVM-OK:" + s + ":" + dcevmCounter; }
```
并同批把 `banner()` 方法体改为 `return dcevmEcho("banner-via-struct-hotswap");`（运行链改动）。

### 5.3 实验组：DCEVM 下结构 redefine —— 成功（两次独立成功）
对**已加载**的 `simarena.TestFormula`（战斗已让 banner() 跑过、类确实加载后）POST `/api/redefine`：
- 启动1（首次会话）：`redefined=8, notLoaded=0, errors=0`，`"simarena.TestFormula":"redefined"`（证据 `evidence_redefine_structural.json`）
- 启动2（重启后复现，同一场战斗内 A/B 对照）：同结果（证据 `ev2_redefine_structural.json`）
- 编译单元：`OK classes=8`（agent 内嵌 JBR javac，`--release 17`）
- 对照说明：完全相同的结构变更字节码，在 jdk-28 与 JBR-默认 下均被拒（§2 探针 + corepatch README 既知限制）；DCEVM 下同一 API 同一变更成功 —— 差异只有 `-XX:+AllowEnhancedClassRedefinition`
- 时序细节：类未加载时 redefine 报 `notLoadedYet`（agent 语义正确，不算失败）；因此验证流程 = 先 `/api/run` 开战让类加载，再 redefine

### 5.4 运行时验证（新方法真的被调用）—— 通过
同一场战斗内 A/B 截图（ArenaEnginePlugin.advance 每秒渲染一次横幅）：
| 时刻 | 截图 | 横幅内容 |
|---|---|---|
| redefine 前（T+10） | `ev2_battleA_old_banner.png`（裁剪 `ev2_banner_crop_old.png`） | `SIMARENA paragon_vs_onslaught v2-HOTFIXED …`（旧方法体） |
| redefine 后 ~5s | `ev2_battleB_new_banner.png`（裁剪 `ev2_banner_crop_dcevm_ok.png`） | `… DCEVM-OK:banner-via-struct-hotswap:4 …` 与下一帧 `:6` |

`:4 → :6` 的计数器递增 = 新增静态方法每帧被调用、新增静态字段被读改写 —— 结构热替换的端到端闭环。
启动1 也有一份独立证据（`evidence_battle1_pre_structural.png`：旧横幅；其后的 redefine 在战斗结束窗口后完成，故该次的"新横幅"未截到，教训是必须在战斗存活窗口内完成 —— 启动2 流程已修正）。

### 5.5 旧行为回归 —— 未回退
- `/api/run` → `/api/stats`：两次启动均 `live:true`（启动2 证据在 §5.4 同流程输出：`{"statsReady":true,"live":true,"scenario":"paragon_vs_onslaught",…}`）
- redefine 后战斗继续正常渲染、FPS 60-61、战斗结算正常产出 CSV（`mods/SimArena/results/20261117_033640_*.csv`）

## 6. 已知问题 / 遗留

1. **战斗结算对话框（惨重的失败/胜利）是模态**：期间 agent 泵不执行队列命令（`/api/run`、`/api/goto` 会等到 120s 超时），且**合成输入（PostMessage 与 SendInput 真实点击/ESC/Enter）都到不了 LWJGL 游戏窗**——README.md 既知限制，README 指出的修复（注入点加到 ObfC0305 插件分发器）需改 agent 源码，本次明确不做。后果：战斗打完后需**人工点"继续"**才能回到标题继续指挥。本次验证全部在战斗存活窗口内完成，未受影响。调试痕迹：`state_check*.png`、`state_hover.png`（按钮区域 0 像素差 = 游戏根本看不到鼠标）、`click_game.ps1`
2. **corepatch 核心类管线依旧不通**（yGuard 关键字标识符，~346/1298 可编译的老问题）；本次实验落在 mod 类上。DCEVM 能力对核心类同样成立（同一 Instrumentation 路径），待 deobf 标识符还原管线补齐后即可用
3. `mods/SimArena` 的 jar 里仍是**旧字节码**：下次冷启动后、首次 redefine 前，TestFormula 仍是旧行为；agent out 目录（`mods/SimArena/build/classes`）已含新类，首次 `/api/redefine` 即切到新行为。实验性源码改动保留在 `mods/SimArena/src/simarena/TestFormula.java`（仅此一个文件被改；ArenaEnginePlugin 未动）
4. 回滚：把 `TestFormula.java` 还原为 `banner() { return "v2-HOTFIXED"; }` 并删除 `dcevmCounter`/`dcevmEcho` 两成员；若需彻底回旧路线，直接用 `Miko_LiveLoop.bat`（jdk-28）启动即可，两条路线互不影响
5. JBR 21 无 JDK28 的 `HotCodeHeap`/`UseCompactObjectHeaders` 行为（默认关闭），性能特性与 jdk-28 路线不完全一致（本次未做性能对比）

## 7. 交付物清单（`_work/explore/liveloop/` 下）

新增：
- `dcevm/jbr/`（JBR 21.0.11 b1163.125 完整 SDK）
- `dcevm/dcevm_auto.ps1`、`dcevm/dcevm_session.ps1`、`dcevm/click_game.ps1`
- `dcevm/dcevmsanity/`（离线 DCEVM 探针：sanity.jar、v1/v2 类）
- `dcevm/tmp/`（下载缓存、筛查现场 sctest/）
- 证据：`ev_jbr_version.txt`、`ev_sanity_probe.txt`、`ev2_jcmd_flags.txt`、`ev2_jcmd_cmdline.txt`、
  `ev2_redefine_structural.json`、`evidence_redefine_structural.json`、`ev2_status_after.json`、`ev2_agent_log_tail.json`、
  `ev2_battleA_old_banner.png`、`ev2_battleB_new_banner.png`、`ev2_banner_crop_old.png`、`ev2_banner_crop_dcevm_ok.png`、
  `evidence_battle1_pre_structural.png`、`evidence_battle2_post_structural.png`、`handover_state.png`、`state_*.png`
- 本文件 `dcevm.md`

修改（实验性，保留）：
- `_work/explore/liveloop/Miko_LiveLoop_DCEVM.txt`（新建，§3.1）
- `_work/explore/liveloop/Miko_LiveLoop_DCEVM.bat`（新建，§3.2）
- `mods/SimArena/src/simarena/TestFormula.java`（新增两成员 + banner 方法体，§5.2/§8 回滚）

未动：agent jar 与 agent 源码、corepatch/src（保持空）、fr.agent、StarsectorPrepatcher、其余 mods。

## 8. 复现步骤（下次会话直接用）
1. `powershell -NoProfile -ExecutionPolicy Bypass -File _work\explore\liveloop\dcevm\dcevm_session.ps1`（自动启动+重试，PASS 后进入保活）
2. 就绪后（`/api/status` commandsAvailable=true 且日志静默 75s）：
   `POST /api/run {"scenario":"paragon_vs_onslaught"}` → 等 banner 渲染 →
   `POST /api/redefine`（看 results 里目标类 `redefined`）→ 数秒内 `shot.ps1` 截横幅
3. 战斗结束的结算框需人工点"继续"（见 §6.1）
