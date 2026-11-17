# Starsector LiveLoop — 运行中游戏的热更新开发驾驶舱

> 一个面向 **Starsector（远行星号）modding 研究** 的工具链：JVMTI agent + mod 桥接，
> 让你**不重启游戏**地修改代码/数据，并立即在画面上看到结果。为 AI 协作的
> mod 开发工作流设计——"AI 改代码 → 游戏内即时生效 → 结构化数据反馈"。

![能力矩阵](https://img.shields.io/badge/hotswap-方法体·结构(需DCEVM)·常量·文本-blue)

## 能做什么

| 改动类型 | 生效方式 | 重启游戏 |
|---|---|---|
| mod 方法体逻辑 / 渲染 | redefine，下一帧/下一 tick | ❌ 不需要 |
| **新增字段/方法**（结构改动） | DCEVM（JBR 21）+ redefine | ❌ 不需要 |
| 方法体内字符串常量 | ASM LDC 补丁（`/api/patch-string`） | ❌ 不需要 |
| 提示 / tooltip / strings 文本 | 改 json → `/api/reload-strings` | ❌ 不需要 |
| 菜单/界面标签（ConstantValue 常量） | 离线 jar 补丁（含自动备份） | ✅ 需一次 |
| 第三方 mod（无混淆 jar） | Vineflower 反编译 → 同上全适用 | 视类型 |

## 工作原理

```
AI 编辑器 ──保存──> 源目录（mod src + corepatch 核心补丁）
    │                   │ WatchService 监视
    │                   ▼
    │        热更新 agent（运行在游戏 JVM 内，-javaagent）
    │        ├─ javax.tools 增量编译（classpath = 游戏运行时 classpath）
    │        ├─ Instrumentation.redefineClasses（JBR/DCEVM 下支持结构改动）
    │        └─ HTTP 127.0.0.1:17999（JSON API，服务 AI）
    │                   │ 主线程命令经字节码注入的 pump 派发
    ▼                   ▼
  游戏主线程（Title/Campaign/Combat 三个状态的 processInput 注入点）
    └─ 场景直达 / 战斗构建 / 统计采集 / 文本热重载
```

关键设计：

- **主线程泵**：agent 用 ASM 往三个游戏状态类的 `processInput` 入口注入 `pump()` 调用，
  使排队的命令在"游戏自己的 UI 按钮处理器同级的"安全点执行——不在引擎遍历中途动引擎。
- **沙箱隔离**：游戏脚本类加载器禁 `java.io.File`/`java.lang.reflect`。mod 桥（SimArena）
  与 agent 完全解耦——所有跨类操作由 agent 在无沙箱侧反射完成。
- **双编译单元**：mod 源与核心补丁源分开编译，坏补丁不会阻塞 mod 热更新。
- **jar 锁规避**：构建产物写 `.next` 暂存，启动脚本在游戏尚未启动的瞬间晋级为正式名。

## 目录结构

```
agent/           热更新 agent（Java 源码 + 构建脚本）
simarena-mod/    桥接 mod（命令门面 / 场景构建 / 战斗统计 / 演示目标）
scripts/         构建、启动、截图、类筛查工具
docs/            研究笔记与案例研究（含截图证据）
examples/        启动参数文件样例
```

## 快速开始

要求：Starsector 0.98a-RC8（Windows）、JDK 21+（agent 内嵌编译器需要完整 JDK）、
按本仓库假设的目录布局安装（`STARSECTOR_ROOT` 环境变量可指向任意位置）。

```bash
# 1. 构建 agent 与 mod jar
./agent/build_agent.sh
./scripts/build_simarena.sh

# 2. 把 simarena-mod/ 装入游戏 mods 目录（或符号链接），启用 SimArena
# 3. 启动（合成点击启动器 + 激活伪装防冻结 + 崩溃自动重试）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/liveloop_session.ps1
```

### API 速查（127.0.0.1:17999，仅监听本机）

```bash
curl -X POST http://127.0.0.1:17999/api/run -d '{"scenario":"paragon_vs_onslaught"}'  # 直达战斗
curl -X POST http://127.0.0.1:17999/api/redefine                                      # 热替换已改源码
curl -X POST http://127.0.0.1:17999/api/patch-string -d '{"class":"...","old":"...","new":"..."}'
curl -X POST http://127.0.0.1:17999/api/reload-strings                                # 文本热重载
curl http://127.0.0.1:17999/api/stats                                                 # 战斗统计
```

## 案例研究（docs/）

- [dcevm.md](docs/dcevm.md) — DCEVM 结构性热更新路线：JBR 21 + 一个开关，战斗中给已加载类
  加字段/方法并被真实调用（含横幅计数器递增的截图证据）
- [datareload.md](docs/datareload.md) — 文本热重载：真实方法名考古（加载方法真名就叫 `super`）、
  提示池计数 99→101→99 与文件编辑完全同步
- [refituiplus-case-study.md](docs/refituiplus-case-study.md) — 第三方 mod（refituiplus）UI 对齐
  热调：Vineflower 反编译 → 指令手术 → 活体 redefine，零重启三连改
- [LIVELOOP_NOTES.md](docs/LIVELOOP_NOTES.md) — 完整研究笔记（含字节码常量补丁与 jar 锁规避）

## 已知限制

- 战斗结算对话框为模态，主线程泵在其期间停摆——排队命令等对话框关闭后执行
  （下一迭代：把泵注入点扩展到插件分发器，穿透对话框）
- yGuard 把包/类/字段重命名为关键字的类无法走"反编译→重编译"（约 2/3 的核心类），
  这类改动走字节码常量补丁或等待标识符映射管线
- `notLoadedYet`：热替换只作用于已加载的类；未加载类的改动随 `.next` 晋级在下次启动生效
- 生涯存档会序列化字段结构——结构热改涉及存档字段时请勿保存

## 免责声明

本项目为**个人 modding 与运行时研究**用途的工具，与 Fractal Softworks 无关。
仓库中不包含、也不分发任何游戏本体代码或资源；所有示例针对本地合法安装的游戏副本。
请遵守游戏 EULA。Starsector 为单机游戏，本工具不适用于任何联网对抗场景。

## 许可

[MIT](LICENSE)
