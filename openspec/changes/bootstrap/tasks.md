# tasks.md — bootstrap

## 1. 文档基础设施（W0.1–W0.5）

- [x] 1.1 `openspec init` 完成
- [x] 1.2 `openspec/project.md` 填充中文技术栈 + 约定
- [x] 1.3 根目录 `.gitignore` 就位
- [x] 1.4 Gradle 骨架（settings + root build + libs.versions.toml + shared/androidApp/relay）
- [x] 1.5 `.claude/scripts/session-end-docs.sh` + `gen-codemaps.sh` + `settings.local.json` 注册 SessionEnd hook
- [x] 1.6 4 篇 skill 文档（openspec-workflow / stream-json-protocol / ansi-parser-deep-dive / compose-canvas-terminal）
- [x] 1.7 合成 permission NDJSON fixture + README 告警

## 2. 声明 5 个 capability 的初版 requirements

- [x] 2.1 `specs/protocol/spec.md`
- [x] 2.2 `specs/approval-inbox/spec.md`
- [x] 2.3 `specs/terminal/spec.md`
- [x] 2.4 `specs/file-sync/spec.md`
- [x] 2.5 `specs/pairing/spec.md`

## 3. 顶层文档

- [x] 3.1 README.md / README.zh-CN.md
- [x] 3.2 AGENTS.md（中文 agent 工作流）
- [x] 3.3 3 份 ADR（0001 KMP / 0002 Tailscale / 0003 Inbox vs APNs）

## 4. 合规

- [x] 4.1 `openspec validate bootstrap --strict` 绿
- [ ] 4.2 提交 `feat/w0-bootstrap` 分支并创建 PR
- [ ] 4.3 合并到 main 后 `openspec archive bootstrap`

> 注：4.2/4.3 由 `superpowers:finishing-a-development-branch` 在 W0 收尾时推进。
