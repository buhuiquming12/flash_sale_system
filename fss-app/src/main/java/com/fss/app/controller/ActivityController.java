package com.fss.app.controller;

import com.fss.biz.activity.model.ActivityDetailVO;
import com.fss.biz.activity.model.ActivityListItemVO;
import com.fss.biz.activity.service.ActivityService;
import com.fss.common.result.PageR;
import com.fss.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("web")
@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    @GetMapping("/list")
    public R<PageR<ActivityListItemVO>> list(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return R.ok(activityService.list(status, page, Math.min(size, 50)));
    }

    @GetMapping("/{activityId}")
    public R<ActivityDetailVO> detail(@PathVariable long activityId) {
        return R.ok(activityService.detail(activityId));
    }
}
