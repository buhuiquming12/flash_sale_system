# UML 图（PlantUML 源 + 导出 PNG）

阶段五交付物之一。源文件在本目录，导出的 PNG 在 `png/`。

| 图 | 源文件 | 讲什么 | 对应代码 |
| --- | --- | --- | --- |
| 总体架构 | [01-架构图.puml](01-架构图.puml) | 五个进程角色、三个中间件、①②③ 的提交路径 | 全局 |
| 秒杀提交 | [02-时序-秒杀提交.puml](02-时序-秒杀提交.puml) | **每一步失败往哪边倒** | `SeckillServiceImpl#submit` |
| 异步下单 | [03-时序-异步下单.puml](03-时序-异步下单.puml) | 幂等三层各拦什么、抛异常 vs 补偿并 ACK | `OrderCreateListener` / `OrderCreateService#handle` |
| 超时关单 | [04-时序-超时关单.puml](04-时序-超时关单.puml) | DB 回补同事务、Redis 回补走消息 | `OrderCloseListener` / `StockReleaseService` |
| 补偿与对账 | [05-时序-补偿与对账.puml](05-时序-补偿与对账.puml) | `checkUncertain` + 三类对账 + 自动降级 | `UncertainCheckJob` / `reconcile/*` / `DegradeMonitorJob` |
| 订单状态机 | [06-订单状态机.puml](06-订单状态机.puml) | 7 条合法迁移，29 条拒绝 | `OrderStateMachine` |
| 数据模型 ER | [07-ER图.puml](07-ER图.puml) | 12 张表、全部唯一键、库存三分口径 | `sql/V1__init.sql` |

## 重新出图

需要 `.tools/plantuml.jar`（见 README「阶段五工具链」）。**不需要装 graphviz**——
所有图都用 `!pragma layout smetana`，那是 PlantUML 内置的 graphviz Java 移植。

```bash
cd docs/uml
java -Djava.awt.headless=true -jar ../../.tools/plantuml.jar -charset UTF-8 -tpng -o png *.puml
```

## 三个出图时踩到的坑

- **细节写进节点，不要写进边标签。** 架构图第一版把「回写结论(脚本 D)」这类说明挂在
  30 条边上，每条带文字的边都迫使 graphviz 留出横向空间，结果 Redis 和 web 被拉到
  画布两端、连线横穿全图，右侧的可观测框还被挤出边界。改成只留 13 条层间边。
- **`-checkonly` 通过 ≠ 图是干净的。** PlantUML 把弃用警告
  （`Please use CSS style instead of skinparam ParticipantPadding`）
  **画进图里**，而退出码仍是 0。验证方法是导出 SVG 再 grep 文本——PNG 里搜不到字。
- **多态列不要画成 ER 边。** `t_stock_log.biz_no` 与 `t_reconcile_task.biz_no`
  按类型分别指向 request_no / order_no / pay_no / `{activityId}:{skuId}`。
  给它们各画 3 条汇入边时 graphviz 只能绕整张图走线，右边缘标签被裁掉。
  改成一条注释说清取值规则。

顺带在核对 ER 图时发现：`StockChangeType` 的 **1 预扣**与 **4 补偿回补**两个枚举值
至今没有任何写入点——这两步只发生在 Redis 里（脚本 A / 脚本 B），DB 侧没有对应流水。
这不是漏写（预扣时订单还不存在，为一个可能永不落库的预扣写 DB 流水等于把异步化
省下的那次写还回去），但代价是**库存流水不能独立还原 Redis 侧的历史**，
Redis 与 DB 的差值只能靠库存对账的第三条等式发现。已标注在图上。
