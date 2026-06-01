# Contributing Guide

## 分支命名

- 新功能：`feature/<yyyymmdd>-<short-topic>`
- 修复：`fix/<yyyymmdd>-<short-topic>`
- 文档：`docs/<yyyymmdd>-<short-topic>`

示例：`feature/20260601-realtime-estimate-upgrade`

## 标准流程

1. 从 `main` 拉取最新代码
2. 创建新分支开发
3. 本地自测（后端至少通过编译）
4. 提交并推送分支
5. 发起 Pull Request 到 `main`
6. 通过 Review 后合并

## 合并策略

- 推荐 `Squash and merge`，保证主分支历史清晰
- 紧急修复可 `Rebase and merge`

## 提交建议

- 每次提交聚焦单一目标
- 提交信息建议：
  - `feat: ...`
  - `fix: ...`
  - `docs: ...`
  - `refactor: ...`

