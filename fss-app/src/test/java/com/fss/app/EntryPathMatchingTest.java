package com.fss.app;

import com.fss.app.filter.AdminAuthFilter;
import com.fss.app.filter.JwtAuthFilter;
import com.fss.app.filter.RequestPathGuardFilter;
import com.fss.app.interceptor.RateLimitInterceptor;
import com.fss.biz.user.core.JwtService;
import com.fss.biz.user.core.NoopTokenRevocationStore;
import com.fss.infra.config.FssProperties;
import com.fss.infra.ratelimit.RateLimiter;
import com.fss.infra.trace.TraceIdFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 入口层的不变量：<b>鉴权与限流按路径做判定，而路由由 Spring MVC 决定，
 * 两者看到的必须是同一条路径。</b>
 *
 * <h3>为什么用真容器 + 真 DispatcherServlet，不用 MockMvc</h3>
 * {@code JwtAuthFilter} / {@code AdminAuthFilter} 的判定依据是
 * {@link HttpServletRequest#getRequestURI()}，而它返回的是客户端发来的<b>原始串</b>
 * （Tomcat 不做 {@code .}/{@code ..} 归并），路由则由 Spring MVC 按同一串匹配 handler。
 * MockMvc 不经过 Tomcat 的 URI 处理，用它验这个问题等于把待验证的前提假设成结论；
 * 而且只挂一个裸 servlet 也不行——那测的是容器级映射（Tomcat 会规范化），
 * 不是生产里真正发生的 Spring 路由。
 *
 * <h3>这个类防的是什么</h3>
 * {@code /api/activity/../admin/degrade} 这类路径曾经能让
 * {@code JwtAuthFilter} 因 {@code /api/activity/} 前缀放行、
 * {@code AdminAuthFilter} 因"不以 {@code /api/admin/} 开头"整个跳过。
 * 当时落不到管理接口（Spring 也匹配不上 {@code /api/admin/**}），
 * 但那是巧合：只要有人在 {@code /api/activity/} 下加一个 {@code /**} 路由就成真。
 * {@link RequestPathGuardFilter} 把这类路径在最前面就拒掉，
 * 本类锁住的就是"被拒且不落到管理控制器"。
 */
class EntryPathMatchingTest {

    static final CopyOnWriteArrayList<String> ADMIN_HIT = new CopyOnWriteArrayList<>();

    private static Tomcat   tomcat;
    private static int      port;
    private static JwtService jwtService;

    // ==================================================================
    // 探针控制器：只保留路径形状，不引入任何业务依赖
    // ==================================================================

    @Configuration
    @EnableWebMvc
    static class MvcConfig {
        @Bean
        ProbeAdminController probeAdminController() {
            return new ProbeAdminController();
        }

        @Bean
        ProbeActivityController probeActivityController() {
            return new ProbeActivityController();
        }
    }

    @RestController
    @RequestMapping("/api/admin")
    static class ProbeAdminController {
        @GetMapping("/sku/list")
        public String list() {
            ADMIN_HIT.add("ProbeAdminController.list");
            return "ADMIN_SKU_LIST";
        }
    }

    @RestController
    @RequestMapping("/api/activity")
    static class ProbeActivityController {
        @GetMapping("/list")
        public String list() {
            return "ACTIVITY_LIST";
        }

        @GetMapping("/{activityId}")
        public String detail(@PathVariable long activityId) {
            return "ACTIVITY_DETAIL:" + activityId;
        }
    }

    // ==================================================================
    // 真 Tomcat：过滤器顺序与生产的 @Order 一致
    // ==================================================================

    @BeforeAll
    static void start() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("tomcat-entry").toAbsolutePath().toString());
        tomcat.setPort(0);
        tomcat.getConnector();

        Context ctx = tomcat.addContext("",
                Files.createTempDirectory("tomcat-entry-doc").toAbsolutePath().toString());

        // 与生产的 @Order 一致：
        // TraceIdFilter(HIGHEST) → RequestPathGuardFilter(+5)
        // → JwtAuthFilter(+10) → AdminAuthFilter(+20)
        jwtService = new JwtService(new FssProperties(), new NoopTokenRevocationStore());
        addFilter(ctx, "traceId", new TraceIdFilter());
        addFilter(ctx, "pathGuard", new RequestPathGuardFilter());
        addFilter(ctx, "jwt", new JwtAuthFilter(jwtService));
        addFilter(ctx, "admin", new AdminAuthFilter());

        AnnotationConfigWebApplicationContext ac = new AnnotationConfigWebApplicationContext();
        ac.register(MvcConfig.class);
        ac.setServletContext(ctx.getServletContext());
        ac.refresh();
        Tomcat.addServlet(ctx, "dispatcher", new DispatcherServlet(ac)).setLoadOnStartup(1);
        ctx.addServletMappingDecoded("/", "dispatcher");

        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stop() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    /** Tomcat 按 filterMap 的加入顺序执行过滤器，所以这里的顺序就是执行顺序 */
    private static void addFilter(Context ctx, String name, Filter filter) {
        FilterDef fd = new FilterDef();
        fd.setFilterName(name);
        fd.setFilter(filter);
        ctx.addFilterDef(fd);
        FilterMap fm = new FilterMap();
        fm.setFilterName(name);
        fm.addURLPattern("/*");
        ctx.addFilterMap(fm);
    }

    // ==================================================================
    // ① 正常流量不受守卫影响
    // ==================================================================

    @Test
    @DisplayName("守卫不能误伤正常流量：匿名读放行、管理接口仍然拦住")
    void 正常流量不受影响() throws Exception {
        assertThat(rawGet(port, "/api/activity/list"))
                .as("匿名活动列表必须正常返回").contains("200").contains("ACTIVITY_LIST");

        assertThat(rawGet(port, "/api/activity/1001"))
                .as("匿名活动详情必须正常返回").contains("200").contains("ACTIVITY_DETAIL:1001");

        // 查询串里出现点号是合法的（版本号、价格区间），不能被"含点段"误判
        assertThat(rawGet(port, "/api/activity/1001?from=2.5"))
                .as("查询串里的点号不该被当成路径穿越").contains("200");

        ADMIN_HIT.clear();
        assertThat(rawGet(port, "/api/admin/sku/list?productId=1"))
                .as("无 token 的管理接口必须 401").contains("401");
        assertThat(ADMIN_HIT).as("无 token 时不该落到管理控制器").isEmpty();
    }

    @Test
    @DisplayName("守卫不能把有权限的管理请求一起挡掉")
    void 合法管理员仍可访问() throws Exception {
        String adminToken = jwtService.issue(1L, "admin", 1).token();
        String memberToken = jwtService.issue(2L, "member", 0).token();

        ADMIN_HIT.clear();
        assertThat(rawGet(port, "/api/admin/sku/list?productId=1", adminToken))
                .as("带管理员 token 必须放行").contains("200").contains("ADMIN_SKU_LIST");
        assertThat(ADMIN_HIT).as("管理员请求必须真的落到控制器").isNotEmpty();

        ADMIN_HIT.clear();
        assertThat(rawGet(port, "/api/admin/sku/list?productId=1", memberToken))
                .as("普通用户必须 403").contains("403");
        assertThat(ADMIN_HIT).as("普通用户不该落到管理控制器").isEmpty();
    }

    // ==================================================================
    // ② 路径穿越必须被拒，且不得落到管理控制器
    // ==================================================================

    @Test
    @DisplayName("路径穿越：必须被守卫拒绝，且绝不落到 /api/admin/ 下的控制器")
    void 路径穿越被拒绝() throws Exception {
        List<String> attacks = List.of(
                "/api/activity/../admin/sku/list?productId=1",
                "/api/activity/%2e%2e/admin/sku/list?productId=1",
                "/api/activity/..%2fadmin/sku/list?productId=1",
                "/api/activity/x/../../admin/sku/list?productId=1",
                "/api/activity/./../admin/sku/list?productId=1",
                "/api/activity/..;/admin/sku/list?productId=1",
                "/api/activity/..;/../admin/sku/list?productId=1",
                "/api/activity/%2e%2e;/admin/sku/list?productId=1");

        for (String attack : attacks) {
            ADMIN_HIT.clear();
            String response = rawGet(port, attack);

            System.out.printf("穿越 %-52s -> %s 命中管理控制器=%s%n",
                    attack, response.lines().findFirst().orElse("(空)"),
                    ADMIN_HIT.isEmpty() ? "false" : ADMIN_HIT.toString());

            // 核心不变量：只要请求有可能落到 /api/admin/** 的处理器上，
            // 就绝不能被鉴权前缀当成"匿名读"放过去。
            // 守卫的作用就是让这种情况在进入路由之前就变成 400
            assertThat(ADMIN_HIT)
                    .as("穿越路径 %s 落到了管理控制器 —— 鉴权前缀判定与实际路由不一致", attack)
                    .isEmpty();
            assertThat(response)
                    .as("穿越路径 %s 应当在进入路由前被守卫拒掉（400），而不是靠 404 碰巧躲过",
                            attack)
                    .contains("400");
        }
    }

    // ==================================================================
    // ③ preHandle 阶段能否拿到路径变量
    // ==================================================================

    /** 记录被限流器问过的 key，用来反推它解析出了什么 activityId */
    static class RecordingRateLimiter extends RateLimiter {
        final List<String> keys = new ArrayList<>();

        RecordingRateLimiter() {
            // tryAcquire 被完全覆写，两个依赖不会被触碰
            super(null, null);
        }

        @Override
        public boolean tryAcquire(String key, int capacity, int rate) {
            keys.add(key);
            return true;
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/activity/{activityId}")
        public String detail(@PathVariable long activityId) {
            return "ok:" + activityId;
        }
    }

    /**
     * {@code RateLimitInterceptor.extractActivityId} 依赖
     * {@code HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE}，并断言它"在 preHandle
     * 阶段已经可用"。这个用例把那个断言变成可执行的事实。
     *
     * <p>它决定了活动维度限流桶对 {@code /api/activity/{id}} 到底生不生效——
     * 不生效的话，活动桶只在少数带查询串的接口上起作用，
     * 而详情接口（全站 QPS 最高）实际上没有任何活动级防护。
     */
    @Test
    @DisplayName("preHandle 能拿到路径变量：/api/activity/{id} 的活动桶必须按真实 id 分桶")
    void preHandle阶段路径变量可用() throws Exception {
        RecordingRateLimiter limiter = new RecordingRateLimiter();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addInterceptors(new RateLimitInterceptor(limiter, new FssProperties()))
                .build();

        mvc.perform(get("/api/activity/1001")).andExpect(status().isOk());

        System.out.println("=== preHandle 探针 ===");
        System.out.println("限流器收到的 key: " + limiter.keys);

        assertThat(limiter.keys)
                .as("若这里只有 rate:ip:... 而没有 rate:activity:1001，"
                        + "说明 preHandle 阶段拿不到路径变量，"
                        + "extractActivityId 对 /api/activity/{id} 永远返回 null")
                .contains("rate:activity:1001");
    }

    // ==================================================================

    /**
     * 裸 socket 发请求：绕开 Java HTTP 客户端可能的路径规范化，
     * 保证线上发出去的字节就是这里写的。
     */
    private static String rawGet(int port, String rawPath) throws IOException {
        return rawGet(port, rawPath, null);
    }

    private static String rawGet(int port, String rawPath, String bearer) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            StringBuilder req = new StringBuilder()
                    .append("GET ").append(rawPath).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1\r\n")
                    .append("Connection: close\r\n");
            if (bearer != null) {
                req.append("Authorization: Bearer ").append(bearer).append("\r\n");
            }
            req.append("\r\n");
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
