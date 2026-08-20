package com.fss.ratelimit;

import com.fss.app.FssApplication;
import com.fss.biz.user.core.JwtService;
import com.fss.common.result.R;
import com.fss.test.IntegrationTestBase;
import com.fss.test.TestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流在 HTTP 层的验收：<b>单用户 100 QPS → 大部分返回 429</b>
 * （docs/08 §1 阶段二的第四条验收标准）。
 *
 * <p>为什么非要起真 Web 环境：{@code RateLimitTest} 已经证明令牌桶本身是对的，
 * 但"对的令牌桶"和"生效的限流"之间还隔着三件事——拦截器有没有挂上、
 * 挂在哪些路径上、返回的状态码和响应体对不对。这三样只能从 HTTP 这一侧看。
 *
 * <p>用 {@code @SpringBootTest} 重新标注覆盖基类的 {@code WebEnvironment.NONE}；
 * 容器与数据源仍然来自基类的静态字段与 {@code @DynamicPropertySource}。
 */
@ActiveProfiles({"test", "web"})
@SpringBootTest(classes = FssApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // 基类配置里限流是关掉的（并发正确性用例不该被限流干扰），这里单独打开
        "fss.ratelimit.enabled=true",
        "fss.ratelimit.user-qps=2",
        "fss.ratelimit.burst-factor=2",
        // IP 桶必须放宽：测试里所有请求都来自 127.0.0.1，
        // 用默认的 20 QPS 会先撞 IP 桶，那就测不到用户桶了
        "fss.ratelimit.ip-qps=100000"})
class RateLimitWebTest extends IntegrationTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired JwtService       jwtService;
    @Autowired TestFixture      fixture;

    @Test
    @DisplayName("单用户对秒杀接口打 100 次 → 绝大多数返回 429，且带 Retry-After")
    void 单用户高频提交大部分被限流() {
        TestFixture.Activity act = fixture.createRunningActivity(1000);
        long userId = fixture.createUser();
        HttpHeaders headers = auth(userId);

        int ok = 0;
        int limited = 0;
        String retryAfter = null;
        for (int i = 0; i < 100; i++) {
            ResponseEntity<String> resp = rest.exchange("/api/seckill/do", HttpMethod.POST,
                    new HttpEntity<>(body(act), headers), String.class);
            if (resp.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                limited++;
                if (retryAfter == null) {
                    retryAfter = resp.getHeaders().getFirst("Retry-After");
                }
            } else {
                ok++;
            }
        }

        assertThat(limited)
                .as("单用户 2 QPS、桶容量 4，100 次连打应绝大多数被拦。"
                        + "放行 %d 拦截 %d", ok, limited)
                .isGreaterThanOrEqualTo(90);
        assertThat(retryAfter)
                .as("必须告诉客户端多久后再来。不给的话客户端只会立刻重试，"
                        + "让限流器更忙")
                .isEqualTo("1");
    }

    @Test
    @DisplayName("429 的响应体是统一格式 + 错误码 1004，不是容器默认的 HTML 错误页")
    void 限流响应体是统一格式() {
        TestFixture.Activity act = fixture.createRunningActivity(10);
        long userId = fixture.createUser();
        HttpHeaders headers = auth(userId);

        ResponseEntity<R<Object>> last = null;
        for (int i = 0; i < 20; i++) {
            last = rest.exchange("/api/seckill/do", HttpMethod.POST,
                    new HttpEntity<>(body(act), headers),
                    new ParameterizedTypeReference<>() { });
            if (last.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                break;
            }
        }

        assertThat(last).isNotNull();
        assertThat(last.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(last.getBody()).isNotNull();
        assertThat(last.getBody().getCode())
                .as("拦截器是在 DispatcherServlet 之前返回的，"
                        + "@RestControllerAdvice 根本看不到它 —— 响应体必须由拦截器自己写。"
                        + "忘了写的话客户端收到的是 Tomcat 的 HTML 错误页，"
                        + "前端的 JSON 解析会直接抛异常")
                .isEqualTo(1004);
    }

    @Test
    @DisplayName("结果查询走单独的档位（5 QPS），不与提交共用 2 QPS 的桶")
    void 查询接口档位更宽() {
        long userId = fixture.createUser();
        HttpHeaders headers = auth(userId);

        // 只关心是否被限流，不关心业务上查不到（那是 200 + 业务错误码）
        int limited = 0;
        for (int i = 0; i < 6; i++) {
            ResponseEntity<String> resp = rest.exchange(
                    "/api/seckill/result?requestNo=R_NOT_EXIST_0001", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            if (resp.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                limited++;
            }
        }
        assertThat(limited)
                .as("轮询接口如果和提交共用 2 QPS 的桶，客户端按 300ms 间隔轮询"
                        + "立刻就会被自己的限流打死")
                .isZero();
    }

    private HttpHeaders auth(long userId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        // 直接签一个 token，不走 /login：BCrypt 强度 10 每次约 50ms，
        // 而这里要验的是限流，不是登录
        h.setBearerAuth(jwtService.issue(userId, "u" + userId, 0).token());
        return h;
    }

    private Map<String, Object> body(TestFixture.Activity act) {
        return Map.of("activityId", act.activityId(), "skuId", act.skuId(), "quantity", 1);
    }
}
