package com.fss.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 对账任务。对账发现的差异一律落库，能自动修复的标 1，不能的标 2 等人工。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_reconcile_task")
public class ReconcileTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** {@link com.fss.common.enums.ReconcileTaskType} */
    private Integer taskType;
    private String  bizNo;
    private Long    activityId;
    private Long    skuId;
    /** JSON 差异明细 */
    private String  detail;
    /** {@link com.fss.common.enums.ReconcileTaskStatus} */
    private Integer status;
    private String  handleResult;
    private String  handler;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
