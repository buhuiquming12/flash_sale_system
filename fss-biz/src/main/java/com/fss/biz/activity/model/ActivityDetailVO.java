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
        /** 近似值，仅用于展示。真实结论以秒杀接口为准 */
        private Integer                 remainStock;
        private Integer                 limitPerUser;
        private boolean                 soldOut;
        private String                  image;
    }
}
