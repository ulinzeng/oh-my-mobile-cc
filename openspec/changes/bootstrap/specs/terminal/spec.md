# terminal — 终端观察（delta）

## ADDED Requirements

### Requirement: ANSI/VT100 解析
系统 SHALL 通过纯 Kotlin 状态机解析 VT100/ANSI/CSI/OSC 序列，覆盖 CUP / ED / EL / SGR / DECSET 1049（备用屏幕）/ RIS 等常用控制序列。

#### Scenario: htop 渲染
- **WHEN** relay 以 PTY 模式运行 `htop`
- **AND** 输出被转发到 `AnsiParser`
- **THEN** `TerminalState.grid` SHALL 正确反映 htop 的列、颜色、选中行

### Requirement: UTF-8 与 East Asian Width
系统 SHALL 正确处理 UTF-8 在多块读取下的字符边界；CJK/Fullwidth 字符占 2 cell，emoji ZWJ 序列作为单一 grapheme cluster 占 2 cell。

#### Scenario: CJK 字符
- **WHEN** 终端输出 "你好"
- **THEN** 每个字在 `TerminalState.grid` 上占据 2 列

### Requirement: 自绘渲染
系统 SHALL 基于 Compose Multiplatform Canvas 渲染终端 grid；仅重绘 diff 行；未使用 WebView。

#### Scenario: 滚动性能
- **WHEN** 用户滚动 10k 行 scrollback buffer
- **THEN** 帧率 SHALL ≥ 30fps（目标设备：Pixel 7 / iPhone 13）

### Requirement: 渲染层降级开关
系统 SHALL 允许在 W3 第 7 天 checkpoint 后通过编译 flag 切换到 xterm.js WebView 渲染；parser 层与 grid 数据模型保留不变。

#### Scenario: 切换到 fallback
- **WHEN** 构建配置启用 `-Pterminal.renderer=webview`
- **THEN** `TerminalScreen` 使用 xterm.js WebView；`AnsiParser` 不参与渲染但仍用于 scrollback 存档

### Requirement: 只读策略（一期）
系统 SHALL 在一期将终端 UI 限定为**观察态**：手机端不向 relay 发送键盘输入。
（键盘输入列为 W4 打磨项；spec 后续 change 再扩展。）

#### Scenario: 尝试输入
- **WHEN** 用户在终端屏幕聚焦并按键
- **THEN** UI 显示 toast "Terminal is read-only in this release"
