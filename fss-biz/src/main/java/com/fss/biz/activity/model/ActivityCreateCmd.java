package com.fss.biz.activity.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ActivityCreateCmd {

    @NotBlank(message = "活动名称不能为空")
    @Size(max = 200)
    private String name;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    @NotEmpty(message = "活动必须包含至少一个秒杀商品")
    @Valid
    private List<SeckillGoodsCmd> goods;
}
