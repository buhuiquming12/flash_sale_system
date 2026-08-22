package com.fss.app.controller;

import com.fss.biz.activity.model.ActivityCreateCmd;
import com.fss.biz.activity.service.ActivityService;
import com.fss.biz.activity.service.WarmupService;
import com.fss.biz.audit.AdminAuditService;
import com.fss.biz.product.model.ProductCreateCmd;
import com.fss.biz.product.model.SkuCreateCmd;
import com.fss.biz.product.model.SkuVO;
import com.fss.biz.product.service.ProductService;
import com.fss.common.context.UserContext;
import com.fss.common.result.R;
import com.fss.infra.degrade.DegradeSwitch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理接口。鉴权由 {@code AdminAuthFilter} 按路径前缀统一处理，
 * 全部操作由各 Service 内部写 {@code t_admin_log}。
 */
@Profile("web")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ProductService  productService;
    private final ActivityService activityService;
    private final WarmupService   warmupService;
    private final DegradeSwitch   degradeSwitch;
    private final AdminAuditService audit;

    @PostMapping("/product")
    public R<Map<String, Long>> createProduct(@Valid @RequestBody ProductCreateCmd cmd) {
        return R.ok(Map.of("productId", productService.createProduct(cmd, UserContext.userId())));
    }

    @PostMapping("/sku")
    public R<Map<String, Long>> createSku(@Valid @RequestBody SkuCreateCmd cmd) {
        return R.ok(Map.of("skuId", productService.createSku(cmd, UserContext.userId())));
    }

    @GetMapping("/sku/list")
    public R<List<SkuVO>> listSkus(@RequestParam long productId) {
        return R.ok(productService.listSkus(productId));
    }

    @PostMapping("/activity")
    public R<Map<String, Long>> createActivity(@Valid @RequestBody ActivityCreateCmd cmd) {
        return R.ok(Map.of("activityId", activityService.create(cmd, UserContext.userId())));
    }

    @PostMapping("/activity/{id}/publish")
    public R<Void> publish(@PathVariable long id) {
        activityService.publish(id, UserContext.userId());
        return R.ok();
    }

    @PostMapping("/activity/{id}/close")
    public R<Void> close(@PathVariable long id) {
        activityService.close(id, UserContext.userId());
        return R.ok();
    }

    /**
     * 手动预热。
     *
     * <p>定时任务会在活动开始前自动预热，这个接口用于两种情况：
     * 预热失败后人工重试、以及 Redis 数据意外丢失后补数据（故障用例 F2）。
     *
     * <p>可以放心重复调用：库存用 {@code setIfAbsent} 初始化，
     * 不会把活动进行中已扣减的库存重置回初始值。
     */
    @PostMapping("/activity/{id}/warmup")
    public R<Void> warmup(@PathVariable long id) {
        warmupService.warmupOne(id);
        return R.ok();
    }

    /** 活动进行中修改库存的唯一入口 */
    @PostMapping("/goods/{id}/stock-adjust")
    public R<Void> adjustStock(@PathVariable long id, @Valid @RequestBody StockAdjustReq req) {
        activityService.adjustStock(id, req.getDelta(), req.getReason(), UserContext.userId());
        return R.ok();
    }

    // ==================================================================
    // 降级开关（阶段四）
    // ==================================================================

    /**
     * 人工设置降级等级。
     *
     * <p>写的是 {@code degrade:level}（人工那一半，<b>无 TTL</b>），
     * 生效等级取人工与自动的最大值——所以自动监控恢复到 0 也盖不过这里的决定。
     * 解除需要显式再调一次并传 0。
     *
     * <p><b>要求填 reason</b> 不是形式主义：降级是会被复盘的操作，
     * "谁在什么时候因为什么降的级"必须能从审计日志里查出来。
     */
    @PostMapping("/degrade")
    public R<Map<String, Object>> setDegrade(@Valid @RequestBody DegradeReq req) {
        degradeSwitch.setManualLevel(req.getLevel(), req.getReason());
        audit.record(UserContext.userId(), "DEGRADE_SET", "SYSTEM", 0L,
                null, req.getLevel() + ":" + req.getReason());
        return R.ok(status());
    }

    @GetMapping("/degrade")
    public R<Map<String, Object>> getDegrade() {
        return R.ok(status());
    }

    private Map<String, Object> status() {
        return Map.of(
                "level", degradeSwitch.getLevel(),
                "autoLevel", degradeSwitch.getAutoLevel(),
                "seckillEnabled", degradeSwitch.seckillEnabled(),
                "showExactStock", degradeSwitch.showExactStock(),
                "browseEnabled", degradeSwitch.browseEnabled(),
                "pollIntervalMs", degradeSwitch.pollIntervalMs());
    }

    @Data
    public static class StockAdjustReq {
        @NotNull
        private Integer delta;
        @NotBlank(message = "必须填写调整原因")
        private String  reason;
    }

    @Data
    public static class DegradeReq {
        @NotNull
        @Min(value = 0, message = "降级等级范围 0~4")
        @Max(value = DegradeSwitch.MAX_LEVEL, message = "降级等级范围 0~4")
        private Integer level;
        @NotBlank(message = "必须填写降级原因，它会进审计日志")
        private String  reason;
    }
}
