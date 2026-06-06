# 基金快看 JJKK

基金快看是一个面向基金投资场景的微信小程序，提供实时大盘行情、基金估值追踪、自选管理、模拟交易、盈亏分析和市场资讯。

## 仓库结构

| 分支 | 内容 | 技术栈 |
|------|------|--------|
| **main** | 后端服务 | Java 17, Spring Boot 3, MySQL, Redis, WebSocket |
| **uniapp** | 前端小程序 | uni-app x (Uvue), 微信小程序 |

## 核心功能

- **大盘行情**：覆盖 A 股、港股、美股、黄金等 14 个核心指数，分时/历史走势
- **基金估值**：分钟级估算净值，收盘后自动校正为官方净值
- **自选管理**：搜索基金（本地库 + 官方同步）、添加、排序、删除
- **模拟交易**：买入/卖出、多账户管理、交易流水、等待确认机制
- **盈亏分析**：日内分时、收益走势、盈亏日历、明细拆解
- **市场资讯**：实时快讯、重要标记、详情阅读
- **价格提醒**：自定义涨跌幅/净值目标，多种提醒时间模式
- **弹幕互动**：基金分时图上叠加用户弹幕
- **深色模式**：全局明暗主题切换

## 技术架构

`
微信小程序 (uni-app x)
       |
       | REST + WebSocket
       v
Spring Boot 后端
       |
       +---> MySQL (基金数据、用户数据、交易流水)
       +---> Redis (分时缓存、认证 Token、实时推送)
       +---> 第三方数据源 (东方财富、新浪、腾讯)
`

## 后端启动

### 环境要求

- Java 17+
- MySQL 8
- Redis

### 配置

通过环境变量设置（或修改 application.yml）：

`
JJKK_DB_URL=jdbc:mysql://localhost:3306/fund_quick_look_jk
JJKK_DB_USERNAME=root
JJKK_DB_PASSWORD=your_password
JJKK_REDIS_HOST=localhost
JJKK_REDIS_PORT=6379
JJKK_REDIS_PASSWORD=your_password
JJKK_WECHAT_APP_ID=your_app_id
JJKK_WECHAT_APP_SECRET=your_app_secret
`

### 初始化数据库

`sql
source src/main/resources/db/schema.sql
`

### 启动

`powershell
mvn -DskipTests spring-boot:run
`

默认地址：http://localhost:8080/api

## 前端启动

切换到 uniapp 分支获取前端代码：

`powershell
git checkout uniapp
`

在 common/api.js 中配置后端地址，然后使用 HBuilderX 或微信开发者工具运行。

## 定时任务

| 时间 | 说明 |
|------|------|
| 交易日 9:00-15:00 每分钟 | 刷新大盘分时 + 活跃基金估值 |
| 交易日 19:00-23:00 每半小时 | 用官方净值校正基金估值 |
| 每天 00:00 | Redis 分时数据落 MySQL |
| 每 10 分钟 | 刷新市场资讯 |

## 文档

- 设计文档：docs/fund_quick_look_design.md
- 后端实现说明：docs/backend_function_implementation.md
- API 文档：docs/api/

## 安全说明

敏感配置通过环境变量注入，仓库不保存真实密钥。
