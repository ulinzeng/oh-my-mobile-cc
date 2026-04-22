---
name: compose-canvas-terminal
description: Compose Multiplatform Canvas 自绘终端方案（字体度量、滚动、选择、降级到 xterm.js 的条件）
triggers: ["compose canvas", "terminal rendering", "canvas draw", "monospace font", "grid render", "terminal scrolling", "xterm.js fallback"]
related-specs: [openspec/specs/terminal/spec.md]
---

# Compose Canvas Terminal

> 适用范围：当任务涉及在
> `shared/src/commonMain/kotlin/io/ohmymobilecc/ui/terminal/` 内的 Compose
> Multiplatform 自绘终端渲染层、滚动、选择复制、键盘输入，或评估 W3
> day-7 降级到 xterm.js 的触发条件时加载本文。

## 1. 方案选择：自绘 vs WebView

plan 锁定 **2b：Compose/SwiftUI 纯原生自绘**（非 xterm.js、非 Kterm）。
背后的权衡：

| 维度 | 自绘 Compose Canvas | WebView + xterm.js |
|---|---|---|
| 性能 | GPU 直绘，大屏滚动最稳 | JS/DOM 重，低端机掉帧 |
| 离线 | 无需外部资源 | 需打包或联网取 xterm.js |
| 外观一致 | iOS/Android 看起来一样 | 两端 WebView 行为不同 |
| 与 SKIE 互操作 | Kotlin 纯类型导出到 Swift | JS 桥接，async 不友好 |
| 字体控制 | 精确 cell 度量 | 受 CSS 字体栈影响 |
| 实现风险 | 高（我们自己写） | 低（成熟） |

因此 W3 同时**保留降级点**：第 7 天 checkpoint 若 `htop` 掉帧 / 颜色错
乱 / ZWJ emoji 断裂无法修复，切 xterm.js。parser + grid model 保留，只换
渲染层（见 §8）。

## 2. Compose Canvas API 的正确用法

渲染层按帧把 `Grid` 快照投到 `Canvas` 上。

```kotlin
@Composable
fun TerminalCanvas(
    state: TerminalState,
    config: TerminalConfig,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberCellMetrics(config.fontFamily, config.fontSize)
    Canvas(modifier = modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            drawGrid(canvas.nativeCanvas, state.grid, metrics, config)
        }
    }
}
```

`drawIntoCanvas { it.nativeCanvas }` 拿到的是
`org.jetbrains.skia.Canvas`（Compose MP 在 Android / iOS / JVM 统一用 Skia
后端），可直接用 `Font` / `TextBlob` / `drawRect` 等原生 API——这是
Android 版 Jetpack Compose 与 Compose MP 共享的能力。

## 3. 字体策略

- **打包**：把 JetBrains Mono 作为 Compose Resources 放到
  `shared/src/commonMain/composeResources/font/JetBrainsMono-Regular.ttf`
  与对应 `-Bold.ttf`、`-Italic.ttf`。
- **加载**：`Font(resource = Res.font.JetBrainsMono_Regular)` 得到
  `FontFamily`；在 Skia 侧通过 `org.jetbrains.skia.Typeface.makeFromData`
  产出度量用 `Font`。
- **兜底**：资源缺失时回落到 `FontFamily.Monospace`，保证至少能渲染 ASCII。
- **CJK 回落字体**：iOS 用 `PingFang SC`，Android 用 `Noto Sans CJK`；通过
  系统字体管理器按 code point 查询（Skia 的 `FontMgr.matchFamilyStyleCharacter`）。

## 4. Cell metrics

```kotlin
data class CellMetrics(
    val cellWidth: Float,
    val cellHeight: Float,
    val baseline: Float,
)

@Composable
fun rememberCellMetrics(family: FontFamily, size: TextUnit): CellMetrics {
    val density = LocalDensity.current
    val skiaFont: org.jetbrains.skia.Font = remember(family, size) {
        buildSkiaFont(family, with(density) { size.toPx() })
    }
    return remember(skiaFont) {
        val advance = skiaFont.measureTextWidth("M")
        val m = skiaFont.metrics
        val lineHeight = kotlin.math.ceil(-m.ascent + m.descent + m.leading)
        CellMetrics(
            cellWidth = advance,
            cellHeight = lineHeight,
            baseline = -m.ascent,
        )
    }
}
```

要点：

- 只测量一次 `"M"` 作为等宽 advance；不要用 text layout 的 `width`。
- `lineHeight` 必须 `ceil`，否则浮点累积会让第 N 行错位半像素。
- 宽度 2 的 cell（CJK / emoji）= `cellWidth * 2`，渲染时直接跨两格。

## 5. 渲染循环：仅重绘 dirty rows

`AnsiParser` + `Grid` 的职责是维护 `Set<Int> dirtyRows`。Canvas 侧只重绘
变动行：

```kotlin
private fun DrawScope.drawGrid(
    native: org.jetbrains.skia.Canvas,
    grid: Grid,
    m: CellMetrics,
    cfg: TerminalConfig,
) {
    val paintBg = org.jetbrains.skia.Paint()
    val paintFg = org.jetbrains.skia.Paint()
    val skFont = cfg.skiaFont

    for (row in grid.dirtyRows) {
        val y = row * m.cellHeight
        for (col in 0 until grid.cols) {
            val cell = grid[row, col] ?: continue
            paintBg.color = cell.attr.bg.toSkColor(cfg.palette)
            native.drawRect(
                org.jetbrains.skia.Rect.makeXYWH(col * m.cellWidth, y, m.cellWidth * cell.width, m.cellHeight),
                paintBg,
            )
            if (cell.codePoint != 0x20) {
                paintFg.color = cell.attr.fg.toSkColor(cfg.palette)
                native.drawString(
                    s = String(intArrayOf(cell.codePoint), 0, 1),
                    x = col * m.cellWidth,
                    y = y + m.baseline,
                    font = skFont,
                    paint = paintFg,
                )
            }
        }
    }
    grid.clearDirty()
}
```

> `drawString` 对每个 cell 调一次是**基线实现**；性能压力出现后合并
> 连续同属性 run，用 `TextBlob` 一次投递（W3 性能任务）。

## 6. 滚动 & scrollback

- `Grid` 内部维护 **ring buffer**，容量默认 10k 行（`TerminalConfig.scrollbackLines`）。
- 可视区 = ring buffer 的一个滑动窗口；UI 侧用
  `androidx.compose.foundation.lazy.LazyListState` 暴露滚动位置：

```kotlin
@Composable
fun TerminalScrollable(state: TerminalState, cfg: TerminalConfig) {
    val lazyState = rememberLazyListState()
    LazyColumn(state = lazyState) {
        items(state.grid.totalRows) { rowIndex ->
            TerminalRow(state.grid.rowAt(rowIndex), cfg)
        }
    }
    LaunchedEffect(state.grid.tailTick) {
        if (lazyState.firstVisibleItemIndex >= state.grid.totalRows - cfg.viewportRows - 1) {
            lazyState.scrollToItem(state.grid.totalRows - 1)
        }
    }
}
```

`tailTick` 每次新增行自增，触发"跟随尾部"自动滚到底。用户手动上滑后
`firstVisibleItemIndex` 落在历史段，自动滚动暂停，与 iTerm / tmux 行为一致。

## 7. 选择 & 复制

```kotlin
Modifier.pointerInput(Unit) {
    detectDragGestures(
        onDragStart = { offset ->
            val cell = metrics.toCell(offset)
            selection = Selection(anchor = cell, focus = cell)
        },
        onDrag = { change, _ ->
            selection = selection?.copy(focus = metrics.toCell(change.position))
        },
        onDragEnd = {
            val text = grid.extractText(selection ?: return@detectDragGestures)
            clipboard.setText(AnnotatedString(text))
        },
    )
}
```

- `metrics.toCell(Offset) -> GridPos` 做除法与 `floor`；注意宽 2 cell 的
  点击应映射到其左列。
- 复制接口通过 `ports/ClipboardPort`（Android: `ClipboardManager`；
  iOS: `UIPasteboard.general`），避免 UI 层平台分叉。
- OSC 52 剪贴板写入我们**拒绝接收**（见 ansi-parser-deep-dive §4）。

## 8. 虚拟键盘 & 输入链路

最小可用输入：一个不可见的 `TextField` 汇集 IME 事件，转成 PTY 字节：

```kotlin
@Composable
fun TerminalInputSink(onBytes: (ByteArray) -> Unit) {
    var buffer by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    TextField(
        value = buffer,
        onValueChange = { new ->
            val delta = new.text.removePrefix(buffer.text)
            if (delta.isNotEmpty()) onBytes(delta.encodeToByteArray())
            buffer = TextFieldValue("")
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onBytes(byteArrayOf(0x0D)) }),
        modifier = Modifier.size(0.dp).focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}
```

> `onBytes` 最终封装为 `WireMessage.input.raw` 发给 relay；这条
> wire message **尚未在 openspec/specs/protocol 中定稿**——实现时先在
> `openspec/changes/<id>/specs/protocol/spec.md` 增量提案，再合并到主 spec。

控制键（方向键、Ctrl、Esc）一期用叠加工具条 + `onBytes` 直接写入对应
转义序列（`\u001B[A` 等），不靠硬件键盘。

## 9. iOS 侧

- 默认复用 Compose MP 的 iOS 渲染器（`ComposeUIViewController`）。
- 若需要原生 SwiftUI 包裹，用 `UIViewControllerRepresentable`
  包 `ComposeUIViewController`，而不是反向用 `UIViewRepresentable`
  硬塞一个 Metal 层——后者复杂且无收益。
- 跨语言状态流通过 SKIE 把
  `StateFlow<TerminalState>` 导出为 Swift 原生 `AsyncSequence`：

```kotlin
// shared/src/commonMain/kotlin/io/ohmymobilecc/features/terminal/TerminalInteractor.kt
class TerminalInteractor(/* ... */) {
    val state: StateFlow<TerminalState> = _state.asStateFlow()
}
```

```swift
// iosApp
for await snapshot in terminalInteractor.state {
    renderer.apply(snapshot)
}
```

## 10. 降级到 xterm.js 的触发条件（W3 day-7 checkpoint）

**这是 plan 的 W3 风险行显式授予的逃生口。** 只有满足下列**任一**硬指
标，才启动降级工单：

1. `htop` 在 Pixel 6 / iPhone 13 上连续 30 秒刷新时帧率中位数 < 30 fps；
2. `htop` 列颜色错乱 / 行断裂无法在一次短迭代内修复；
3. ZWJ emoji（如 `👨‍👩‍👧`）在 grid 上被拆成多个 cell；
4. Compose Canvas 在 iOS 上的字形度量与 Android 偏差 > 0.5 cell。

### 降级范围

- **保留**：`AnsiParser`、`Grid`、`TerminalState`、`ClipboardPort`、
  `TerminalInteractor`、scrollback、wire message。
- **替换**：仅 `ui/terminal/TerminalCanvas.kt` → `TerminalWebView.kt`。
  - Android：`androidx.compose.ui.viewinterop.AndroidView` 包一个
    `WebView`，本地 asset 加载 xterm.js，通过 `addJavascriptInterface`
    把 `Grid` 的 dirty 行序列化为 JSON 推入。
  - iOS：`WKWebView` 包同一份 asset，通过
    `evaluateJavaScript` 投递更新。
- 输入链路不变：`TerminalInputSink` → `input.raw` → relay。

降级不是"直接扔 Compose"：我们已投资的 parser/grid/协议完整保留，只换
像素输出面。这是 plan 第 38 行、第 515–517 行、第 616 行风险表一致要
求的。

## 11. 计划文件布局

```
shared/src/commonMain/kotlin/io/ohmymobilecc/
├── core/terminal/
│   ├── AnsiParser.kt
│   ├── TerminalState.kt
│   └── Grid.kt
└── ui/terminal/
    ├── TerminalCanvas.kt       // 默认渲染层
    ├── TerminalInputSink.kt    // 不可见 TextField
    ├── TerminalScrollable.kt   // LazyColumn 包裹
    ├── CellMetrics.kt
    └── TerminalWebView.kt      // 降级后启用，二期或 W3 day-7 触发才写
```

## 12. 测试

| 层 | 工具 | 覆盖点 |
|---|---|---|
| 像素快照 (Android) | Roborazzi | `htop` 静态帧、SGR 256 色、光标位置 |
| 像素断言 (iOS) | XCUITest + `XCTAttachment` | 同上，像素容差 ≤ 2% |
| 度量单元测试 | commonTest | `CellMetrics` 在常见字号下 `cellWidth` > 0、`ceil(lineHeight)` 稳定 |
| 滚动行为 | commonTest + fake `LazyListState` | 尾部跟随、手动上滑解锁 |
| Grapheme 宽度 | commonTest | ZWJ emoji、CJK、VS15/VS16 的渲染 cell 数 |

Roborazzi 快照写法示例：

```kotlin
@RunWith(AndroidJUnit4::class)
class TerminalCanvasSnapshotTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val roborazziRule = RoborazziRule(composeRule)

    @Test fun rendersHtopFixture() {
        val state = TerminalState.fromFixture("htop_frame_01.ndjson")
        composeRule.setContent { TerminalCanvas(state, defaultConfig) }
        composeRule.onRoot().captureRoboImage("htop_frame_01.png")
    }
}
```

## 13. 显式不做（防止越界）

- ❌ Compose Web / WearOS（plan 未列入）
- ❌ Sixel 图像、mouse tracking、DCS（一期 parser 不支持）
- ❌ 远端剪贴板写入（OSC 52）
- ❌ iOS BGProcessingTask 长后台（plan 第 443 行）
- ❌ macOS Mac Catalyst（plan 第 449 行）

> 与 parser 侧的配合点全部在 `AnsiParser` 契约里；具体状态机实现见
> `docs/skills/ansi-parser-deep-dive.md`。
