# 数据层文本热重载（data/strings 热生效）

目标：玩家停在某个 UI 页面（如标题界面提示轮播）时，AI 修改 `data/strings/*.json` 后，**不重启游戏**让新文本生效。

实现全部在 agent 侧：新增 `agent/src/liveloop/agent/DataReload.java`，修改 `agent/src/liveloop/agent/HttpApi.java`（仅这两个 agent 源文件）。agent jar 未重建（运行中的游戏锁着 jar；生效需按既有 `build_agent.sh` 流程在游戏关闭时重建并重启 agent）。

---

## 1. 机制结论（javap 证据）

### 1.1 StarfarerStrings —— 字符串静态仓

类：`com.fs.starfarer.loading.StarfarerStrings`（starfarer_obf.jar，yGuard 混淆重命名产物）。
证据文件：`javap-evidence/StarfarerStrings.javap-v.txt` / `.javap-sig.txt`。

| 成员 | 真名（字节码） | 签名 | 说明 |
|---|---|---|---|
| 加载入口 | `super`（真名就是 ASCII 的 "super"，CFR 直接渲染成 `super()` 不是重命名巧合，而是字节码里就叫这个） | `public static void super() throws IOException, JSONException`，descriptor `()V` | 见下方方法体 |
| tooltips 仓 | `Ó00000`（首字符 U+00D3，CFR 输出 `\u00d300000`；javap 控制台显示为 `?00000`；class 文件常量池字节 `c3 93` = UTF-8 编码的 U+00D3） | `private static org.json.JSONObject Ó00000` | `data/strings/tooltips.json` 的解析结果 |
| strings 仓 | `o00000`（ASCII） | `private static org.json.JSONObject o00000` | `data/strings/strings.json` 的解析结果 |

加载入口方法体（javap -v 字节码，偏移 8~27）：

```
 8: ldc  #38   // String data/strings/tooltips.json
10: invokestatic #43  // LoadingUtils.?00000:(Ljava/lang/String;)Lorg/json/JSONObject;
13: putstatic #45   // Field Ó00000
16: ldc  #47   // String data/strings/strings.json
18: invokestatic #43  // LoadingUtils.?00000:(Ljava/lang/String;)Lorg/json/JSONObject;
21: putstatic #49   // Field o00000
24: invokestatic #54  // Method com/fs/starfarer/title/String.loadTips:()V
```

即：**调用一次 `StarfarerStrings.super()` = 从磁盘重读 tooltips.json + strings.json（重新赋值两个 static JSONObject）+ 调用 `title.String.loadTips()` 重读 tips.json**。三个文件一次搞定。

读取路径（全部每次调用都直读 static JSONObject，无二级缓存）：
- `String(String,String)` → strings.json 指定命名空间取值（`StarfarerSettings` 第 1487 行经此取串）
- `super(String,String)` → tooltips.json 取 tooltip 文本（`StandardTooltipV2` 等使用）
- `Ó00000(String,String)` → 取 {title,body} 对（`prototype.Utils` 使用）
- `super(String)` → strings.json 顶层键

磁盘读取链：`StarfarerStrings.super()` → `LoadingUtils.?00000(String)`（javap-evidence/LoadingUtils.javap.txt，签名 `(Ljava/lang/String;)Lorg/json/JSONObject;`）→ `C.Ø00000().Ø00000(path)`（游戏虚拟文件系统，按启动时的根列表解析 starsector-core + 已启用 mod 目录，mod 优先）→ **每次调用都是新的磁盘 IO，无内容缓存**；读出的文本先剥离 `#` 注释行（字符串内部除外）再 `new JSONObject(...)`。所以重载=重读，`#` 注释与尾逗号都合法。

boot 时唯一调用点：`com.fs.starfarer.loading.ResourceLoaderState`（第 255 行 `StarfarerStrings.super();`），即启动加载期跑一次，之后不再自刷。

### 1.2 title.String —— 标题提示轮播

类：`com.fs.starfarer.title.String`（类名就叫 String）。证据：`javap-evidence/title.String.javap.txt`。

```
public static com.fs.starfarer.api.util.WeightedRandomPicker<java.lang.String> picker;   // 提示池
public static void loadTips() throws java.io.IOException, org.json.JSONException;        // 重读 tips.json
public static java.lang.String pickTipText();                                            // 取一条
private void ??O000();                                                                   // (ÓöO000) setText(pickTipText())
```

- `loadTips()`：读 `data/strings/tips.json` → 顶层键 `tips`（数组）→ 每项 `picker.add(tip, freq)`。**只 add 不 clear** —— 直接二次调用会把旧提示全部累加，所以热重载必须先清空 `picker`。
- 轮播读取路径：点击"下一个提示"按钮 → `actionPerformed` → 私有 `ÓöO000()` → `pickTipText()` → `picker.pick()`（带"不与上一条重复"逻辑，基于 `picker.getItems().indexOf`）。**每次点击实时读 static picker 字段，不缓存文本**。
- 结论：**重载成功后，下一次点击"下一个提示"即从新池抽取，无需重开 UI**。当前正在显示的那一条要等下次点击才换。另一个会触发 re-pick 的点：标题控件树重建时（`TitleScreenState` → `ObfC0895` 第 44 行 `new String(...)`，构造函数里 pick 一次并 setText）；此外 `campaign/save/B.java` 的存读档界面也会调 `pickTipText()`。
- `WeightedRandomPicker`（starfarer.api.jar，javap-evidence/WeightedRandomPicker.javap.txt）：有 `public void clear()`、`add(T,float)`、`getItems()`、`getWeight(T)` —— 全 ASCII 名，反射操作无障碍。

### 1.3 是否有 UI 缓存问题

- **标题提示轮播：无缓存问题**（见 1.2，按点击实时读池）。
- strings.json / tooltips.json 的**通用结论**：所有已渲染在屏幕上的文本（对话框已显示的句子、已打开界面的 tooltip、百科条目等）都是 UI 构件创建时 setText 进去的旧值；重载只影响**之后新发起的查找**（新打开的界面/新渲染的 tooltip/新触发的对话）。要看新值需要重开对应界面/对话框。这是通用 UI 限制，与本次实现无关——静态仓本身已换新。
- `pickTipText()` 对"新游戏首次进标题"有一段硬编码欢迎词（字节码常量，不在 json 里），重载不影响它。

---

## 2. DataReload.java 实现

文件：`_work/explore/liveloop/agent/src/liveloop/agent/DataReload.java`（自包含、纯反射、不 import 任何游戏类、永不抛出）。

`public static Map<String,Object> reloadStrings()` 流程：

1. **快照**：反射取 `title.String.picker`（public static），经 `getItems()`/`getWeight(T)` 把当前提示池连权重复制下来。
2. **清池**：`picker.clear()`（规避 loadTips 只 add 不 clear 的坑）。
3. **主路径**：`Class.forName/findLoaded` 拿 `StarfarerStrings` → `getMethod("super")` → `invoke(null)`。成功 → `reloaded` 记入 tooltips.json + strings.json + tips.json(经主路径)。
4. **回退路径**：主路径类找不到/调用失败 → 直接 `title.String.loadTips()`（只重载 tips.json）。
5. **失败保护**：若两条路径全失败 → 用快照 `clear()` + `add(item, weight)` 尽力还原旧池，**坏 JSON 不会让标题屏变成空池**（空池时 `pick()` 返回 null、`setText(null)` 有风险）。
6. 返回：`{ok, reloaded:[...], titlePickerCleared, tipCount?, restoredFromSnapshot, error?}`；`tipCount` 是重载后池大小（含 `{"freq":0}` 的 0 权重项，它们不会被抓到但占条目数）。

类查找：先 `Redefiner.findLoaded(dotted)`（跨类加载器精确匹配已加载实例），失败再 `Class.forName`（核心类在应用类加载器上）。没有依赖 ModLink/SimArena，游戏没开 mod 也能用。

## 3. HttpApi 路由 diff

仅两处改动：

(a) `start()` 的端点清单日志字符串补上 `/api/reload-strings`：

```diff
-        Log.log("HTTP api on http://127.0.0.1:" + cfg.port + "/  (endpoints: /api/status /api/run /api/goto /api/redefine "
-                + "/api/reload-specs /api/end /api/scenarios /api/variants /api/stats /api/stats.csv /api/log)");
+        Log.log("HTTP api on http://127.0.0.1:" + cfg.port + "/  (endpoints: /api/status /api/run /api/goto /api/redefine "
+                + "/api/reload-specs /api/end /api/scenarios /api/variants /api/stats /api/stats.csv "
+                + "/api/reload-strings /api/log)");
```

(b) 在 `/api/patch-string` 块之后（仍处于 `!ModLink.available()` 503 门槛**之前**，与 patch-string 同级，不依赖 mod）新增：

```diff
+        // 数据层文本热重载：重读 data/strings/*.json（不依赖 ModLink，游戏未启用 mod 也可用）
+        if (path.equals("/api/reload-strings") && method.equals("POST")) {
+            if (HotSwapAgent.pumpInjected()) {
+                // run on the game main thread: the reload swaps static JSONObjects and
+                // rebuilds the title tip picker, which the render thread reads per click
+                final java.util.concurrent.CompletableFuture<Map<String, Object>> fut =
+                        new java.util.concurrent.CompletableFuture<>();
+                AgentBridge.enqueue(() -> fut.complete(DataReload.reloadStrings()));
+                Map<String, Object> res;
+                try {
+                    res = fut.get(30, java.util.concurrent.TimeUnit.SECONDS);
+                } catch (Throwable t) {
+                    res = new LinkedHashMap<>();
+                    res.put("ok", false);
+                    res.put("error", "reload-strings on game thread timed out/failed: " + t);
+                }
+                res.put("thread", "game");
+                send(ex, 200, MiniJson.write(res));
+            } else {
+                Map<String, Object> res = DataReload.reloadStrings();
+                res.put("thread", "http");
+                send(ex, 200, MiniJson.write(res));
+            }
+            return;
+        }
```

写法对齐 `/api/patch-string`（POST、`MiniJson.write` 返回、失败 200+`ok:false`）与 `/api/run` 的 game-thread 派发模式（`AgentBridge.enqueue` + CompletableFuture + 超时）。pump 已注入（TitleScreenState/CampaignState/CombatState，标题屏在列）则跑在游戏主线程（静态仓换引用 + 重建 picker 期间避免渲染线程竞争）；未注入则退化为 HTTP 线程直调，响应里的 `thread` 字段标明走的是哪条。游戏冻结（无前台焦点且无保活）时 game 分支 30 秒超时报错。

## 4. 数据文件落点（改哪里）

标题提示轮播的文本 = **`starsector-core/data/strings/tips.json`，顶层键 `tips`（数组）**。
- 数组元素两种形态：纯字符串（默认 freq=1），或 `{"freq":n, "tip":"..."}`（freq=0 永不出现；文件第 3 行官方示例就是这个）。
- 文件是 UTF-8 + CRLF、允许 `#` 注释（LoadingUtils 解析前剥离）、尾逗号合法。
- 任务里提到的 "Philip" 提示**不在 tips.json**（grep 全 strings 目录，Philip 只出现在 `descriptions.csv`，那是物品/舰船描述数据，不走 StarfarerStrings）；示例文本是虚构的。本安装为汉化版，tips.json 内容已是中文。
- 修改方式（两种均已实测，见 §6）：
  1. **改现有条目**：直接编辑目标行字符串。
  2. **新增条目**：最后一条末尾加 `,`，下一行加 `"新提示文本"`（注意保持数组闭合 `]` 之前的逗号规则）。
- 若 mods 下某 mod 提供 `data/strings/tips.json` 覆盖，重载读到的是 mod 版（虚拟 FS mod 优先）；启动后**新加入**的 mod 目录不会被识别（根列表 boot 期固定）。

**端到端步骤（游戏须以 liveloop_session.ps1 启动，保活防冻结）**：

1. 改 `starsector-core/data/strings/tips.json` 的 `tips` 数组（如把第 57 行末尾追加 `[热重载测试]`）。
2. `curl -s -X POST http://127.0.0.1:17999/api/reload-strings` → 期望 `{"titlePickerCleared":true,"tipCount":55,"reloaded":["data/strings/tooltips.json + data/strings/strings.json (StarfarerStrings)","data/strings/tips.json (title.String.loadTips, via StarfarerStrings)"],"ok":true,"restoredFromSnapshot":false,"thread":"game"}`（原版 tips.json 共 55 条）。
3. 游戏停在标题界面，点 **"下一个提示"** 按钮若干次 → 会抽到含 `[热重载测试]` 的新文本（同屏可能混有旧池外无变化的条目，多点几次或临时把 freq 调大只留测试条目最直观；也可把原数组全部删掉只留一条新文本，tipCount 应为 1）。
4. 截图留证：`_work/explore/liveloop/shot.ps1`。
5. 还原文件后再 POST 一次即可回滚。

## 5. 离线验证证据（未触碰运行中的游戏）

### 5.1 javap 真名/签名

工具 `C:/game/StarSector.v0.9.8a-RC8/jdk-28+13/bin/javap`（28-beta）。证据文件在 `javap-evidence/`。关键行：

```
StarfarerStrings:
  public static void super() throws java.io.IOException, org.json.JSONException;   // descriptor: ()V
  private static org.json.JSONObject ?00000;   // 实为 Ó00000 (U+00D3)，常量池字节 c3 93
  private static org.json.JSONObject o00000;
  public static java.lang.String super(java.lang.String);
  public static java.lang.String String(java.lang.String, java.lang.String);
  public static java.lang.String super(java.lang.String, java.lang.String);
  public static com.fs.starfarer.loading.StarfarerStrings$o ?00000(java.lang.String, java.lang.String);
  public static boolean ?00000(java.lang.String, java.lang.String);

title.String:
  public static com.fs.starfarer.api.util.WeightedRandomPicker<java.lang.String> picker;
  public static void loadTips() throws java.io.IOException, org.json.JSONException;
  public static java.lang.String pickTipText();

WeightedRandomPicker:
  public void clear();  public void add(T, float);  public java.util.List<T> getItems();  public float getWeight(T);  public T pick();

LoadingUtils:
  public static org.json.JSONObject ?00000(java.lang.String) throws java.io.IOException, org.json.JSONException;
```

`Ó00000` 字符判定：class 常量池 hexdump `01 00 07 c3 93 30 30 30 30 30` = Utf8(len 7) = U+00D3 + "00000"，与 CFR 的 `\u00d300000` 一致。（实现上不需要引用这个怪名——加载方法自己会重赋值。）

### 5.2 编译

仅编译两个改动文件（jdk-28 javac，`-encoding UTF-8 --release 17`，-cp 用 C:/ 风格；含 agent jar + 游戏各 jar）：

```
javac -encoding UTF-8 --release 17 ^
  -cp "C:/game/StarSector.v0.9.8a-RC8/_work/explore/liveloop/agent/hotswap-agent.jar;C:/game/StarSector.v0.9.8a-RC8/starsector-core/starfarer_obf.jar;C:/game/StarSector.v0.9.8a-RC8/starsector-core/starfarer.api.jar;C:/game/StarSector.v0.9.8a-RC8/starsector-core/json.jar" ^
  -d datareload/build-check src/liveloop/agent/DataReload.java src/liveloop/agent/HttpApi.java
→ COMPILE_OK
```

全源码树整编（与 build_agent.sh 同参，只出 class 到 `datareload/build-check-full`，**未重建 jar**）→ `FULL_TREE_COMPILE_OK`。

### 5.3 运行时冒烟（OfflineSmoke.java，无游戏进程）

- (a) 无游戏 jar：`{"titlePickerCleared":false,"restoredFromSnapshot":false,"reloaded":[],"ok":false,"error":"class not loaded/found: com.fs.starfarer.loading.StarfarerStrings; class not loaded/found: com.fs.starfarer.title.String"}` —— 类缺失路径优雅返回。
- (b) 带游戏 jar（starfarer_obf/fs.common_obf/json/log4j）但无游戏引导：log4j 打出真实日志 `Loading strings`（**真实加载方法确实被反射调起**），随后在游戏虚拟 FS 初始化处失败，DataReload 捕获并返回 `ok:false, error:"StarfarerStrings loader: ...InvocationTargetException <- StringIndexOutOfBoundsException..."` —— 永不抛出契约成立，错误链完整。

### 5.4 tips.json 编辑往返（改→读回→还原）

- 备份 md5 `af34c88e8aa5d452a964b1b2325dcf85`（`datareload/tips.json.orig-bak`）。
- 改法 1（行内追加标记）：Edit 后读回仅 1 行差异，文件仍为 `UTF-8 text, with CRLF`，标记可见。
- 改法 2（末尾新增条目：上一行补 `,` + 新行 `"HOTRELOAD-APPENDED-TIP-MARKER：..."`）：diff 仅 57→57,58 两行变化，编码不变。
- 还原：`cp tips.json.orig-bak tips.json` → md5 回到 `af34c88e8aa5d452a964b1b2325dcf85`，HOTRELOAD 标记残留 0。**游戏安装目录数据文件已字节级还原**。

## 6. 限制与注意事项

1. **生效范围**：重载后，之后发生的查找/抽取拿新值；已渲染文本（其他界面已画出的字符串、已打开的对话框/百科）保持旧值直到该界面重建/重开。标题提示轮播是特例——按点击实时读池，天然即时生效。
2. **覆盖范围**：本次重载只覆盖 `tooltips.json`、`strings.json`、`tips.json`（StarfarerStrings 的全部管辖）。`descriptions.csv`（物品/舰船描述）与 `ship_names.json` 不经 StarfarerStrings 加载，不在本路径内。
3. **坏 JSON 防护**：tips.json 解析失败时提示池快照会还原，标题屏不会出现空池；但 strings/tooltips 的 static 仓若解析失败会保持旧值（赋值发生在解析成功后），同样安全。
4. **重复防护**：loadTips 只 add 不 clear，DataReload 已先 clear；绕过 DataReload 直接 POST 反射调 loadTips 会造成提示翻倍。
5. **线程模型**：默认游戏主线程派发（pump 注入时）；游戏整体冻结（无焦点无保活）时 30s 超时返回错误，HTTP 线程不受影响。
6. **agent jar 未重建**：运行中的游戏锁定 jar；新代码生效需游戏关闭后跑 `_work/explore/liveloop/build_agent.sh` 重建并重启游戏/agent（本任务按约束未执行）。
7. 游戏偶发在 `LightShader$1` 附近静默崩溃——与本功能无关。
