package com.fss.biz.activity.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class ActivityListItemVO {

    private Long          activityId;
    private String        name;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer       status;
    private String        statusDesc;
    private int           goodsCount;
}
