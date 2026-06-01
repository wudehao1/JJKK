# JJKK - 基金快看（前后端一体仓库）

JJKK 是一个面向基金投资场景的实时行情与持仓辅助系统，包含：

- `backend/`：Spring Boot 后端（当前代码在仓库根目录）
- `frontend/`：UniApp 前端（小程序/H5）

本仓库重点能力：

- 基金分时估算与收盘校准
- 自选与持仓管理
- 市场概览、资讯、板块排行
- Redis 分时缓存 + MySQL 持久化
- WebSocket 实时推送

## 目录结构

```text
.
├─ src/                      # 后端源码（Spring Boot）
├─ frontend/                 # 前端源码（UniApp）
├─ docs/                     # 设计与接口文档
├─ tools/                    # 维护脚本
├─ pom.xml
└─ README.md
```

## 技术栈

- 后端：Java 17, Spring Boot 3, JDBC, Redis, WebSocket, MySQL
- 前端：UniApp Uvue
- 调度：Spring Scheduler（分钟级刷新）

## 快速启动

### 1) 后端

1. 复制 `.env.example` 为本地环境变量配置（或直接在系统环境变量中设置）
2. 准备 MySQL 与 Redis
3. 初始化数据库：`src/main/resources/db/schema.sql`
4. 启动：

```powershell
mvn -DskipTests spring-boot:run
```

默认地址：`http://localhost:8080/api`

### 2) 前端

前端目录：`frontend/`

在 `frontend/common/api.js` 配置后端地址后，使用 HBuilderX/UniApp 工具运行。

## 分支与发布规范

为确保“每次变更可回滚”，采用以下策略：

1. `main` 只接收稳定代码
2. 每次开发新建分支：`feature/<日期>-<主题>` 或 `fix/<日期>-<主题>`
3. 分支完成后发起 PR 合并到 `main`
4. 合并后打 tag（可选）：`vX.Y.Z`

详细流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。
可回滚操作细则见 [docs/git_branch_workflow.md](docs/git_branch_workflow.md)。

## 文档

- 设计文档：[docs/fund_quick_look_design.md](docs/fund_quick_look_design.md)
- 后端实现说明：[docs/backend_function_implementation.md](docs/backend_function_implementation.md)
- API 文档：`docs/api/`

## 安全说明

- 仓库不保存真实密钥，请通过环境变量注入：
  - `JJKK_WECHAT_APP_ID`
  - `JJKK_WECHAT_APP_SECRET`
  - `JJKK_DB_PASSWORD`
  - `JJKK_REDIS_PASSWORD`
