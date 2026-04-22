---
name: ansi-parser-deep-dive
description: VT100/ANSI/CSI/OSC 状态机实现指南，覆盖 UTF-8/CJK/emoji 宽度判定
triggers: ["ansi", "vt100", "csi", "osc", "escape sequence", "terminal parser", "ansi-parser", "CJK width"]
related-specs: [openspec/specs/terminal/spec.md]
---

# ANSI Parser Deep Dive

> 适用范围：当任务涉及 `shared/src/commonMain/kotlin/io/ohmymobilecc/core/terminal/`
> 下的 VT100/ANSI 解析器、UTF-8 解码、单元宽度判定、或者为 `htop` / `vim`
> 这类真实 TUI 程序排错时加载本文。

## 1. 为什么需要一个"正确"的 ANSI 状态机

一期终端目标是跑通 `htop` 与 `vim` 两类基线 TUI。二者在 relay 端由本机
`claude -p` 间接触发的 PTY 输出中会混合下列序列：

- C0 控制字符（`0x00–0x1F`）、DEL（`0x7F`）
- CSI（`ESC [`）参数化控制序列
- OSC（`ESC ]`）操作系统控制串
- 可打印 UTF-8 文本，含 CJK、emoji、ZWJ 组合
- 偶发的 C1（`ESC @` 至 `ESC _`）与 DCS（本期**不实现**）

把"按字节逐个前向推进的解析器"写成经验式 `if/else` 基本一定翻车：
交错状态、多字节 UTF-8 在 chunk 边界被切断、`ESC` 内嵌 `ESC`。我们以
**Paul Williams 的 DEC 解析器状态机** 为蓝本（见 `vt100.net/emu/dec_ansi_parser`），
它是业界公认的权威参考，xterm / kitty / alacritty 都以此为根基。

## 2. 状态机概览

```
GROUND ──ESC──▶ ESCAPE ──[──▶ CSI_ENTRY ─digit──▶ CSI_PARAM
  ▲               │                                    │
  │               ├──]──▶ OSC_STRING                   │ intermediate (0x20–0x2F)
  │               │                                    ▼
  │               └──final (0x30–0x7E)──▶ GROUND  CSI_INTERMEDIATE
  │                                                    │
  └──────────────────────── final ─────────────────────┘
                                                       │ invalid
                                                       ▼
                                                   CSI_IGNORE
```

本期我们实现的状态：

| State | 触发方式 | 职责 |
|---|---|---|
| `GROUND` | 默认 | 把可打印字节投递给文本收集器 |
| `ESCAPE` | `ESC` (0x1B) | 分派到 CSI / OSC / C1 处理 |
| `CSI_ENTRY` | `ESC [` | 读 0–n 个参数（分号分隔） |
| `CSI_PARAM` | 读到数字/`;`/`:` | 累积参数 |
| `CSI_INTERMEDIATE` | 读到 `0x20–0x2F` | 累积中间字节（私有序列） |
| `CSI_IGNORE` | 非法字节 | 一路吞到 final 字节 |
| `OSC_STRING` | `ESC ]` | 一路收集到 `BEL` (0x07) 或 `ST` (`ESC \`) |

> **实现顺序约束**：必须先用 VT500 fixture 写 RED 用例（Task W3.1），
> 再实现这张转移表。Pure 函数式 transition 函数最利于表驱动测试。

## 3. 必须正确处理的 CSI 序列

`htop` 与 `vim` 用到的最小闭包：

| 序列 | 语义 | 在我们 Grid 上的行为 |
|---|---|---|
| `CSI n A/B/C/D` | Cursor up/down/forward/back | 光标移动 |
| `CSI row ; col H` / `f` | CUP — Cursor Position | 跳到 (row,col)，1-based |
| `CSI n J` | ED — Erase Display | 0=cursor→end / 1=begin→cursor / 2=all / 3=all+scrollback |
| `CSI n K` | EL — Erase Line | 0/1/2 同上但行内 |
| `CSI n ; … m` | SGR — Select Graphic Rendition | 颜色 / 粗体 / 下划线 / 反显 |
| `CSI ? 1049 h` / `l` | DECSET/DECRST Alternate Screen Buffer | 切换主/备屏（htop、vim 必需） |
| `CSI ? 25 h` / `l` | Show/Hide Cursor | 控制渲染层光标 |
| `CSI c` | `RIS` via `ESC c` | 重置所有属性 + 清屏 |
| `CSI s` / `CSI u` | Save/Restore Cursor | 辅助配合备屏 |

SGR 要支持的属性参数：

- `0` reset
- `1` bold / `2` dim / `3` italic / `4` underline / `7` reverse / `9` strike
- `30–37` / `90–97` fg, `40–47` / `100–107` bg
- `38;5;n` / `48;5;n` 256 色
- `38;2;r;g;b` / `48;2;r;g;b` truecolor

> 显式**不实现**：Sixel、REP、DCS、mouse tracking 扩展格式（一期不做）。

### Kotlin 表达示例

```kotlin
internal sealed interface CsiCommand {
    data class CursorPosition(val row: Int, val col: Int) : CsiCommand
    data class EraseDisplay(val mode: Int) : CsiCommand
    data class EraseLine(val mode: Int) : CsiCommand
    data class Sgr(val params: IntArray) : CsiCommand
    data class SetPrivateMode(val code: Int, val enable: Boolean) : CsiCommand
    object SaveCursor : CsiCommand
    object RestoreCursor : CsiCommand
    data class Unknown(val final: Char, val params: IntArray) : CsiCommand
}
```

## 4. OSC：要什么、拒什么

OSC 以 `ESC ]` 开头，以 `BEL` 或 `ST` 结尾。

| OSC | 语义 | 本期策略 |
|---|---|---|
| `0` / `2` | 设置窗口标题 | 投递到 `TerminalState.title` 供 UI 显示 |
| `8` | 超链接 | 解析但不渲染下划线链接（后续） |
| `52` | 剪贴板读写 | **忽略**（安全）：远端进程不应操作手机剪贴板 |
| 其它 | — | 丢弃，记 `kermit.d { "unhandled OSC $code" }` |

## 5. UTF-8 解码与 chunk 边界

relay 从 PTY 以 byte stream 推送，单个多字节字符可能横跨两个 `ByteArray`。
解析器必须维护一个最多 3 字节的 **carry-over buffer**：

```kotlin
class Utf8Decoder {
    private val carry = ByteArray(3)
    private var carryLen = 0

    /** Feed bytes; emit decoded code points via [out]. Returns bytes consumed. */
    fun feed(chunk: ByteArray, out: (Int) -> Unit) {
        var i = 0
        while (i < chunk.size) {
            val first = chunk[i].toInt() and 0xFF
            val expected = when {
                first and 0x80 == 0x00 -> 1
                first and 0xE0 == 0xC0 -> 2
                first and 0xF0 == 0xE0 -> 3
                first and 0xF8 == 0xF0 -> 4
                else -> { out(REPLACEMENT); i++; continue }
            }
            if (i + expected > chunk.size) {
                chunk.copyInto(carry, destinationOffset = 0, startIndex = i, endIndex = chunk.size)
                carryLen = chunk.size - i
                return
            }
            out(decodeCodePoint(chunk, i, expected))
            i += expected
        }
    }

    private companion object { const val REPLACEMENT = 0xFFFD }
}
```

边界用例（必须有 RED 测试）：

- `"中"` = `E4 B8 AD` 被切成 `[E4]` + `[B8, AD]`
- 非法起始字节（单独的 `0x80`）→ 插入 U+FFFD
- 过长编码（`C0 80` 伪编码 NUL）→ U+FFFD，不允许回绕

## 6. 宽度判定（cell 宽 1 还是 2）

终端渲染的本质是"格子"，因此我们必须为每个**字形簇（grapheme cluster）**
决定 1/2 列宽。规范参考：

- `unicode.org/reports/tr11`（East Asian Width, EAW）
- `unicode.org/reports/tr29`（Grapheme Cluster Boundaries）
- `unicode.org/Public/emoji/15.1/emoji-data.txt`

规则（本期锁定 Unicode 15.1）：

| EAW 类别 | 列宽 |
|---|---|
| `F` Fullwidth | 2 |
| `W` Wide（含大多数 CJK） | 2 |
| `H` Halfwidth / `Na` Narrow / `N` Neutral | 1 |
| `A` Ambiguous | 平台可配置，默认 1；`TerminalConfig.ambiguousIsWide` 切 2 |

Emoji 特殊规则：

- 基础 emoji + VS16（`U+FE0F`）→ 2
- VS15（`U+FE0E`）→ 按底字符 EAW（通常 1）
- ZWJ 序列（如 👨‍👩‍👧）整体作为**一个** grapheme cluster，宽度 = 2
- Skin tone modifier（`U+1F3FB..1F3FF`）并入前一个 cluster，不单独占列

### 数据加载策略

不在运行时解析 `EastAsianWidth.txt`，而是在构建期用脚本（`shared/build/gen-width-table.kts`）
压成 **区间表**（`IntArray` + 二分查找）嵌入源码：

```kotlin
internal object Width {
    // pairs of [startCodePoint, endCodePoint] sorted, all width-2
    private val WIDE = intArrayOf(
        0x1100, 0x115F,
        0x2E80, 0x303E,
        0x3041, 0x33FF,
        // ... generated
    )

    fun columns(cp: Int, ambiguousIsWide: Boolean): Int {
        if (cp < 0x20) return 0
        if (cp in 0x300..0x36F) return 0 // combining marks
        if (isWide(cp)) return 2
        if (ambiguousIsWide && isAmbiguous(cp)) return 2
        return 1
    }

    private fun isWide(cp: Int): Boolean { /* binary search WIDE */ TODO() }
    private fun isAmbiguous(cp: Int): Boolean = TODO()
}
```

## 7. 规划的 Kotlin 目录结构

```
shared/src/commonMain/kotlin/io/ohmymobilecc/core/terminal/
├── AnsiParser.kt        // 纯状态机，emit ParsedEvent sealed
├── Utf8Decoder.kt       // chunk-safe UTF-8 decoder
├── Width.kt             // EAW + emoji cluster 宽度
├── Grapheme.kt          // TR29 cluster boundary
├── TerminalState.kt     // 光标、属性、备屏标记
└── Grid.kt              // rows × cols cell buffer + dirty tracking
```

`AnsiParser` 对外接口应保持 pure：

```kotlin
class AnsiParser {
    private var state: ParserState = ParserState.Ground
    private val params = IntArray(16)
    private var paramCount = 0
    private val oscBuf = StringBuilder()

    /** Feed one decoded code point; emit zero or more [ParsedEvent]. */
    fun step(cp: Int): List<ParsedEvent> = when (state) {
        ParserState.Ground -> ground(cp)
        ParserState.Escape -> escape(cp)
        ParserState.CsiEntry -> csiEntry(cp)
        ParserState.CsiParam -> csiParam(cp)
        ParserState.CsiIntermediate -> csiIntermediate(cp)
        ParserState.CsiIgnore -> csiIgnore(cp)
        ParserState.OscString -> oscString(cp)
    }
}

sealed interface ParsedEvent {
    data class Print(val codePoint: Int) : ParsedEvent
    data class Execute(val byte: Byte) : ParsedEvent     // C0 like \n \r \b
    data class Csi(val cmd: CsiCommand) : ParsedEvent
    data class Osc(val code: Int, val payload: String) : ParsedEvent
}
```

`Grid.apply(event: ParsedEvent)` 在另一文件中实现，保证 parser 不感知屏幕。

## 8. 测试策略（RED 先行）

最小测试矩阵放在 `shared/src/commonTest/kotlin/io/ohmymobilecc/core/terminal/`：

1. **VT500 parser harness fixtures**  
   `resources/fixtures/vt500/` 放若干 `.in` / `.events.json`，
   对照 Paul Williams 的参考事件流做 round-trip 断言。
2. **UTF-8 boundary tests**  
   喂一个三字节字符、逐字节 `step`，期望恰好最后一字节之后 emit 一个 `Print`。
3. **CJK 宽度表**  
   `"字"` → 2，`"a"` → 1，`"㈠"`（ambiguous）默认 1，配置开关后 2。
4. **ZWJ emoji**  
   `"👨\u200D👩\u200D👧"` 整体应作一个 cluster、宽 2、占 1 格位的 Grid cell。
5. **Alternate screen**  
   `CSI ? 1049 h` → 备屏、清屏；`CSI ? 1049 l` → 回主屏，主屏内容未丢。
6. **SGR 256 & truecolor**  
   `CSI 38;5;200 m` 和 `CSI 38;2;10;20;30 m` 都能正确落到 `CellAttr.fg`.

```kotlin
class AnsiParserTest {
    @Test fun `CUP positions cursor one-based`() {
        val p = AnsiParser()
        val events = "\u001B[5;10H".codePoints().flatMap { p.step(it) }
        assertEquals(
            ParsedEvent.Csi(CsiCommand.CursorPosition(row = 5, col = 10)),
            events.single()
        )
    }
}
```

## 9. 参考链接

- Paul Williams DEC ANSI parser: <https://vt100.net/emu/dec_ansi_parser>
- xterm ctlseqs: <https://invisible-island.net/xterm/ctlseqs/ctlseqs.html>
- UAX #11 East Asian Width: <https://unicode.org/reports/tr11>
- UAX #29 Grapheme Cluster Boundaries: <https://unicode.org/reports/tr29>
- Unicode emoji 15.1 data: <https://unicode.org/Public/emoji/15.1/emoji-data.txt>

> 本 skill 聚焦 parser 层；渲染侧（字体度量、Canvas 绘制、降级到
> xterm.js 的触发条件）见 `docs/skills/compose-canvas-terminal.md`。
