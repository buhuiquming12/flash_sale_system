# 电商秒杀系统（Java 实现）

高并发秒杀系统的完整工程实现，覆盖流量削峰、库存防超卖、一人一单、消息幂等、
订单状态机、最终一致性、限流降级、监控压测与故障补偿。

## 一句话架构

```
网关限流 → Redis 原子校验并预扣库存 → 消息队列削峰 → 异步创建订单
        → MySQL 最终兜底 → 延迟关闭订单 → 对账补偿
```

## 技术栈

| 层次 | 选型 |
| --- | --- |
| 语言 / 运行时 | JDK 17 (LTS) |
| 应用框架 | Spring Boot 3.3.x |
| 持久层 | MyBatis-Plus 3.5.x + MySQL 8.0 |
| 缓存 | Redis 7.2 + Lettuce（Lua 与读写）+ Redisson（分布式锁） |
| 消息队列 | Apache RocketMQ 5.2.x |
| 限流熔断 | Nginx `limit_req` + Sentinel 1.8.x + Redis Lua 令牌桶 |
| 认证 | Spring Security 6 + JWT |
| 可观测 | Micrometer + Prometheus + Grafana + Loki |
| 接口文档 | Springdoc OpenAPI 2.x |
| 测试 | JUnit 5 + Testcontainers + JMeter |
| 部署 | Docker Compose |

## 设计文档索引

| 文档 | 内容 |
| --- | --- |
| [00-设计总览](docs/00-设计总览.md) | 关键决策、总体架构、容量假设、一致性边界 |
| [01-工程结构](docs/01-工程结构.md) | 五模块划分、包结构、Maven 组织、角色 profile、配置项 |
| [02-数据模型](docs/02-数据模型.md) | 全量 DDL、约束设计理由、枚举定义、索引说明 |
| [03-缓存与Lua](docs/03-缓存与Lua.md) | Redis Key 设计、hash tag、四段 Lua 脚本全文、预热 |
| [04-核心流程](docs/04-核心流程.md) | 时序图、关键伪代码、订单状态机、并发控制 |
| [05-消息与一致性](docs/05-消息与一致性.md) | Topic 规划、幂等三层、本地消息表、补偿与对账 |
| [06-接口契约](docs/06-接口契约.md) | REST API、请求响应体、统一错误码 |
| [07-防护与可观测](docs/07-防护与可观测.md) | 四级限流、防刷风控、降级、安全、监控告警、链路追踪 |
| [08-实施与验收](docs/08-实施与验收.md) | 五阶段路线图、正确性/性能/故障测试、部署拓扑 |

## 阅读顺序建议

初次阅读按 `00 → 02 → 03 → 04` 走通主链路，再看 `05 → 07` 理解异常与防护，
最后按 `08` 排期开发。

## 快速开始

需要 JDK 17、Maven 3.9+、Docker。

```bash
# 1. 起 MySQL 与 Redis（MySQL 首次启动会自动执行 sql/V1__init.sql 建表）
docker compose --profile phase2 up -d mysql redis

# 2. 打包
mvn clean package -DskipTests

# 3. 启动（web + job 单进程，dev profile 会初始化演示数据）
java -jar fss-app/target/fss-app.jar \
  --spring.profiles.active=web,job,dev \
  --server.port=8080 \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3307/flash_sale?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
```

- 演示页面：<http://localhost:8080/index.html>
- 接口文档：<http://localhost:8080/swagger-ui.html>
- 演示账号：`admin` / `demo1` / `demo2` / `demo3`，密码统一 `Passw0rd1`
- 启动后自动创建一个「1 分钟后开抢、库存 100、每人限 1 件」的活动并完成 Redis 预热

注意 `server.port` 必须显式指定：`application-job.yml` 里设了 8099，
profile 顺序 `web,job` 时后者会覆盖前者。

Redis 地址默认 `127.0.0.1:6379`，可用 `FSS_REDIS_HOST` / `FSS_REDIS_PORT` 覆盖。
**Redis 必须可用**：库存判定是 fail-closed 的，连不上时秒杀接口一律返回
「系统繁忙」而不是放行——放行就是超卖。

## 运行测试

集成测试用 Testcontainers 起真实 MySQL 与真实 Redis（不是 H2、不是嵌入式 Redis），
需要 Docker 在运行：

```bash
docker pull mysql:8.0        # 首次
docker pull redis:7.2-alpine # 首次
mvn clean verify
```

| 测试类 | 覆盖 |
| --- | --- |
| `OrderStateMachineTest` | 穷举 6×6 状态迁移：7 条合法、29 条拒绝 |
| `SeckillConcurrencyTest` | C1 库存100×500并发、C2 单用户50并发、C3 库存1×200并发、C4/C5 时间窗口 |
| `SeckillLuaTest` | **P1 库存1000×10000并发**、售罄快速失败、活动结束瞬间全拒、F14 时钟只认 Redis、未预热拒绝、结果查询命中 Redis |
| `SeckillRedisStockTest` | 取消回补 Redis 库存但保留资格、重复回补幂等、确定性失败不归还资格、售罄标记复位 |
| `WarmupTest` | 重复预热不重置库存、元数据可覆盖、已结束活动预热不崩、`warmup_state` 置位、关闭活动同步 Redis |
| `ActivityCacheTest` | 缓存命中、`serverTime`/库存不被缓存、空值缓存、逻辑过期后台重建、管理操作失效缓存、脏缓存自愈 |
| `RateLimitTest` / `RateLimitWebTest` | 令牌桶突发与匀速补充、按 key 隔离；HTTP 层单用户 100 次 → 90+ 返回 429 |
| `DistributedLockTest` | F12 多实例只执行一次、释放后可重取、异常不吞不漏锁、单飞不阻塞 |
| `TokenAndJwtTest` | 秒杀令牌一次性与身份绑定、JWT 主动失效、按会话撤销 |
| `OrderLifecycleTest` | 取消回补、重复释放×10、C9 取消后重抢、C10 支付关单竞态×30 轮、C11 金额篡改、验签、防重放、C12 越权 |
| `ScheduledJobTest` | 超时关单、重复扫描幂等、F13 未预热不进 RUNNING、预热任务与状态推进串联 |
| `DatabaseTimezoneTest` | DB 与应用时钟同源（回归测试，见下） |

## 实现进度

- [x] **阶段一** 基础业务闭环（纯 MySQL 同步链路）
- [x] **阶段二** Redis 缓存 + Lua 原子判扣 + 四级限流
- [ ] 阶段三 RocketMQ 异步化 + 本地消息表
- [ ] 阶段四 补偿、对账、降级、监控
- [ ] 阶段五 压测报告、故障演练报告、架构图

### 阶段二做了什么

资格分配的权威从 MySQL 挪到了 Redis，落库逻辑一行没改：

```
令牌校验 → Lua 原子判扣（活动/时间/一人一单/库存，一次调用） → 同步落库 → 回写结论
                                                              ↓ 失败
                                                        Lua 回补库存
```

- **五段 Lua**：`seckill` 判扣 / `rollback` 补偿回补 / `release` 取消回补 /
  `write_result` 结论写入 / `token_bucket` 令牌桶。全部走 `EVALSHA`
- **时间判定进了 Lua**，用 Redis `TIME`。`OrderCreateService` 里那份应用时钟的
  时间校验被**删掉**而不是留作双重保险——两个时钟只要有一个错，留着的那个就是错的
- **预热**：`setIfAbsent` 初始化库存，元数据无条件覆盖；`warmup_state` 未完成的活动
  不会进入 RUNNING
- **四级限流**：Nginx `limit_req`（`docker/nginx/nginx.conf`）→ Sentinel 接口级
  QPS/熔断 → Redis 令牌桶（单 IP / 单用户 / 单活动）→ Lua 内售罄快速失败
- **活动详情缓存**：逻辑过期 + 单飞重建 + 空值缓存 + TTL 抖动，
  `serverTime` / 状态 / 库存三个字段每次重算
- **Redisson 分布式锁**：三个定时任务加 `@DistributedLock`
- **JWT 主动失效**：`jwt:active:{jti}` 白名单，新增 `POST /api/user/logout`

Redis 与 Lettuce / Redisson 的分工是刻意的：Lua 与普通读写走 Lettuce
（Spring Data Redis 的一等公民），分布式锁走 Redisson（看门狗续期自己写容易写错）。
不用 `redisson-spring-boot-starter`，它会顶掉 Boot 自动装配的连接工厂。

### 与设计文档的偏差

实现过程中发现设计文档需要修正的地方，已同步改文档。

**阶段一**

1. **JDK 21 → 17**。Spring Boot 3.x 基线是 17，项目未用到 21 特性。
2. **库存三分口径**。docs/02 原本的扣减示例是 `available -= qty, sold += qty`，
   与 docs/04 支付时的 `moveLockedToSold` 冲突，照原样实现会在支付时把 `sold`
   加两次。现为下单 `available → locked`、支付 `locked → sold`、
   取消 `locked → available`，`total = available + locked + sold` 恒成立，
   每个集成测试都断言这个等式。
3. **业务号尾段用递增序列而非 6 位随机**。10000 QPS 下同秒同机 10000 个 6 位
   随机数碰撞概率约 5%（生日问题），碰撞表现为请求被误拒或唯一键冲突。

**阶段二**

4. **`seckill:bought` 没有 TTL**。docs/03 的脚本 A 只 `HINCRBY` 不设过期，而这个
   Hash 是惰性创建的、预热阶段设不了 TTL（Redis 里空 Hash 不存在）。结果是购买标记
   永久驻留内存，一场十万人的活动约 3MB，而 `maxmemory-policy` 是 `noeviction`，
   撑爆意味着写入直接失败。现在脚本成功预扣后补一次 `EXPIRE`，过期时间用脚本里
   已读到的 `endTime` 算，不额外传参。
5. **售罄与停售必须是两个返回码**。原脚本 `status ~= 1 → -2` 把"卖光了"和
   "管理员下架了"合并，于是活动一售罄用户看到的提示是"商品已停售"。拆成
   `-8` 售罄（对外仍是"库存不足"）与 `-2` 停售，代价是一次整数比较。
6. **脚本 B 需要 `keepBought` 参数**。落库撞 `uk_activity_sku_user` 时如果连购买
   资格一起归还，就是"用户重抢 → Redis 放行 → DB 又冲突 → 又回补"的死循环
   （`FssProperties.Seckill` 的注释早就预警过这个循环）。确定性失败只还库存不还资格。
7. **脚本 D 重建分支要写 `userId`**。查询接口读到结论后要做归属校验，而被脚本 A
   提前拒绝的请求没创建过 `req` key，`userId` 只能由脚本 D 补上。漏了它的表现是
   "明明失败了却查不到失败原因"，客户端一路轮询到超时——这个缺陷是被
   `SeckillLuaTest#P8` 抓出来的。
8. **令牌桶回写要显式 `tostring`**。`ts` 被截断到整秒时，下一次算出的 `delta`
   最多多出 1 秒，等于每次调用白送一秒的令牌。这种漏只在调用间隔小于 1 秒时出现。
9. **单用户限流速率必须按接口分档**。docs/07 对所有路径用同一个 2 QPS，
   但用户点一次"抢购"背后有领令牌、活动详情、订单列表等多个请求，共用一个桶
   会让第二次点击直接 429。按 URI 前缀分成提交（2）/ 结果查询（5）/ 其他（不限）。
10. **预热要允许 RUNNING 状态**，且 TTL 计算要有下限。活动进行中重新预热是合法运维
    动作（Redis 数据丢失后补数据，F2）；对已结束的活动预热时 `endTime - now` 是负数，
    直接 `EXPIRE` 会让 Redis 报 `invalid expire time`，预热在第一个商品上就中断。
11. **`@ConditionalOnMissingBean` 的自动让位改成显式判断**。Noop 与 Redis 两个
    `TokenRevocationStore` 实现原本靠条件注解二选一，那依赖"组件扫描注册早于
    `@Bean` 条件求值"这个实现细节。现在一个 `@Bean` 方法里用 `ObjectProvider`
    显式判断，更短也更好测。

另修了一个阶段一联调时才暴露的缺陷：MySQL 容器默认时区 UTC，`NOW(3)` 与列
`DEFAULT CURRENT_TIMESTAMP(3)` 写入的时间比 Java 侧写入的 `LocalDateTime`
慢 8 小时。已在 compose 与 Testcontainers 两处统一加 `--default-time-zone=+08:00`，
并补 `DatabaseTimezoneTest` 防止回归——原有用例抓不到它，因为断言的字段
恰好都是 Java 侧写入的。

### 阶段二已知限制

- **Redis 单点**（演示环境）。生产需哨兵或 Cluster；Key 已带 hash tag，上 Cluster
  无需改代码。
- **Nginx 只提供配置未接入本地运行**。它要 `proxy_pass` 到两个 web 容器，
  本地开发时应用跑在宿主机上，所以 compose 里放在 `phase5` profile 默认不启动。
- **Redis 调用超时的不确定结果只记日志**。此时无法确定脚本是否已执行，
  对用户报"系统繁忙"，库存差额留给阶段四的对账任务收敛。完整的 `checkUncertain`
  需要 `t_reconcile_task` 真正跑起来，属于阶段四。
- **秒杀仍是同步落库**。这是阶段二的设计意图：单独验证 Lua 的正确性，
  不被 MQ 的异步性干扰。
- **令牌串错误时令牌也会被消费**。`GETDEL` 没有"比对不上就别删"这个选项，
  这是接受它的原子性所付的代价。key 由已认证的 userId 推出，攻击者只能作废自己的令牌。
"# flash_sale_system" 
