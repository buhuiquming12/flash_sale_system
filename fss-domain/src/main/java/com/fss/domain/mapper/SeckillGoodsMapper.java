package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.entity.SeckillGoods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 秒杀商品 Mapper —— 全系统最关键的几条 SQL 都在这里。
 *
 * <p><b>库存口径</b>：{@code total_stock = available_stock + locked_stock + sold_stock}
 * 恒成立（异步化后右侧再加上"Redis 已预扣但尚未落库"的排队中数量）。
 * 下单时 available → locked，支付时 locked → sold，取消时 locked → available。
 * 设计文档 02 的扣减示例把 {@code sold_stock} 直接加了，与 04 的
 * {@code moveLockedToSold} 冲突，这里按 locked/sold 分离的口径实现，
 * 否则支付时会把 sold 加两次、库存等式立刻不成立。
 */
@Mapper
public interface SeckillGoodsMapper extends BaseMapper<SeckillGoods> {

    /**
     * 条件扣减 —— 防超卖的核心。
     *
     * <p>一条语句完成"判断 + 扣减"，行锁只在语句执行期间持有。
     * <b>影响行数 0 就是库存不足</b>，调用方据此抛 {@code STOCK_NOT_ENOUGH}。
     *
     * <p>为什么不用 {@code SELECT ... FOR UPDATE} 再更新：两次往返、持锁时间长。
     * 为什么不用 {@code version} 乐观锁：1000 并发下 CAS 重试率极高，退化成忙等。
     */
    @Update("""
            UPDATE t_seckill_goods
               SET available_stock = available_stock - #{qty},
                   locked_stock    = locked_stock + #{qty}
             WHERE id = #{id}
               AND status <> 0
               AND available_stock >= #{qty}
            """)
    int deductStock(@Param("id") long id, @Param("qty") int qty);

    /**
     * 取消回补：locked → available，并累计 released（对账用）。
     *
     * <p>{@code status = CASE WHEN status = 2 THEN 1 ELSE status END} 把售罄标记
     * 改回在售——回补后库存又有了，必须让后续请求能抢到。但如果是被管理员停售(0)，
     * 就保持停售，不能因为一次回补把停售的商品又打开。
     */
    @Update("""
            UPDATE t_seckill_goods
               SET available_stock = available_stock + #{qty},
                   locked_stock    = locked_stock - #{qty},
                   released_stock  = released_stock + #{qty},
                   status          = CASE WHEN status = 2 THEN 1 ELSE status END
             WHERE activity_id = #{activityId}
               AND sku_id = #{skuId}
               AND locked_stock >= #{qty}
            """)
    int restoreStock(@Param("activityId") long activityId,
                     @Param("skuId") long skuId,
                     @Param("qty") int qty);

    /** 支付成功：locked → sold。此时库存真正卖出，不再可回补 */
    @Update("""
            UPDATE t_seckill_goods
               SET locked_stock = locked_stock - #{qty},
                   sold_stock   = sold_stock + #{qty}
             WHERE activity_id = #{activityId}
               AND sku_id = #{skuId}
               AND locked_stock >= #{qty}
            """)
    int moveLockedToSold(@Param("activityId") long activityId,
                         @Param("skuId") long skuId,
                         @Param("qty") int qty);

    /** 售罄标记，供停售/售罄快速失败用 */
    @Update("""
            UPDATE t_seckill_goods
               SET status = #{status}
             WHERE activity_id = #{activityId}
            """)
    int updateStatusByActivity(@Param("activityId") long activityId,
                               @Param("status") int status);

    @Select("""
            SELECT * FROM t_seckill_goods
             WHERE activity_id = #{activityId} AND sku_id = #{skuId}
            """)
    SeckillGoods selectByActivitySku(@Param("activityId") long activityId,
                                     @Param("skuId") long skuId);

    @Select("SELECT * FROM t_seckill_goods WHERE activity_id = #{activityId} ORDER BY id")
    List<SeckillGoods> selectByActivity(@Param("activityId") long activityId);

    @Select("SELECT sku_id FROM t_seckill_goods WHERE activity_id = #{activityId}")
    List<Long> selectSkuIds(@Param("activityId") long activityId);

    /** 对账用：进行中活动下的全部秒杀商品 */
    @Select("""
            SELECT g.* FROM t_seckill_goods g
              JOIN t_seckill_activity a ON a.id = g.activity_id
             WHERE a.status = 2
            """)
    List<SeckillGoods> selectActiveGoods();

    /** 管理调整。同时调整 total 与 available，保证库存等式仍然成立 */
    @Update("""
            UPDATE t_seckill_goods
               SET total_stock     = total_stock + #{delta},
                   available_stock = available_stock + #{delta},
                   status          = CASE WHEN status = 2 AND #{delta} > 0 THEN 1 ELSE status END
             WHERE id = #{id}
               AND available_stock + #{delta} >= 0
               AND total_stock + #{delta} >= 0
            """)
    int adjustStock(@Param("id") long id, @Param("delta") int delta);

    /** 管理操作低频，可用行锁 */
    @Select("SELECT * FROM t_seckill_goods WHERE id = #{id} FOR UPDATE")
    SeckillGoods selectByIdForUpdate(@Param("id") long id);
}
