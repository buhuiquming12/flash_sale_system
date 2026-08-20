package com.fss.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理操作审计。
 *
 * <p>库存调整、活动发布/关闭必须落审计，这是"活动进行中只能通过专门流程改库存"
 * 的可追溯依据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_admin_log")
public class AdminLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long   adminId;
    /** 如 ACTIVITY_PUBLISH / STOCK_ADJUST */
    private String action;
    private String targetType;
    private String targetId;
    private String beforeVal;
    private String afterVal;
    private String ip;

    private LocalDateTime createTime;

    public static AdminLog of(long adminId, String action, String targetType,
                              Object targetId, String before, String after) {
        return AdminLog.builder()
                .adminId(adminId)
                .action(action)
                .targetType(targetType)
                .targetId(String.valueOf(targetId))
                .beforeVal(before)
                .afterVal(after)
                .build();
    }
}
