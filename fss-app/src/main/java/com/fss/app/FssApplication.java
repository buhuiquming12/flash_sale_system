package com.fss.app;

import com.fss.infra.config.FssProperties;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 单 jar 三角色：{@code --spring.profiles.active=web|consumer|job}。
 *
 * <pre>
 * java -jar fss-app.jar --spring.profiles.active=web,prod      --server.port=8080
 * java -jar fss-app.jar --spring.profiles.active=consumer,prod --server.port=8090
 * java -jar fss-app.jar --spring.profiles.active=job,prod      --server.port=8099
 * 本地开发：--spring.profiles.active=web,job,dev
 * </pre>
 */
@Slf4j
@SpringBootApplication(scanBasePackages = "com.fss")
@MapperScan("com.fss.domain.mapper")
@EnableConfigurationProperties(FssProperties.class)
@EnableScheduling
public class FssApplication {

    public static void main(String[] args) {
        SpringApplication.run(FssApplication.class, args);
    }

    /**
     * 启动自检。
     *
     * <p>{@code allow-repurchase-after-cancel} 一旦被误开成 true，
     * 表现是"用户取消后重抢 → 一直创建失败 → 一直补偿"的死循环，而且只在生产的
     * 真实并发下才显现。宁可启动失败，也不让这种配置进到运行时。
     *
     * <p>同理适用于两个密钥，见 {@link #assertSecretsInjected}。
     */
    @Bean
    public ApplicationListener<ContextRefreshedEvent> configSelfCheck(FssProperties props,
                                                                     Environment env) {
        return event -> {
            if (props.getSeckill().isAllowRepurchaseAfterCancel()) {
                throw new IllegalStateException("""
                        fss.seckill.allow-repurchase-after-cancel 必须为 false。
                        t_order 的 uk_activity_sku_user 对已取消订单同样生效，
                        允许重抢会导致"创建失败 → 补偿回补 → 再抢"的死循环。
                        详见 docs/00-设计总览.md 决策 1。""");
            }
            assertSecretsInjected(props, env);
            log.info("配置自检通过: payTimeout={} closeScanDelay={}",
                    props.getOrder().getPayTimeout(), props.getOrder().getCloseScanDelay());
        };
    }

    /** 与 {@code JwtService}、{@code PaySignUtil} 的校验保持一致 */
    private static final int MIN_SECRET_LENGTH = 32;

    /**
     * 非 dev 的 web 角色必须真正注入密钥，否则拒绝启动。
     *
     * <p><b>为什么必须在启动时拦。</b> 两个密钥都有静默可用的开发默认值
     * （{@link FssProperties.Jwt#DEV_DEFAULT_SECRET} 与字段默认值，
     * 以及 {@code application.yml} 里 {@code ${FSS_JWT_SECRET:...}} 的占位符回退），
     * 所以生产忘记注入环境变量的表现是<b>应用一切正常地启动</b>。而这两个密钥
     * 决定的恰恰是最直接的资损路径：
     * <ul>
     *   <li>JWT 密钥 —— 任何看过源码的人都能签发 {@code role=1} 的管理员 token；
     *       借助自己账号的真实 {@code jti}（先注册登录拿一个）还能绕过吊销校验。</li>
     *   <li>支付回调密钥 —— 可以伪造成功的支付回调，把自己的订单置为已支付。</li>
     * </ul>
     * 两者都是"启动时静默、被发现时已经晚了"的类型，属于宁可起不来也不能放行的配置。
     *
     * <p><b>为什么只对 web 角色强制。</b> {@code consumer} / {@code job} 不暴露
     * HTTP 认证入口，不该因为它们起不来而阻塞整个部署。
     *
     * <p><b>为什么不去掉 {@code application.yml} 的占位符回退就够了。</b> 不够——
     * {@link FssProperties} 的字段默认值同样会生效，只改一处挡不住，所以闸门放在这里，
     * 对"值从哪来"不作假设。
     */
    private static void assertSecretsInjected(FssProperties props, Environment env) {
        if (!env.acceptsProfiles(Profiles.of("web")) || env.acceptsProfiles(Profiles.of("dev"))) {
            return;
        }
        requireInjected("fss.jwt.secret", "FSS_JWT_SECRET",
                props.getJwt().getSecret(), FssProperties.Jwt.DEV_DEFAULT_SECRET,
                "可伪造管理员 token");
        requireInjected("fss.pay.notify-secret", "FSS_PAY_SECRET",
                props.getPay().getNotifySecret(), FssProperties.Pay.DEV_DEFAULT_NOTIFY_SECRET,
                "可伪造支付回调");
    }

    private static void requireInjected(String prop, String envVar, String actual,
                                        String devDefault, String consequence) {
        if (actual == null || actual.isBlank() || devDefault.equals(actual)) {
            throw new IllegalStateException(prop + " 仍是开发默认值，未注入 " + envVar
                    + "。该密钥用于生产会" + consequence
                    + "。dev profile 下可继续使用默认值，非 dev 的 web 角色必须显式注入。");
        }
        if (actual.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(prop + " 至少需要 " + MIN_SECRET_LENGTH
                    + " 字节，当前 " + actual.length() + "。HMAC-SHA256 的密钥短于 256 位会让"
                    + "签名强度形同虚设。");
        }
    }
}
