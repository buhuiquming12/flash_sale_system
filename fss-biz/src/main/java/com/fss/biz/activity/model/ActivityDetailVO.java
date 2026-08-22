package com.fss.biz.activity.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动详情。
 *
 * <p>{@code @NoArgsConstructor} 是必需的：这个 VO 会被序列化进 Redis
 * （{@code activity:detail:{id}} 缓存），Jackson 反序列化需要无参构造。
 * 只有 {@code @Builder + @AllArgsConstructor} 时反序列化会报
 * "cannot construct instance: no Creators"，而那个错误发生在缓存<b>回读</b>时——
 * 第一次写入完全正常，几分钟后才炸。
 *
 * <p><b>哪些字段被缓存、哪些每次重算</b>：缓存里存的是活动的静态骨架
 * （名称、时间、商品、价格、总库存）。{@link #serverTime}、{@link #status}
 * 和每个商品的 {@link GoodsItemVO#remainStock} 在每次读取时覆盖，
 * 见 {@code ActivityServiceImpl#detail}。否则一个 2 小时 TTL 的缓存会让
 * 倒计时和库存显示整整冻结 2 小时。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityDetailVO {

    private Long          activityId;
    private String        name;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer       status;
    private String        statusDesc;

    /**
     * 服务端当前时间。
     *
     * <p><b>必须返回</b>：客户端倒计时要基于服务端时间校准本地时钟偏差，
     * 否则用户本地时间快 30 秒就会在活动未开始时疯狂提交，全部被拒。
     */
    private LocalDateTime serverTime;

    private List<GoodsItemVO> goodsList;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GoodsItemVO {
        private Long                    skuId;
        private String                  productTitle;
        private String                  spec;
        private java.math.BigDecimal    originPrice;
        private java.math.BigDecimal    seckillPrice;
        private Integer                 totalStock;
        /**
         * 近似值，仅用于展示。真实结论以秒杀接口为准。
         *
         * <p>降级 Level 1 起<b>置为 null</b>，改用 {@link #stockLevel}。
         * 这么做省掉的正是每个 SKU 一次 Redis 读——一个活动 10 个 SKU 就是 10 次往返，
         * 而详情接口是全站 QPS 最高的那个。
         */
        private Integer                 remainStock;
        /** 粗粒度库存档位。始终有值，降级时它是唯一的库存信息来源 */
        private StockLevel              stockLevel;
        private Integer                 limitPerUser;
        private boolean                 soldOut;
        private String                  image;
    }

    /**
     * 库存档位。
     *
     * <p>存在的理由是降级：{@code remainStock} 要一次 Redis 读，而"还有没有货"
     * 这个信息用 DB 快照就够。同时它对<b>正常</b>状态也有价值——
     * 前端可以只靠这个字段决定文案（"仅剩 3 件"用精确值，"库存紧张"用档位），
     * 不必自己定阈值，于是降级前后的展示逻辑是同一套。
     */
    public enum StockLevel {
        /** 充足：> 20% */
        AVAILABLE,
        /** 紧张：0 < remain <= 20% */
        LOW,
        SOLD_OUT;

        public static StockLevel of(Integer remain, Integer total) {
            if (remain == null || remain <= 0) {
                return SOLD_OUT;
            }
            if (total == null || total <= 0) {
                return AVAILABLE;
            }
            return remain * 5 <= total ? LOW : AVAILABLE;
        }
    }
}
