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
     * <p><b>由 {@code fss.seckill.allow-tokenless-submit} 控制，默认 false。</b>
     * 关闭时本接口返回 403，客户端必须先走 {@code /api/seckill/token}。
     * 只有 {@code dev} 与 {@code perf} profile 打开它。
     *
     * <p>早先这里只靠一行注释约定"生产应在网关层屏蔽"——那是把安全边界
     * 寄托在部署配置上，而代码本身在任何环境下都是放行的。配置开关让
     * 默认姿态变安全，压测能力仍然保留。
     *
     * <p>注意这与认证无关：本接口始终需要 JWT，约束的是"抢购资格"而不是"用户身份"。
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
