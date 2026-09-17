package com.fss.biz.reconcile;

import com.fss.common.enums.ReconcileTaskStatus;
import com.fss.common.enums.ReconcileTaskType;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.ReconcileTask;
import com.fss.domain.mapper.ReconcileTaskMapper;
import com.fss.infra.metrics.SeckillMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 对账差异的落库出口。三个对账器共用。
 *
 * <p><b>为什么必须去重</b>：库存对账每 5 分钟跑一次，一个没人处理的漂移会被发现 288 次
 * ——一天 288 条同内容的记录。工单列表被刷满之后，"今天有几个新差异"这个最基本的
 * 问题就答不出来了，而对账任务存在的全部意义就是回答它。
 *
 * <p>去重口径是"未关闭的同 {@code (task_type, biz_no)} 记录"。已经人工处理过或
 * 主动忽略过的差异如果再次出现，会重新记一条——那确实是一个新事件，
 * 上次的处理没有让它不再发生，这件事本身就值得知道。
 *
 * <p><b>落库失败只记日志。</b> 对账器不能因为写不进一条审计记录就中断整轮扫描——
 * 后面还有几百个商品要对。但日志必须是 error：差异被发现了却没进工单流，
 * 等于这一轮对账白跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileRecorder {

    private final ReconcileTaskMapper mapper;
    private final SeckillMetrics      metrics;

    /**
     * 记一条差异。
     *
     * @param status {@code AUTO_FIXED} 表示已自动修复（记录仅供审计），
     *               {@code NEED_MANUAL} 表示等人处理
     * @return true 表示新插入了一条；false 表示已有未关闭的同类记录（只更新了明细）
     */
    public boolean record(ReconcileTaskType type, String bizNo, Long activityId, Long skuId,
                          Map<String, Object> detail, ReconcileTaskStatus status,
                          String handleResult) {
        String json = JsonUtil.toJson(detail);
        try {
            // 自动修复成功的记录不去重：它是"发生过一次并已修好"的事实，
            // 每次都该留痕。去重只针对"还没解决"的差异
            if (status != ReconcileTaskStatus.AUTO_FIXED) {
                ReconcileTask open = mapper.selectOpen(type.code(), bizNo);
                if (open != null) {
                    mapper.updateDetail(open.getId(), json, handleResult);
                    log.debug("stage=RECONCILE type={} bizNo={} result=DEDUPED taskId={}",
                            type, bizNo, open.getId());
                    return false;
                }
            }
            mapper.insert(ReconcileTask.builder()
                    .taskType(type.code())
                    .bizNo(bizNo)
                    .activityId(activityId)
                    .skuId(skuId)
                    .detail(json)
                    .status(status.code())
                    .handleResult(handleResult)
                    .build());
            metrics.reconcileDiff(typeTag(type),
                    status == ReconcileTaskStatus.AUTO_FIXED ? "auto_fixed" : "need_manual");
            log.warn("stage=RECONCILE type={} bizNo={} status={} detail={} handleResult={}",
                    type, bizNo, status, json, handleResult);
            return true;
        } catch (Exception e) {
            log.error("stage=RECONCILE type={} bizNo={} result=RECORD_FAILED "
                    + "差异已发现但没进工单流", type, bizNo, e);
            return false;
        }
    }

    /** 指标标签用小写英文：Grafana 里的图例不适合放中文，而枚举的 desc 是中文 */
    private static String typeTag(ReconcileTaskType type) {
        return switch (type) {
            case QUALIFICATION -> "qualification";
            case STOCK         -> "stock";
            case PAYMENT       -> "payment";
            case ORDER         -> "order";
        };
    }
}
