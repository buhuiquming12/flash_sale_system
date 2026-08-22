# 电商秒杀系统（Java 实现）

高并发秒杀系统的完整工程实现，覆盖流量削峰、库存防超卖、一人一单、消息幂等、
订单状态机、最终一致性、限流降级、监控压测与故障补偿。

## 一句话架构

```
网关限流 → Redis 原子校验并预扣库存 → 消息队列削峰 → 异步创建订单
        → MySQL 最终兜底 → 延迟关闭订单 → 对账补偿
```

阶段三起主链路已经是这个形状：秒杀接口只做「Lua 判扣 + 投一条消息」就返回排队中，
订单由消费端创建，关单由任意时刻定时消息触发。阶段四补上了最后那段「对账补偿」，
以及它旁边的降级与监控。

## 技术栈

| 层次 | 选型 |
| --- | --- |
| 语言 / 运行时 | JDK 17 (LTS) |
| 应用框架 | Spring Boot 3.3.x |
| 持久层 | MyBatis-Plus 3.5.x + MySQL 8.0 |
| 缓存 | Redis 7.2 + Lettuce（Lua 与读写）+ Redisson（分布式锁） |
| 消息队列 | Apache RocketMQ 5.3.0 + rocketmq-spring-boot-starter 2.3.1 |
| 限流熔断 | Nginx `limit_req` + Sentinel 1.8.x + Redis Lua 令牌桶 |
| 认证 | Spring Security 6 + JWT |
| 可观测 | Micrometer + Prometheus 2.54 + Grafana 11.2 |
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
# 1. 起 MySQL、Redis、RocketMQ、Prometheus、Grafana
#    （MySQL 首次启动会自动执行 sql/V1__init.sql 建表）
docker compose --profile phase2 --profile phase3 --profile phase4 up -d

# 2. 建 Topic（autoCreateTopicEnable=false，必须显式创建；只需在 broker 首次起来后做一次）
docker cp docker/rocketmq/init-topics.sh fss-rmq-broker:/home/rocketmq/init-topics.sh
docker exec fss-rmq-broker sh /home/rocketmq/init-topics.sh

# 3. 打包
mvn clean package -DskipTests

# 4. 启动（web + consumer + job 单进程，dev profile 会初始化演示数据）
java -jar fss-app/target/fss-app.jar \
  --spring.profiles.active=web,consumer,job,dev \
  --server.port=8080 \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3307/flash_sale?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
```

- 演示页面：<http://localhost:8080/index.html>
- 接口文档：<http://localhost:8080/swagger-ui.html>
- **Grafana 看板**：<http://localhost:3000>（匿名可看，打开即是「秒杀系统总览」）
- **Prometheus**：<http://localhost:9090>（`/alerts` 看 16 条告警规则的实时状态）
- 演示账号：`admin` / `demo1` / `demo2` / `demo3`，密码统一 `Passw0rd1`
- 启动后自动创建一个「1 分钟后开抢、库存 100、每人限 1 件」的活动并完成 Redis 预热

**`consumer` profile 必须激活**：阶段三起订单由消费端创建，不激活它所有秒杀都会
永远停在"排队中"。**`job` profile 决定对账、降级监控、关单扫描跑不跑**——
不激活时主链路仍然完整（关单靠定时消息），但所有兜底机制都不在。

注意 `server.port` 必须显式指定：`application-job.yml` 里设了 8099、
`application-consumer.yml` 设了 8090，profile 顺序靠后的会覆盖前面的。

`docker compose down -v` **必须带上全部 profile**，否则 compose 不认识那些服务，
它们的命名卷会留下来：`redis-data` 带着上一轮的 `stock`/`bought` 活到下一轮，
而 `mysql-data` 被删了、activityId 从 1 重新开始——两边一对上就是"库存莫名少了几个"。
清干净的写法：

```bash
docker compose --profile phase2 --profile phase3 --profile phase4 --profile phase5 down -v
```

Redis 地址默认 `127.0.0.1:6379`，可用 `FSS_REDIS_HOST` / `FSS_REDIS_PORT` 覆盖；
RocketMQ 默认 `127.0.0.1:9876`，可用 `FSS_MQ_NAMESRV` 覆盖。
**Redis 必须可用**：库存判定是 fail-closed 的，连不上时秒杀接口一律返回
「系统繁忙」而不是放行——放行就是超卖。

## 运行测试

集成测试用 Testcontainers 起真实 MySQL、真实 Redis、真实 RocketMQ
（不是 H2、不是嵌入式 Redis、不是 Mock 的 `RocketMQTemplate`），需要 Docker 在运行：

```bash
docker pull mysql:8.0          # 首次
docker pull redis:7.2-alpine   # 首次
docker pull apache/rocketmq:5.3.0
mvn clean verify
```

测试用的 MQ 端口刻意错开 compose 的那套（namesrv 9877、broker 10921），
所以 `mvn verify` 和联调环境可以同时活着。

当前 **20 个测试类 / 144 个用例**，全绿约 5 分 45 秒。

| 测试类 | 覆盖 |
| --- | --- |
| `OrderStateMachineTest` | 穷举 6×6 状态迁移：7 条合法、29 条拒绝 |
| `SeckillConcurrencyTest` | C1 库存100×500并发、C2 单用户50并发、C3 库存1×200并发、C4/C5 时间窗口 |
| `SeckillLuaTest` | **P1 库存1000×10000并发**（含落库吞吐断言）、售罄快速失败、活动结束瞬间全拒、F14 时钟只认 Redis、未预热拒绝、结果查询命中 Redis |
| `MqReliabilityTest` | **M1 只返回排队中**、M2 重复投递×10 仍 1 单（C6）、M3 消费端晚到不丢、M4 断开 MQ 堆积后重发（F4）、M5 重发耗尽即回补 |
| `MqTopicConsistencyTest` | Topic/消费组的常量与配置项逐字一致（不需要容器） |
| `ReconcileTest` | **C14 库存漂移自动修正**、Redis>DB 判 P1 不覆盖、DB 等式被破坏、有排队请求时不算差异、孤儿资格回补、结论丢失只补结论、支付对账 A/C/D 三类、差异去重 |
| `UncertainAndRefundTest` | **F8** 已执行则补发/未执行则不动/静置窗口/按原始串移除/重复判定幂等、**F9** 支付关单竞态自动退款与幂等 |
| `DegradeTest` | 分级行为（L1 库存档位、L2 轮询间隔、L3 停资格、L4 停浏览）、人工与自动取最大值、脏值处理、TTL 区别、降级等级进指标 |
| `MetricsTest` | 关键指标存在、失败原因用标签、**标签基数有界**（禁 userId/requestNo）、告警指标形状 |
| `MetricsExportTest` | **导出文本**里的指标名与告警规则/看板逐一对齐（不需要容器，毫秒级） |
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
- [x] **阶段三** RocketMQ 异步化 + 本地消息表
- [x] **阶段四** 补偿、对账、降级、监控
- [ ] 阶段五 压测报告、故障演练报告、架构图

### 阶段四做了什么

前三个阶段建的是"正常路径 + 局部兜底"，阶段四补的是**最后一层：当上面全都失效时，
差异能不能被发现并收敛**。

```
不确定结果 → checkUncertain（EXISTS req key 判定，补发或放过）
资格孤儿   → 资格对账（按消息表状态五分支处置）
库存漂移   → 库存对账（三条等式，严格前提下自动修正）
资金差异   → 支付对账（四类，只有一类可自动修）+ 退款流程
压力上来   → 自动降级（积压/连接池 → 分级收紧，滞回恢复）
以上全部   → 指标 + 16 条告警规则 + Grafana 看板
```

- **三类对账任务**，频率刻意不同：资格每分钟（用户正在等）、库存每 5 分钟（最贵）、
  支付每 10 分钟（给回调延迟留窗口）。每个都有分布式锁，而这里的锁**保正确不只省资源**
- **库存对账拆成三条独立等式**：商品表自洽、商品表与订单表吻合、Redis 与 DB 的差值
  等于排队中量。差异能直接定位到某一层；自动修正只在「DB 自洽 且 无排队请求」时才敢做
- **不确定结果处理**：Lua 超时时无法判断库存扣没扣，登记进 Redis ZSet，
  静置 5 秒后用 `EXISTS req key` 判定——脚本 A 的原子性保证
  `req key 存在 ⟺ 库存已扣`，这条等价关系是整个机制的基石
- **降级五级 + 自动降级**：人工与自动是两个 key、生效等级取最大值，
  所以自动机制只能收紧、永远盖不过人的决定。目标等级 = 各条件的最大值，
  天然支持自动恢复且无需记状态
- **退款流程**：支付与关单竞态输给关单时，先如实记成功流水再标记退款、
  落对账任务、P1 告警。CANCELLED 是终态没有回头路，所以只能退款而不能把订单拉回来
- **告警统一出口** `AlarmService`：固定日志格式 + `fss_alarm_total` 指标双通道，
  `event` 是有限常量集合。不做重复抑制（收敛交给 Prometheus 的 `for:`）
- **库存仪表用推送而不是回调采样**：对账任务本来就要读那四个值，顺手写内存；
  回调式会让每次抓取都产生 IO，而 Redis 一慢连"Redis 慢了"这条曲线自己都断掉

### 阶段三做了什么

秒杀接口不再等 MySQL。落库逻辑**仍然一行没改**——只是换了谁来调它：

```
Lua 原子判扣 → 本地消息表 + 投递 → 立刻返回「排队中」
                                      ↓ 消费端
                              OrderCreateService.handle（与阶段一同一个方法）
                                      ↓ 同事务登记
                              FSS_ORDER_CLOSE 定时消息（投递时刻 = expire_time）
```

- **五个 Topic / 四个消费者**：`ORDER_CREATE` 异步落库、`ORDER_CLOSE` 定时关单、
  `STOCK_RELEASE` Redis 回补、`STOCK_ROLLBACK` 补偿回补、`%DLQ%GID_FSS_ORDER_CREATE`
  死信兜底。队列数 16/4/4/4，`autoCreateTopicEnable=false` 显式建
- **本地消息表两个入口**：`sendReliable`（事务外，先落库再发）与
  `registerAfterCommit`（事务内登记、提交后投递）。后者让"订单已创建"与
  "关单消息已登记"成为原子的
- **任意时刻定时消息**关单，投递时刻取 `expire_time` 而不是"现在+15分钟"；
  定时扫描退化为兜底（消息可能丢，而"订单永久待支付"不会自己暴露）
- **幂等三层不变**：L1 `selectByRequestNo`、L2 `uk_request_no` /
  `uk_activity_sku_user`、L3 状态条件更新。消费端只多做一个判断——
  确定性失败立即回补并 ACK，可恢复异常抛出触发重试
- **重发任务 + 死信消费者**：重试耗尽走 `onSendGiveUp` 回补（消息没发出去），
  进死信走 `DlqListener` 回补（消息发出去了但处理不了）。两条路径的兜底完全不同
- **traceId 跨 MQ 传递**：消息体带 `traceId`，消费端 `onMessage` 开头
  `MDC.put`、`finally` 里 clear。实测一次关单的 5 条日志（ORDER_CLOSE 消费 →
  DB 回补 → 登记 STOCK_RELEASE → Redis 回补 → ACK）共享同一个 traceId

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

**阶段三**

12. **`ReliableMqProducer` 拆成两个类**。它同时要 `RocketMQTemplate`（技术设施）和
    `MqMessageMapper`（数据模型），而按 docs/01 的分层 `fss-infra` 只依赖
    `fss-common`。让 infra 依赖 domain 会把两者的方向拧反，以后 domain 想用 infra
    的工具就成环。现在 `MqSender`（infra）只负责发出去，`ReliableMqProducer`（biz）
    负责落库与"发不出去该怎么办"——后者是业务决策：订单消息必须回补库存，
    关单消息只需告警。
13. **库存释放只把 Redis 那半挪进消息**。docs/05 把取消回补整个改成消息，但 DB 回补
    **能**和关单同事务，所以必须同事务：拆出去之后消费端永久失败时，订单已是
    CANCELLED 却永远拿不回库存，而"已取消"没法回滚成"待支付"。Redis 回补**不能**
    加入 DB 事务，所以必须可重试——阶段二那种"提交后直接 INCRBY，失败只打日志"
    期间就是实打实的少卖。
14. **脚本 B 需要 `failStatus` 参数**。它原本固定把请求置成 5（"系统繁忙已退回"），
    而消费端同时往 `t_seckill_request` 落一条 status=3（"已参与过"）。同一请求在
    Redis 和 DB 里有两个结论，返回哪个取决于 TTL 到没到，而它偏向更坏的一边：
    用户看到"系统繁忙"会不停重试。
15. **捞待重发记录必须带 `send_count < max` 条件**。原写法是捞出来再判断次数、
    超了就 `markFailed` 并跳过。只要有一次 `markFailed` 失败（DB 抖动），
    那条记录就永远留在 `status = 0` 里，每 30 秒被捞一次、跳过一次；积累几万条后
    每轮 200 条的配额全被占满，真正要发的新消息一条也捞不到。症状是
    "重发任务在跑、日志不报错、消息就是发不出去"。现在拆成两次查询。
16. **已消费回写要按 `(biz_key, topic)` 且只从"已发送"推进**。消费端只拿得到
    RocketMQ 的 `KEYS`，拿不到 `msg_id`；同一个 requestNo 在多个 Topic 下各有一条
    消息，只按 bizKey 会误更新另一条。允许从"待发送"推进则会让一条尚未发出的消息
    被标成已消费，重发任务再也不碰它——投递意图就这么丢了。
17. **消费端要能区分"回补失败"与"早就回补过了"**。脚本 C 对两者都返回非 0，
    分不开的话重复投递会被当成失败无限重试，最后整批进死信——一个纯粹由
    "把幂等命中误判成失败"造出来的故障。多查一次 `SISMEMBER released` 把两者分开。

**阶段四**

18. **库存对账的 `dbOk` 判据是错的**。docs/05 §9.2 写
    `available + occupied + queueing == total`，而 `queueing` 是"Redis 已预扣但还没
    落库"的量，**它根本还没到 DB**。加进 DB 的等式里，只要有排队中请求 `dbOk` 就恒为假，
    于是每轮都判"DB 漂移"、自动修正永远不触发（它的前提正是 `dbOk`）、
    每 5 分钟刷一条"需人工"。症状是"对账一直在报差异，但库存其实是对的"。
    现在拆成三条独立等式，差异能定位到具体某一层。
19. **支付对账 A 类的 SQL 必须限定 `o.status = 0`**。文档写的
    `NOT IN (1,3,4,5)` 把 `status = 2`（已取消）也捞进来了，而那是 D 类，
    处置方式**正好相反**：A 类补推订单到已支付，D 类绝不能补推（关单已经把库存还给
    别人了，补推等于超卖）。两类混在一条查询里，自动修复会把资损事件"修"成超卖事件。
    另外 A 类补推**必须连 `locked → sold` 一起做**，只改订单状态会让 `locked` 里
    永久留一份已卖出的量——而这种不一致库存对账发现不了（三条等式都还成立）。
20. **不确定结果的登记簿用 ZSet 而不是 List**。docs/04 §5 给的是 List，
    但 `LPOP` 取出即出队，处理过程崩了记录就消失——而它正是"不知道库存扣没扣"的唯一
    线索；改用 `LRANGE` 不删则要 `LREM` 按值删，O(N)。而且 List 没有时间维度，
    做不了那个必需的静置窗口（超时那一刻脚本可能**正在**执行，立刻判定"没执行"
    会把一份已扣的库存留成永久泄漏）。
21. **降级开关要拆成人工与自动两个 key，生效等级取最大值**。只有一个 key 时，
    运维手动降到 Level 4，下一轮自动计算（每 15 秒）就把它抹回 0——而做这个决定的人
    正在处理别的事故，根本不知道开关自己弹回去了。人工那个不带 TTL（人的决定不该
    悄悄失效），自动那个带 TTL（写入者崩溃后没人负责删它）。
22. **自动降级的目标等级 = 各条件的最大值**，不是"最后一个触发的条件"。
    逐条件顺序覆盖会出现：积压超阈值判 Level 3，接着"连接池正常"把它写回 0，
    于是秒杀在积压 8 万条的情况下重新开闸。取最大值顺带解决了自动恢复——
    每轮从 0 重算，所有条件退回时结果自然是 0，不需要记"是谁触发的"。
    采不到数据时让这一条**不参与计算**而不是当成 0：broker 挂了正是最该降级的时候。
23. **指标名不能带 Prometheus 的保留后缀**，而这个坑**单元测试抓不到**。
    Micrometer 注册的名字和导出到 `/actuator/prometheus` 的名字不是一个东西：
    `fss_order_created_total` 被剥成 `fss_order_total`、`fss_stock_total`（Gauge）
    被剥成 `fss_stock`，而 `registry.find(原名)` 在**注册侧**照样命中。
    结果是断言指标存在的用例全绿，告警规则和看板却在查不存在的序列——
    **Prometheus 对查不到序列的表达式既不报错也不告警**。同理
    `publishPercentiles` 与 `publishPercentileHistogram` 只差一个词，
    前者导出 `{quantile=...}`、后者导出 `_bucket`，而 `histogram_quantile()` 只认后者。
    三处都是联调时发现的，现在由 `MetricsExportTest` 直接断言导出文本，
    并把 `alert-rules.yml` 与看板 JSON 里的每个指标名都对一遍。
24. **库存仪表要用推送而不是回调采样**。`Gauge.builder(name, () -> readRedis())`
    在每次抓取时执行，1 个活动 10 个 SKU × 4 个指标 = 一次抓取 40 次查询，
    15 秒一轮就是稳定的负载源。更糟的是失败模式：Redis 变慢 → 抓取超时 →
    整个端点失败 → 连"Redis 慢了"都看不到，因为报告它的曲线也断了。
    改成对账任务顺手写内存、抓取只读内存，代价是滞后一个对账周期。
25. **定时任务线程池必须从默认的 1 调大**。`DegradeSwitch` 每秒刷新与"扫描关单"
    共用这个池，单线程下一轮跑 3 分钟的关单会让降级开关整整 3 分钟不更新——
    恰好是最需要它更新的时候。

另修了一个阶段一联调时才暴露的缺陷：MySQL 容器默认时区 UTC，`NOW(3)` 与列
`DEFAULT CURRENT_TIMESTAMP(3)` 写入的时间比 Java 侧写入的 `LocalDateTime`
慢 8 小时。已在 compose 与 Testcontainers 两处统一加 `--default-time-zone=+08:00`，
并补 `DatabaseTimezoneTest` 防止回归——原有用例抓不到它，因为断言的字段
恰好都是 Java 侧写入的。

### 阶段三踩到的三个环境坑

与代码无关但会卡住整个阶段，记下来省下次的时间：

- **RocketMQ 容器不能给 `/home/rocketmq/store` 挂命名卷**。镜像里这个目录不存在，
  挂上去 Docker 会新建一个 root 所有的目录，而 broker 以 uid 3000 运行写不进去。
  更糟的是 5.3.0 的失败路径本身有个 NPE
  （`ScheduleMessageService.configFilePath`），真正的原因被那个 NPE 完全盖掉，
  日志里只有一句 `NullPointerException`。
- **broker 端口必须固定映射，不能用 Testcontainers 的随机端口**。broker 把
  `brokerIP1:listenPort` 注册到 namesrv，客户端查到之后**直连**——随机映射时
  客户端拿到的是容器内端口。症状是发送超时，日志只说 `sendDefaultImpl call timeout`。
- **集成测试的属性要统一放进 `application-test.yml`**。属性只要有一处不同 Spring
  就另起一个上下文，而每个上下文都会创建一套 RocketMQ 生产者与四个消费组。
  分散在 `@TestPropertySource` 里时有 4 个上下文，跑到第 3 个就报
  `java.lang.Error: IP Helper Library GetAdaptersAddresses failed with error == 1450`
  （ERROR_NO_SYSTEM_RESOURCES：客户端每次实例化都要枚举网卡，而 Docker Desktop
  会造出一大堆虚拟网卡）。合并成一个上下文顺带省掉两次启动。

### 阶段四踩到的坑

- **`micrometer-registry-prometheus` 不会被 actuator 自动带进来**。
  `management.endpoints.web.exposure.include` 里写了 `prometheus` 而没有这个依赖时，
  端点是 404 且**不报任何错**——配了但没生效，部署当天才会发现。
- **Grafana 数据源的 `uid` 必须写死**。不写时 Grafana 生成随机 uid
  （形如 `PBFA97CFB590B2093`），而看板 JSON 里的模板变量是按 uid 引用数据源的。
  症状是"图都有，就是活动筛选框是空的"——面板本身走默认数据源反而正常，
  很难联想到 uid 上。
- **Linux 上 `host.docker.internal` 默认不存在**（那是 Docker Desktop 的特性），
  Prometheus 抓宿主机上的应用要加 `extra_hosts: host.docker.internal:host-gateway`。
- **MySQL 容器时钟与宿主时钟会漂**。测支付对账时用 `NOW(3)` 造 `finish_time`、
  而筛选条件是 Java 侧算出的时刻，两者差出上百毫秒（WSL2 的 VM 时钟），
  阈值又设成 0s，于是记录选不选中取决于当时的漂移方向——只在整套跑的时候偶发失败。
  测试里改成从 Java 侧显式给一个过去时刻。

### 阶段四已知限制

- **Redis 单点**（演示环境）。生产需哨兵或 Cluster；Key 已带 hash tag，上 Cluster
  无需改代码。
- **单 broker、消息数据不持久化到卷**。要持久化就得 `user: root`，
  为演示环境授这个权不值得。`stop`/`start` 数据保留，`down` 之后 Topic 需重建。
- **Nginx 只提供配置未接入本地运行**。它要 `proxy_pass` 到两个 web 容器，
  本地开发时应用跑在宿主机上，所以 compose 里放在 `phase5` profile 默认不启动。
- **告警只到日志与指标，没有真实通道**。`P1` 是 `fss_alarm_total` 的一个标签，
  不会真的打电话。触发点、分级、事件名都是真的，接通道只需在 `AlarmService`
  里加一个 HTTP 调用。Alertmanager 也没起——Prometheus 的 `/alerts` 页面
  已经能演示规则的实时状态。
- **对账用 SCAN 遍历 Redis**。MATCH 模式在 Redis 侧是"先取回一批 key 再过滤"，
  所以扫描量与库里总 key 数成正比而非匹配数。活动与 SKU 很多时会变贵，
  替代方案是从 `t_seckill_request` 反向查——但那需要主链路同步写库，
  等于把异步化的收益还回去一部分。当前量级下 SCAN 更划算。
- **排队中请求的 TTL 是对账的时间窗口**。`seckill:req` 的 TTL 是 30 分钟，
  一条真的卡住的请求如果 30 分钟内没被资格对账扫到，key 过期后就再也发现不了——
  表现是"差异自己消失了"。资格对账每分钟一轮，正常情况下有 30 次机会，
  但这个依赖关系值得写下来：调长 `orphan-after` 或调短 `result-ttl` 都会压缩它。
- **`checkUncertain` 判定不了时只转人工，不自动回补**。此时的处境恰好是
  "不知道库存扣没扣"，而回补一份没扣过的库存就是凭空增加库存 → 直接超卖。
  少卖可以人工修，超卖要赔钱。差额留给库存对账发现。
- **限购固定为 1**，所以资格对账回补时 `quantity` 写死 1。支持 >1 时必须从
  `t_seckill_request` 或消息体读真实数量，否则会回补错数量。
- **补偿回补目前是直接调用而非发消息**。`FSS_STOCK_ROLLBACK` 的生产者与消费者都已
  就位（重发放弃、死信兜底两条路径），但主链路上的确定性失败是消费端就地回补的——
  它已经在自己的线程里，多绕一次 MQ 只增加延迟。
- **Redis 令牌串错误时令牌也会被消费**。`GETDEL` 没有"比对不上就别删"这个选项，
  这是接受它的原子性所付的代价。key 由已认证的 userId 推出，攻击者只能作废自己的令牌。
"# flash_sale_system" 
