package com.fss.app.config;

import com.fss.app.interceptor.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 限流拦截器的挂载点。
 *
 * <p><b>只挂在需要限流的路径上，不是全站。</b> 全站限流会把
 * {@code /actuator/health} 和静态资源也算进桶里——健康检查每秒探一次就能把
 * IP 桶吃掉一部分，压测时演示页面的图片请求也会挤掉真正的秒杀请求配额。
 *
 * <p>登录/注册接口<b>刻意排除在外</b>：它们的防护是 BCrypt 本身的耗时
 * （强度 10 约 50ms，天然限速）加上风控规则，用同一个令牌桶反而会因为
 * 一个 NAT 出口下的正常用户互相挤占而误伤。
 */
@Configuration
@Profile("web")
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(
                        "/api/seckill/**",     // 提交、令牌、结果轮询
                        "/api/activity/**",    // 详情与列表，防爬
                        "/api/order/**",
                        "/api/pay/**")
                // 支付渠道回调必须排除：它来自渠道方固定的几个 IP、量大且集中，
                // 按 IP 限流一定会误杀。回调的防护是验签 + 时间戳 + 幂等，
                // 而且拦掉一次成功回调的代价是用户付了钱订单没变，
                // 这是限流最不该介入的地方
                .excludePathPatterns("/api/pay/notify");
    }
}
