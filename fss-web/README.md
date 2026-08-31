# fss-web · 秒杀系统演示前端

Vue 3 + Vite + TypeScript + Element Plus。独立进程跑，通过 Vite 代理打后端，
**后端零改动**（不需要开 CORS，不需要改 `JwtAuthFilter` 白名单）。

## 启动

后端按根 README 的「快速开始」起在 8080，然后：

```bash
cd fss-web
npm install
npm run dev          # http://localhost:5173
```

后端端口不是 8080 时用环境变量覆盖：

```bash
FSS_API=http://127.0.0.1:8081 npm run dev
```

演示账号 `admin` / `demo1` / `demo2` / `demo3`，密码统一 `Passw0rd1`
（由 `DevDataInitializer` 在 dev profile 下创建）。

## 四个页面

| 页面 | 看什么 |
| --- | --- |
| 秒杀大厅 | 服务端时间校准的倒计时、Redis 近似库存、**抢购链路四步可视化**（领令牌 → 提交 → 退避轮询 → 结论）、每次轮询的间隔与结论 |
| 我的订单 | 服务端下发的 `remainSeconds` 倒计时、模拟支付（创建流水 → 换合法签名 → 自投回调）、取消订单与「取消后不能再抢」 |
| 链路观测 | 本次会话所有请求的时间线：阶段标签、HTTP 状态、业务码、RT、可复制的 `traceId`；降级开关实时状态；Grafana / Prometheus / Swagger 入口 |
| 管理控制台 | 一键造活动（商品 → SKU → 活动 → 发布 → 预热）、活动运维、库存调整、降级等级设置 |

## 几个刻意的选择

- **用 Vite 代理而不是给后端开 CORS**：跨域策略只在开发期成立，写进后端配置后
  很容易被误当成线上也这样。代理让这件事完全留在前端工程里。
- **轮询间隔不硬编码**：第一档用服务端下发的 `pollAfterMs`，之后
  500 / 800 / 1300 / 2000ms 退避。降级到 L2 时服务端把第一档从 300 拉到 2000，
  前端不发版就跟着变。
- **JWT 存 localStorage**：为了刷新页面不用重登，是演示取向的决定。
  生产环境对 XSS 没有防护，应换成 HttpOnly Cookie + CSRF token。
- **业务失败只看 `code` 不看 HTTP 状态**：后端约定业务失败一律 200 + 非 0 code，
  只有 401 / 403 / 429 / 503 才用非 200。

## 已知限制

- 库存调整要手填 `t_seckill_goods.id`：活动详情接口只下发 `skuId`，没有这个 ID。
- 没有对账任务面板：`docs/06-接口契约` 里列的
  `GET /api/admin/reconcile/tasks` 尚未实现，对账结果目前只能查 `t_reconcile_task` 表。
- `npm run build` 产物没有接到 Maven 构建里。演示按上面的两进程方式起。

## 命令

```bash
npm run dev         # 开发服务器
npm run typecheck   # vue-tsc 类型检查
npm run build       # 类型检查 + 生产构建到 dist/
```
