package com.fss.biz.activity.service;

import com.fss.biz.activity.model.ActivityCreateCmd;
import com.fss.biz.activity.model.ActivityDetailVO;
import com.fss.biz.activity.model.ActivityListItemVO;
import com.fss.common.result.PageR;
import com.fss.domain.entity.SeckillGoods;

public interface ActivityService {

    long create(ActivityCreateCmd cmd, long adminId);

    /** DRAFT → READY，执行全部发布校验 */
    void publish(long activityId, long adminId);

    /** 任意状态 → CLOSED */
    void close(long activityId, long adminId);

    /** 库存调整：活动进行中改库存的唯一入口 */
    void adjustStock(long goodsId, int delta, String reason, long adminId);

    PageR<ActivityListItemVO> list(Integer status, long page, long size);

    ActivityDetailVO detail(long activityId);

    /**
     * 秒杀链路读取商品配置。返回 null 表示不存在，由调用方决定错误码——
     * 秒杀链路和管理链路对"不存在"的处理方式不同。
     */
    SeckillGoods getGoods(long activityId, long skuId);
}
