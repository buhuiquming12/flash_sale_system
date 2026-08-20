package com.fss.app.controller;

import com.fss.biz.user.core.JwtService;
import com.fss.biz.user.model.LoginCmd;
import com.fss.biz.user.model.LoginVO;
import com.fss.biz.user.model.RegisterCmd;
import com.fss.biz.user.model.UserVO;
import com.fss.biz.user.service.UserService;
import com.fss.common.context.UserContext;
import com.fss.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Profile("web")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private static final String BEARER = "Bearer ";

    private final UserService userService;
    private final JwtService  jwtService;

    @PostMapping("/register")
    public R<Map<String, Long>> register(@Valid @RequestBody RegisterCmd cmd) {
        return R.ok(Map.of("userId", userService.register(cmd)));
    }

    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginCmd cmd) {
        return R.ok(userService.login(cmd));
    }

    @GetMapping("/me")
    public R<UserVO> me() {
        return R.ok(userService.me(UserContext.userId()));
    }

    /**
     * 登出：服务端撤销这一个会话。
     *
     * <p>阶段一没有这个接口，因为没有 Redis 时它做不到任何事——JWT 是无状态的，
     * 签发后在过期前天然无法撤销，"登出"只能靠客户端自己丢掉 token。
     * 提供一个什么都不做的登出接口比不提供更糟：调用方会以为会话真的结束了。
     *
     * <p>阶段二有了 {@code jwt:active:{jti}} 白名单，删掉这个 key 即刻生效。
     *
     * <p>{@code jti} 从请求头里的 token 重新解析而不是塞进 {@code UserContext}：
     * 登出是极低频操作，为它在所有请求的上下文里多带一个字段不值得。
     */
    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(name = "Authorization", required = false) String header) {
        if (header != null && header.startsWith(BEARER)) {
            jwtService.revoke(jwtService.verify(header.substring(BEARER.length()).trim()).jti());
        }
        return R.ok();
    }
}
