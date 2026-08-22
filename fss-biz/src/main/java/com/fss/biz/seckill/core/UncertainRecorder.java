package com.fss.biz.seckill.core;

import com.fss.common.util.JsonUtil;
import com.fss.domain.message.OrderCreateMessage;
import com.fss.infra.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * "结果不确定"的请求登记簿。
 *
 * <h3>为什么需要它</h3>
 * {@code redis.execute(seckillScript, ...)} 抛超时异常时，脚本<b>可能已经执行</b>
 * （命令到了服务端，响应丢在网络上），也可能根本没执行。两种误判都有代价：
 * <ul>
 *   <li>当成功 → 库存可能没扣，却给用户返回排队中，最终没订单</li>
 *   <li>当失败后直接回补 → 脚本可能没执行过，回补是凭空增加库存，直接超卖</li>
 * </ul>
 * 唯一安全的做法是<b>把判断推迟到能确定的时候</b>：脚本 A 的原子性保证
 * "预扣库存"和"写 req key"要么都发生要么都不发生，因此
 * <b>{@code req key} 存在 ⟺ 库存已扣</b>。这条等价关系是整个不确定性处理的基石，
 * 也是脚本 A 里必须把 {@code HSET reqKey} 放在同一段脚本内的原因。
 *
 * <h3>用 ZSet 而不是 List（与设计文档的偏差）</h3>
 * docs/04 §5 写的是 Redis List。List 有两个问题：
 * <ol>
 *   <li><b>取出即出队。</b> {@code LPOP} 之后如果处理过程崩了，这条记录就消失了——
 *       而它正是"我们不知道库存扣没扣"的唯一线索。改成 {@code LRANGE} 不删则要靠
 *       {@code LREM} 按值删，那是 O(N)。</li>
 *   <li><b>没有时间维度。</b> 必须"等一会儿再判定"（见 {@code uncertain-settle}），
 *       List 里只能靠元素顺序近似，而顺序会被重试打乱。</li>
 * </ol>
 * ZSet 用 score 存记录时刻，天然支持"只取 5 秒前的"、天然按 requestNo 去重、
 * ZREM 是 O(log N)。代价是内存略高，而这个集合正常情况下应该是空的。
 *
 * <h3>写不进去只能记日志</h3>
 * 登记本身也要写 Redis，而此刻 Redis 正不可靠——这是无法回避的循环。
 * 登记失败时这条请求就只剩<b>库存对账</b>能发现了（Redis 库存比预期少一份）。
 * 所以这里的失败不是"可以忽略"，而是"降级到更慢的兜底路径"，日志必须打 error。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UncertainRecorder {

    private final StringRedisTemplate redis;

    /**
     * 登记一条不确定的请求。
     *
     * @return true 表示登记成功（后续会有任务来判定）；false 表示连登记都失败了，
     *         这条请求只能靠库存对账兜底
     */
    public boolean record(OrderCreateMessage msg) {
        try {
            redis.opsForZSet().add(RedisKeys.uncertain(), JsonUtil.toJson(msg),
                    System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            log.error("stage=UNCERTAIN_RECORD requestNo={} result=FAILED "
                            + "登记失败，这条请求只能靠库存对账发现",
                    msg.getRequestNo(), e);
            return false;
        }
    }

    /**
     * 取一批"已经静置够久、可以判定"的记录。
     *
     * <p>{@code settle} 不能是 0：Redis 超时的那一刻，脚本可能<b>正在</b>执行。
     * 立刻 {@code EXISTS req key} 读到不存在就断定"没执行"，而 50ms 后它执行完了——
     * 于是库存被扣掉却没人补消息，成了一个只有库存对账才能发现的泄漏。
     */
    public List<Record> take(int limit, Duration settle) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                    .rangeByScoreWithScores(RedisKeys.uncertain(), 0,
                            System.currentTimeMillis() - settle.toMillis(), 0, limit);
            if (tuples == null || tuples.isEmpty()) {
                return List.of();
            }
            List<Record> out = new ArrayList<>(tuples.size());
            for (var t : tuples) {
                String raw = t.getValue();
                if (raw == null) {
                    continue;
                }
                OrderCreateMessage msg = null;
                try {
                    msg = JsonUtil.parse(raw, OrderCreateMessage.class);
                } catch (Exception e) {
                    // 解析不了的记录留着也没用：没有 requestNo 就无法判定，
                    // 直接丢掉并告警比让它每 10 秒被捞出来一次好
                    log.error("stage=UNCERTAIN_CHECK result=UNPARSEABLE raw={} 已丢弃", raw, e);
                    remove(raw);
                    continue;
                }
                out.add(new Record(raw, t.getScore() == null ? 0L : t.getScore().longValue(), msg));
            }
            return out;
        } catch (Exception e) {
            log.warn("stage=UNCERTAIN_CHECK 取记录失败，本轮跳过", e);
            return List.of();
        }
    }

    /**
     * 判定完成，移除记录。
     *
     * <p>必须传原始 JSON 字符串而不是 requestNo：ZSet 的 member 就是那个字符串，
     * 重新序列化一次未必逐字节相同（字段顺序、时间格式），ZREM 会静默失败 0 条——
     * 而症状是记录永远删不掉、每轮重复处理。
     */
    public void remove(String raw) {
        try {
            redis.opsForZSet().remove(RedisKeys.uncertain(), raw);
        } catch (Exception e) {
            log.warn("stage=UNCERTAIN_CHECK 移除记录失败，下轮会重复处理（幂等）", e);
        }
    }

    /** 当前待判定条数。监控与测试用 */
    public long size() {
        try {
            Long n = redis.opsForZSet().size(RedisKeys.uncertain());
            return n == null ? 0 : n;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * @param raw        ZSet 里的原始 member，删除时必须用它
     * @param recordedAt 登记时刻（epoch millis），用于判断是否超过保留期
     */
    public record Record(String raw, long recordedAt, OrderCreateMessage msg) {
    }
}
