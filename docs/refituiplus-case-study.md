# refituiplus 船插筛选面板 — 选项文字居中 热调报告

日期: 2026-11-18
目标: 运行中的 Starsector 0.98a-RC8(根目录 `C:\game\StarSector.v0.9.8a-RC8`),refit 界面左上角 refituiplus 船体插件筛选面板,"船插类型"标题下方的选项文字从左对齐改为居中对齐。
结果: **成功**。三次 JVM HotSwap redefine,0 错误,面板内全部选项文字已居中(截图验收通过)。未重启游戏、未杀 java、未重建任何 jar、未改动 agent 源码。

## 一、根因定位(反编译 Vineflower,产物在 `vf_out/`)

面板各元素的文字对齐链路(均为 Kotlin 代码):

| 面板元素 | 创建代码 | 原对齐 |
|---|---|---|
| 两列类型格(所有类型/后勤/…/防御)、分组优先/清除筛选、MOD来源/设计来源标签页 | `HullModSidebarBuilder` → lambda `HullModSidebarBuilder$replace$1$9$5` 等 → `refituiplus/ui/controls/LunaFilterButton.create(content,w,h,text,tooltip,fontRole,centered)`;`centered=false`(create$default 缺省 iconst_0)时走 `LunaTreeStyle.centerSourceLabel()` | `centerSourceLabel` 只做**垂直**居中(调 YAlignOffset),水平保持左对齐 |
| 收藏/黑名单 | lambda `$replace$1$13$5` → `LunaFilterButton.create(..., centered=true)` → `LunaElement.centerText()` | 本来就居中(印证语义) |
| 数字 1-0 | `NumberedGroupButton` | 本来就居中 |
| 来源树行(全部 MOD 来源/原版/威胁渐显 Emergent Threats/黎明共和国) | `ModSourceTree` → `SourceRowsMount.mountLuna` → `refituiplus/ui/controls/SourceTreeRow.create` → 同样调 `centerSourceLabel` | 水平左对齐 |
| 搜索占位符(名称/拼音 @MOD #属性、查找 MOD / 设计来源) | `refituiplus/ui/controls/SearchControl`:构造时 `label.setAlignment(Alignment.LMID)`;每帧/每次编辑 `updateText()` 重设文字 | LMID(左中) |

`LunaElement.centerText()`(Lunalib)= `position.inTL(width/2 - textW/2, height/2 - textH/2)`,即水平+垂直居中;`centerSourceLabel` 在其后调用时垂直修正量≈0,不冲突。

## 二、改动内容(方法体级,ASM 单指令手术;工具 `patchtool/PatchTool.java`,产物 `patched_out/`)

全部是对**原 jar 内 .class 的方法体**做栈中性指令改写,不改字段/方法/内部类结构(redefine 合法):

1. `refituiplus/ui/controls/LunaFilterButton.class` → `create(...)`:
   else 分支 `getstatic LunaTreeStyle.INSTANCE; aload N; invokevirtual LunaTreeStyle.centerSourceLabel(Llunalib/lunaUI/elements/LunaElement;)V`
   改为 `3×nop; aload N; invokevirtual lunalib/lunaUI/elements/LunaElement.centerText()V`
   ⇒ 两个分支都调用 centerText,所有 `centered=false` 的格子(类型格、分组优先、清除筛选、MOD来源/设计来源标签)水平居中。`LunaFilterButton` 仅被 HullModSidebarBuilder 引用,作用域恰好只有船插侧栏。
2. `refituiplus/ui/controls/SourceTreeRow.class` → `create(...)`:同一模式改写 ⇒ 来源树行居中。
3. `refituiplus/ui/controls/SearchControl.class` → `updateText()`:
   - 占位符分支(`invokespecial fit(String,F)String` 之后、`goto setText` 之前)插入 `label.setAlignment(Alignment.MID)`(栈中性,4 条指令);
   - "已输入文字"分支入口(`SearchInputState.getSearchFocused()` 后 `ifne` 的跳转目标 label 处)插入 `label.setAlignment(Alignment.LMID)`。
   ⇒ 占位符居中显示;用户实际输入的文字与光标坐标系保持左对齐(输入框光标 placeCaret 以 textStartX 为基准,若输入文字也居中会错位)。

## 三、投放机制(已验证)

1. `POST http://127.0.0.1:17999/api/redefine` 会:编译监视源目录(mods/SimArena/src + corepatch/src)→ **读取 `mods/SimArena/build/classes/` 下全部 .class**(含手工投放的)→ 对运行中已加载的同名类逐个 redefine。
2. 操作:把 `patched_out/refituiplus/ui/controls/{LunaFilterButton,SourceTreeRow,SearchControl}.class` 拷到 `mods/SimArena/build/classes/refituiplus/ui/controls/` → `curl -X POST http://127.0.0.1:17999/api/redefine`。
3. 返回:`"refituiplus.ui.controls.LunaFilterButton":"redefined"`, `"refituiplus.ui.controls.SourceTreeRow":"redefined"`, `"refituiplus.ui.controls.SearchControl":"redefined"`,errors=0。starsector.log 无 VerifyError/ClassFormatError。

## 四、生效时机结论

- 三个类的对齐都在**格子/行创建时**设置 → redefine 后**已存在的单元格不变**,需要侧栏面板重建才生效。
- 例外:`SearchControl.updateText()` 每次编辑/布局刷新都会执行,所以**搜索占位符 redefine 后立即变居中**(无需重建,截图 after_redefine_1.png 可见)。
- 实测触发重建的交互:**点击"清除筛选"按钮**(它执行 clearFilters 并触发整个侧栏 replace 重建)——点击后类型格、动作行、来源行全部以居中样式重建。开关类型筛选(点击"所有类型"/"相位"再取消)不会重建格子(只刷新高亮和标题显隐)。

## 五、迭代截图(`_work/explore/refituiplus-hot/`)

| 阶段 | 文件 | 说明 |
|---|---|---|
| 迭代0 修改前 | `before_1.png` / `before_crop.png` | 全部左对齐(收藏/黑名单与数字原本居中) |
| redefine 后、重建前 | `after_redefine_1.png` / `after_crop.png` | 仅两个搜索占位符立即居中,格子仍左对齐 |
| 重建触发尝试1 | `click1.png` | 点"所有类型":格子只切换选中态,未重建 |
| 重建触发尝试2 | `dd1.png` | 点"船体插件"下拉:改列表分组,未重建 |
| **重建成功** | `clear1.png` / `clear1_crop.png` | 点"清除筛选"→ 全侧栏重建,全部选项居中 |
| 回归测试 | `regress1.png`(选中"相位",筛选生效且文字居中)、`regress2.png`(取消,列表恢复) | 补丁未破坏交互 |
| 最终定稿 | `final_clean.png` / `final_clean_crop.png` | 干净最终态 |

## 六、最终效果

`final_clean_crop.png`:类型格 10 格、分组优先/清除筛选、数字 1-0、收藏/黑名单、两个搜索占位符、MOD来源/设计来源、全部 MOD 来源/原版/威胁渐显 Emergent Threats/黎明共和国 —— **全部水平居中**。区块标题(船插类型、需要船坞)与提示行(左键单选…)保持原样式(属标题/提示,非选项)。

## 七、用户需要做什么

- **现在**即可继续游戏,无需任何操作;改动已生效且当前会话内持续。
- 若后续某次改动/重开界面导致侧栏重建,样式自动保持居中(代码已换)。

## 八、遗留问题 / 注意事项

1. **仅本次游戏会话有效**:mod jar 未动(被运行中 JVM 锁定,铁律禁止重建)。重启游戏后回到 mod 原版左对齐样式。若要永久生效,需把 `patched_out/` 里的 3 个 .class 打进 `mods/refituiplus/jars/refituiplus.jar`(需下次启动前操作)。
2. **投放目录易失**:`mods/SimArena/build/classes/` 下的补丁 class 会被 build_simarena.sh 的 `rm -rf build` 清掉(本次未运行该脚本);重跑 redefine 前需重新拷贝(工具与产物都在 `_work/explore/refituiplus-hot/`,一键可复现:`java -cp patchtool_out;<asm-bundle> patchtool.PatchTool jar_extracted patched_out` → 拷贝 → POST redefine)。
3. **超范围副作用(同一代码路径,已一并生效)**:`SourceTreeRow.create` 被武器/战机选择器、生产订单来源视图、靶场浏览器等共用——这些界面的来源选择行在下次重建后也会居中;所有 mod 内搜索框(武器选择器等)的占位符同样会居中。输入框实际输入文字与光标不受影响。若需要把这些界面改回左对齐,需再走一次 redefine 流程改回条件分支。
4. 未禁止且未做:未改 `_work/explore/liveloop/agent/src/`,未运行 build_simarena.sh / build_agent.sh,未动任何 jar。
