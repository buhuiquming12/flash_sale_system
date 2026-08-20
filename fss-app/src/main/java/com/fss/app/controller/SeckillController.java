package com.fss.app.controller;

import com.fss.app.controller.dto.SeckillSubmitReq;
import com.fss.biz.seckill.core.SeckillTokenService;
import com.fss.biz.seckill.model.SeckillCmd;
import com.fss.biz.seckill.model.SeckillResultVO;
import com.fss.biz.seckill.model.SeckillSubmitVO;
import com.fss.biz.seckill.service.SeckillService;
import com.fss.common.context.UserContext;
import com.fss.common.error.Assert;
import com.fss.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Profile("web")
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService      seckillService;
    private final SeckillTokenService tokenService;

    /**
     * 领取秒杀令牌，返回令牌与应当提交的动态路径。
     *
     * <p>令牌只提高脚本作者的成本，不是安全措施——详见
     * {@link SeckillTokenService} 的类注释。真正的防线是限流与一人一单。
     */
    @PostMapping("/token")
    public R<Map<String, String>> token(@RequestParam long activityId,
                                        @RequestParam long skuId) {
        Assert.require(activityId > 0 && skuId > 0, "活动或 SKU 不合法");
        long userId = UserContext.userId();
        String token = tokenService.issue(userId, activityId, skuId);
        return R.ok(Map.of("token", token, "path", tokenService.dynamicPath(token)));
    }

    /**
     * 提交秒杀（动态路径）。
     *
     * <p>令牌同时是路径的一部分和一次性凭据：{@code GETDEL} 消费后同一个令牌
     * 无法复用。客户端必须先调 {@code /token} 才知道往哪 POST。
     */
    @PostMapping("/{token}/do")
    public R<SeckillSubmitVO> submitWithToken(@PathVariable String token,
                                              @Valid @RequestBody SeckillSubmitReq req) {
        SeckillCmd cmd = SeckillCmd.of(req.getActivityId(), req.getSkuId(), req.getQuantity());
        cmd.setToken(token);
        return R.ok(seckillService.submit(cmd, UserContext.userId()));
    }

    /**
     * 提交秒杀（固定路径，不校验令牌）。
     *
     * <p>保留它是为了压测与联调：JMeter 脚本不必先取令牌再拼路径，
     * 否则压测测的一半是令牌接口的性能。
     *
     * <p><b>生产环境应当在网关层只放开动态路径</b>，把这个入口挡在外面。
     * 代码里保留而部署时屏蔽，比为了安全把压测入口删掉、
     * 然后每次压测再临时改代码要可靠。
     */
    @PostMapping("/do")
    public R<SeckillSubmitVO> submit(@Valid @RequestBody SeckillSubmitReq req) {
        SeckillCmd cmd = SeckillCmd.of(req.getActivityId(), req.getSkuId(), req.getQuantity());
        // userId 只从 JWT 取
        return R.ok(seckillService.submit(cmd, UserContext.userId()));
    }

    /**
     * 查询秒杀结论。
     *
     * <p>{@code activityId} 与 {@code skuId} 是可选的，但强烈建议带上：
     * 带了才能命中 Redis 里的请求结果（key 含 {@code {activityId:skuId}} hash tag），
     * 不带就得回查数据库。客户端刚提交过秒杀，这两个值它一定有。
     */
    @GetMapping("/result")
    public R<SeckillResultVO> result(@RequestParam String requestNo,
                                     @RequestParam(required = false) Long activityId,
                                     @RequestParam(required = false) Long skuId) {
        return R.ok(seckillService.queryResult(requestNo, activityId, skuId,
                UserContext.userId()));
    }
}
