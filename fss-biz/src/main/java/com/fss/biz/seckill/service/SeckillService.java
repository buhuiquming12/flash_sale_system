package com.fss.biz.seckill.service;

import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.model.SeckillResultVO;
import com.fss.biz.seckill.model.SeckillSubmitVO;

public interface SeckillService {

    /**
     * 提交秒杀。
     *
     * <p>阶段二：Redis Lua 原子判扣 + 同步落库，直接返回订单号。
     * 阶段三：判扣通过后投递 MQ，返回"排队中"，由客户端轮询
     * {@link #queryResult(String, Long, Long, long)}。
     */
    SeckillSubmitVO submit(SeckillCmd cmd, long userId);

    /**
     * 查询秒杀结论。
     *
     * <p>{@code activityId} 与 {@code skuId} 用于定位 Redis 里的请求结果 key ——
     * 那个 key 带 {@code {activityId:skuId}} hash tag（它必须和库存、购买标记
     * 落在同一个 Cluster 槽，才能被同一段 Lua 原子操作），
     * 只有 requestNo 是拼不出 key 的。客户端刚调过秒杀接口，这两个值它一定有。
     *
     * <p>传 null 时跳过 Redis 直接回查数据库，结论一样，只是慢一点。
     */
    SeckillResultVO queryResult(String requestNo, Long activityId, Long skuId, long userId);

    /** 只有 requestNo 时的便捷入口：跳过 Redis，直接回查数据库 */
    default SeckillResultVO queryResult(String requestNo, long userId) {
        return queryResult(requestNo, null, null, userId);
    }
}
