# fss-web · 秒杀系统演示前端

Vue 3 + Vite + TypeScript + Element Plus。两种跑法，**后端都零改动**
（不需要开 CORS，不需要改 `JwtAuthFilter` 白名单）——开发期靠 Vite proxy，
容器里靠同镜像内的 Nginx 反代，前端代码始终只打相对路径 `/api`。

## 跑法一：跟整套一起进容器（演示用）

在仓库根目录：

```bash
docker compose --profile all up -d --build
```

前端在 <http://localhost:8081>，Nginx 把 `/api` 与 `/actuator` 反代到 `app:8080`。
镜像是两段构建（`node:24-alpine` 跑 `vue-tsc + vite build` → `nginx:1.27-alpine`
只留 `dist`），成品约 60MB。**类型不过构建就失败**，这是刻意的。

## 跑法二：本地 dev server（改前端时用，有热更新）

后端按根 README 起在 8080，然后：

```bash
npm install
npm run dev          # http://localhost:5173
```

后端端口不是 8080 时用环境变量覆盖：

```bash
FSS_API=http://127.0.0.1:8082 npm run dev
```

容器化的前端占 8081、dev server 占 5173，**两个端口刻意不撞**：
常见用法是容器里跑后端、宿主机跑 Vite 热更新，同时开着。

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

- **开发用 Vite 代理、容器用 Nginx 反代，而不是给后端开 CORS**：跨域策略只在
  开发期成立，写进后端配置后很容易被误当成线上也这样。两种模式都把这件事
  留在前端这一侧。
- **history 路由 + Nginx `try_files` 回退**：少了那条回退，直接访问 `/orders`
  会 404，而且只有刷新页面时才遇到——最容易漏测的一类问题。
- **轮询间隔不硬编码**：第一档用服务端下发的 `pollAfterMs`，之后
  500 / 800 / 1300 / 2000ms 退避。降级到 L2 时服务端把第一档从 300 拉到 2000，
  前端不发版就跟着变。
- **JWT 存 localStorage**：为了刷新页面不用重登，是演示取向的决定。
  生产环境对 XSS 没有防护，应换成 HttpOnly Cookie + CSRF token。
- **业务失败只看 `code` 不看 HTTP 状态**：后端约定业务失败一律 200 + 非 0 code，
  只有 401 / 403 / 429 / 503 才用非 200。
- **支付回调的 `amount` 原样回传字符串**：验签是对渠道实际发来的串算的，
  `4999.00` 转成数字再发回去会变成 `4999`，服务端 `toPlainString()` 对不上
  就是 `PAY_SIGN_INVALID`。

## 已知限制

- 库存调整要手填 `t_seckill_goods.id`：活动详情接口只下发 `skuId`，没有这个 ID。
- 没有对账任务面板：`docs/06-接口契约` 里列的
  `GET /api/admin/reconcile/tasks` 尚未实现，对账结果目前只能查 `t_reconcile_task` 表。
- 观测面板里的 Grafana / Prometheus / Swagger 链接是写死的 `localhost:3000/9090/8080`，
  换了端口映射要跟着改。

## 命令

```bash
npm run dev         # 开发服务器
npm run typecheck   # vue-tsc 类型检查
npm run build       # 类型检查 + 生产构建到 dist/
```
