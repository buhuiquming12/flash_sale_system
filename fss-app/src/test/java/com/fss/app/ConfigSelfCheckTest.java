package com.fss.app;

import com.fss.infra.config.FssProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 启动自检里的密钥闸门。
 *
 * <p>这两个密钥的失效方式是"静默"：配置里有开发默认值、{@code FssProperties} 的字段
 * 默认值也是同一个值，所以生产忘记注入环境变量的表现是<b>应用一切正常地启动</b>，
 * 而后果是任何人都能签发 {@code role=1} 的管理员 token、或伪造支付回调。
 * 启动时拒绝是这个失效模式下唯一能被发现的时机，所以值得单独锁住。
 */
class ConfigSelfCheckTest {

    private static FssProperties propsWith(String jwtSecret, String paySecret) {
        FssProperties props = new FssProperties();
        props.getJwt().setSecret(jwtSecret);
        props.getPay().setNotifySecret(paySecret);
        return props;
    }

    private static ApplicationListener<ContextRefreshedEvent> listener(FssProperties props,
                                                                      String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new FssApplication().configSelfCheck(props, env);
    }

    private static void fire(ApplicationListener<ContextRefreshedEvent> l) {
        l.onApplicationEvent(null);
    }

    @Test
    @DisplayName("dev profile 下允许使用开发默认密钥")
    void dev允许默认密钥() {
        FssProperties props = new FssProperties();
        assertThatCode(() -> fire(listener(props, "web", "dev"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非 dev 的 web 角色用开发默认 JWT 密钥必须启动失败")
    void 生产未注入jwt密钥则拒绝启动() {
        FssProperties props = new FssProperties();
        assertThatThrownBy(() -> fire(listener(props, "web", "prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fss.jwt.secret")
                .hasMessageContaining("FSS_JWT_SECRET");
    }

    @Test
    @DisplayName("支付密钥同样要拦——它决定的是能不能伪造回调")
    void 生产未注入支付密钥则拒绝启动() {
        FssProperties props = propsWith(
                "a-real-injected-jwt-secret-32-bytes-min", "fss-mock-pay-secret-change-me-in-prod");
        assertThatThrownBy(() -> fire(listener(props, "web", "prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fss.pay.notify-secret")
                .hasMessageContaining("FSS_PAY_SECRET");
    }

    @Test
    @DisplayName("密钥过短也要拦：HMAC 密钥短于 256 位，签名强度形同虚设")
    void 过短的密钥被拒绝() {
        String short31 = "1234567890123456789012345678901";   // 31 字节
        assertThatThrownBy(() -> fire(listener(propsWith(short31, short31), "web", "prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少需要 32");
    }

    @Test
    @DisplayName("正确注入时放行")
    void 正确注入则通过() {
        FssProperties props = propsWith("a-real-injected-jwt-secret-32-bytes-min",
                "a-real-injected-pay-secret-32-bytes-min");
        assertThatCode(() -> fire(listener(props, "web", "prod"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("consumer / job 角色不强制——它们不暴露 HTTP 认证入口")
    void 非web角色不强制注入() {
        FssProperties props = new FssProperties();
        assertThatCode(() -> fire(listener(props, "consumer"))).doesNotThrowAnyException();
        assertThatCode(() -> fire(listener(props, "job"))).doesNotThrowAnyException();
    }
}
