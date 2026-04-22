# file-sync Specification

## Purpose
配对完成后，移动端可 **按需** 浏览和读取桌面 relay 工作区内的文件，
并以 Myers 算法计算增量 diff 传输。
本 capability 限定文件 ≤ 2 MB、受 relay 白名单沙箱约束，
**不** 涵盖大文件编辑、版本化同步或双向写入冲突合并。
## Requirements
### Requirement: 目录浏览
系统 SHALL 允许移动端请求 relay 上某 path 的目录快照（`WireMessage.FileListRequest(path)` → `FileListResponse(entries)`），其中每个 entry 含 `(name, kind, size, mtime)`。

#### Scenario: 列目录
- **WHEN** 客户端发送 `FileListRequest("/Users/me/repo")`
- **THEN** relay 回以按名字排序的 `FileListResponse`，隐藏文件受 `show_hidden` 参数控制

### Requirement: 只读文件读取
系统 SHALL 支持文件 raw 字节获取，**单次最大 2 MB**；超过 SHALL 回以 `FileTooLarge(path, size)` 错误事件。

#### Scenario: 大文件拒绝
- **WHEN** 客户端对一个 10 MB 的 PDF 发 `FileReadRequest`
- **THEN** relay 回 `FileError.FileTooLarge`，不返回内容

### Requirement: 增量编辑（Myers diff）
系统 SHALL 通过 `FilePatchRequest(path, patch_ops)` 接受基于 Myers 算法的文本补丁；服务端在应用前 SHALL 校验 `base_sha256` 与当前文件一致，否则拒绝以 `ConflictError`。

#### Scenario: 冲突检测
- **WHEN** 客户端提交的 `base_sha256` 与 relay 上文件的 sha256 不匹配
- **THEN** relay 回 `ConflictError(path, server_sha256)`，不写入

### Requirement: 二进制文件豁免
系统 SHALL 拒绝对非文本文件的 patch 请求（通过 magic bytes 判定），回 `UnsupportedMediaError`。

#### Scenario: 对 PNG 发 patch
- **WHEN** 客户端对 `.png` 文件发 `FilePatchRequest`
- **THEN** relay 回 `UnsupportedMediaError`

### Requirement: 路径沙箱
系统 SHALL 只允许访问 relay 启动时通过 `--sandbox-root` 指定的目录子树；任何 `..` 穿透 SHALL 被拒绝。

#### Scenario: 路径穿透
- **WHEN** 客户端请求 `/Users/me/repo/../../etc/passwd`
- **THEN** relay 回 `ForbiddenError`，审计日志记录 `session_id` 与尝试路径

