# JJKK_2 第一版接口说明

默认配置：

- REST 基础地址：`http://localhost:8080/api`
- WebSocket 地址：`ws://localhost:8080/api/ws/funds`
- 数据库：`fund_quick_look_jk`

环境变量：

- `JJKK_DB_URL`：MySQL 连接地址。
- `JJKK_DB_USERNAME`：MySQL 用户名，默认 `root`。
- `JJKK_DB_PASSWORD`：MySQL 密码，默认空。
- `JJKK_REDIS_HOST`：Redis 地址，默认 `localhost`。
- `JJKK_REDIS_PORT`：Redis 端口，默认 `6379`。
- `JJKK_SERVER_PORT`：后端端口，默认 `8080`。

启动：

```bash
mvn spring-boot:run
```

## 用户

创建或更新小程序用户：

```http
POST /api/users
Content-Type: application/json

{
  "wechatOpenid": "dev-openid-001",
  "nickname": "测试用户"
}
```

查询用户：

```http
GET /api/users/{userId}
```

第一版暂时用 `userId` 对接自选和持有；接微信登录后，后端会用登录态解析用户，不需要前端显式传 `userId`。

## 基金资料

搜索基金：

```http
GET /api/funds?keyword=沪深&page=1&size=20
```

查询基金详情：

```http
GET /api/funds/{fundCode}
```

录入基金：

```http
POST /api/funds
Content-Type: application/json

{
  "fundCode": "000001",
  "fundName": "示例基金A",
  "fundShortName": "示例基金A",
  "shareClassCode": "A",
  "fundType": "MIXED",
  "operationMode": "OPEN",
  "riskLevel": "R3",
  "currency": "CNY",
  "companyName": "示例基金管理有限公司",
  "custodianName": "示例银行股份有限公司"
}
```

更新基金：

```http
PUT /api/funds/{fundCode}
Content-Type: application/json

{
  "fundName": "示例基金A更新",
  "purchaseStatus": "OPEN",
  "redeemStatus": "OPEN"
}
```

删除基金是软删除，实际把份额状态改为 `TERMINATED`：

```http
DELETE /api/funds/{fundCode}
```

## 自选基金

查询自选：

```http
GET /api/users/{userId}/watchlist
```

加入自选：

```http
POST /api/users/{userId}/watchlist
Content-Type: application/json

{
  "fundCode": "000001",
  "groupName": "重点关注",
  "displayOrder": 1,
  "remark": "先观察估值波动",
  "alertEnabled": true
}
```

更新自选：

```http
PUT /api/users/{userId}/watchlist/{watchId}
Content-Type: application/json

{
  "displayOrder": 2,
  "remark": "改为普通关注"
}
```

删除自选：

```http
DELETE /api/users/{userId}/watchlist/{watchId}
```

## 持有基金

查询持有：

```http
GET /api/users/{userId}/holdings
```

录入持有：

```http
POST /api/users/{userId}/holdings
Content-Type: application/json

{
  "fundCode": "000001",
  "holdingShare": 1000.0000,
  "costAmount": 1200.00,
  "avgCostNav": 1.200000,
  "firstBuyDate": "2026-05-12"
}
```

不传 `accountName` 时，后端自动创建或复用“默认账户”。

更新持有：

```http
PUT /api/users/{userId}/holdings/{holdingId}
Content-Type: application/json

{
  "holdingShare": 1200.0000,
  "costAmount": 1400.00
}
```

删除持有是软删除：

```http
DELETE /api/users/{userId}/holdings/{holdingId}
```

### 持有模拟交易（新增）

持有汇总：

```http
GET /api/users/{userId}/holdings/summary
Authorization: Bearer {token}
```

返回字段包含：
- `totalCostAmount` 总成本
- `totalMarketValue` 估算总市值
- `totalProfitLossAmount` 估算总盈亏
- `totalProfitLossPct` 估算总盈亏比例
- `holdingCount` 持仓条目数
- `activeHoldingCount` 有效持仓条目数

持有交易流水：

```http
GET /api/users/{userId}/holdings/transactions?fundCode=021894&accountId=1&limit=50
Authorization: Bearer {token}
```

参数都可选：
- `fundCode` 按基金过滤
- `accountId` 按账户过滤
- `limit` 返回条数（默认 50，最大 200）

模拟买入/卖出：

```http
POST /api/users/{userId}/holdings/trades
Authorization: Bearer {token}
Content-Type: application/json

{
  "fundCode": "021894",
  "txnType": "BUY",
  "accountId": 1,
  "txnDate": "2026-05-26",
  "confirmDate": "2026-05-26",
  "amount": 1000.00,
  "fee": 1.50
}
```

说明：
- `txnType` 只支持 `BUY` / `SELL`。
- `amount` 与 `share` 至少传一个；`nav` 不传时后端自动取该基金最新估算净值/最新净值。
- 成交后会同时更新 `user_fund_holding` 与 `user_holding_txn`，返回“成交记录 + 最新持仓”。

## WebSocket

连接：

```text
ws://localhost:8080/api/ws/funds
```

心跳：

```json
{"type":"PING"}
```

订阅基金：

```json
{"type":"SUBSCRIBE","fundCodes":["000001","000002"]}
```

取消订阅：

```json
{"type":"UNSUBSCRIBE","fundCodes":["000001"]}
```

当前 WebSocket 已支持连接、心跳和订阅关系维护。下一步接入估算引擎后，由 Redis Stream 或 Pub/Sub 将 `fund:estimate:latest:{fundCode}` 的变化推送到这些订阅会话。
