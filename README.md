<div align="center">

<img src="assets/banner.svg" alt="WindyPurchase" width="100%">

</div>

# WindyPurchase

![Language](https://img.shields.io/badge/Kotlin-2.x-7f52ff?style=flat-square&logo=kotlin&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-Velocity-1e5c97?style=flat-square&logo=velocity&logoColor=white)
![Pay](https://img.shields.io/badge/Alipay%20%2F%20WeChat-Native%20Pay-22c55e?style=flat-square)
![Storage](https://img.shields.io/badge/Storage-SQLite-4479a1?style=flat-square)

Minecraft 游戏内支付插件，支持支付宝当面付和微信 Native 支付。

## 模块

| 模块 | 说明 |
|------|------|
| `core` | 平台无关核心（订单/商品模型、支付接口抽象、SQLite 数据库、配置） |
| `velocity` | Velocity 代理端插件 + 内嵌 HTTP REST API 服务端 |
| `bukkit` | Bukkit 预留（暂空壳） |

## 功能

- 玩家在游戏内通过 `/purchase` 命令下单，获取支付二维码
- 支持支付宝当面付（预创建订单）和微信 Native 支付
- 支付完成后自动执行奖励命令（支持 `%player%` 占位符）
- 充值排行榜、订单历史查询
- REST API 供前端 Dashboard 调用
- 支付宝/微信异步回调 + 轮询双保险
- 商品配置热重载

## 命令

### 玩家命令

| 命令 | 说明 |
|------|------|
| `/purchase list` | 查看商品列表 |
| `/purchase buy <商品ID> [alipay\|wechat]` | 购买商品 |
| `/purchase pay <金额> [alipay\|wechat]` | 直接充值（元） |
| `/purchase history` | 查看订单记录 |
| `/purchase check <订单ID>` | 查询订单状态 |

### 管理命令（需 `windypurchase.admin` 权限）

| 命令 | 说明 |
|------|------|
| `/purchaseadmin reload` | 重载商品配置 |
| `/purchaseadmin stats` | 充值统计 |
| `/purchaseadmin check <订单ID>` | 查看订单详情 |
| `/purchaseadmin products` | 商品列表 |

## REST API

所有 API 需在 `config.yml` 中配置 `http.token`，通过 `X-Token` header 或 `?token=` 参数鉴权。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/orders` | 创建订单 |
| GET | `/api/orders/{id}` | 查询订单 |
| POST | `/api/orders/{id}/close` | 关闭订单 |
| GET | `/api/orders/player/{uuid}` | 玩家订单历史 |
| GET | `/api/products` | 商品列表 |
| GET | `/api/products/{id}` | 商品详情 |
| GET | `/api/revenue` | 充值统计 |
| GET | `/api/revenue/ranking` | 充值排行 |
| POST | `/api/notify/alipay` | 支付宝回调 |
| POST | `/api/notify/wechat` | 微信回调 |
| GET | `/api/health` | 健康检查 |

## 构建

```bash
./gradlew build
```

产出 jar: `velocity/build/libs/windypurchase-1.0-SNAPSHOT.jar`

## 配置

首次启动会自动生成 `config.yml` 和 `products.yml`。

### config.yml

配置支付宝/微信凭证、HTTP 端口、消息模板等。详见文件内注释。

### products.yml

定义可购买商品，支持价格、命令、权限、冷却时间。

## 技术栈

- Kotlin
- JDK HttpServer（内嵌 REST API，零依赖）
- SQLite（本地数据库）
- 支付宝 SDK v2
- 微信支付 Java SDK
- ZXing（二维码生成）
