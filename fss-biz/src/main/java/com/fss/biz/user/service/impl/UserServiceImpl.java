package com.fss.biz.user.service.impl;

import com.fss.biz.user.core.JwtService;
import com.fss.biz.user.model.LoginCmd;
import com.fss.biz.user.model.LoginVO;
import com.fss.biz.user.model.RegisterCmd;
import com.fss.biz.user.model.UserVO;
import com.fss.biz.user.service.UserService;
import com.fss.common.error.BizException;
import com.fss.common.error.ErrorCode;
import com.fss.domain.entity.User;
import com.fss.domain.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtService jwtService;

    /** BCrypt 强度 10：单次校验约 50~100ms，足以让离线爆破不可行，又不至于拖慢登录 */
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(10);

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long register(RegisterCmd cmd) {
        User u = User.builder()
                .username(cmd.getUsername())
                .password(encoder.encode(cmd.getPassword()))
                .phone(cmd.getPhone())
                .nickname(cmd.getNickname() == null ? cmd.getUsername() : cmd.getNickname())
                .role(0)
                .status(1)
                .build();
        try {
            userMapper.insert(u);
        } catch (DuplicateKeyException e) {
            // 靠唯一键判重而非先查后插：后者在并发注册下两个请求都能查到"不存在"
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("uk_phone")) {
                throw new BizException(ErrorCode.PARAM_INVALID, "该手机号已注册");
            }
            throw new BizException(ErrorCode.PARAM_INVALID, "用户名已存在");
        }
        return u.getId();
    }

    @Override
    public LoginVO login(LoginCmd cmd) {
        User u = userMapper.selectByUsername(cmd.getUsername());

        // 用户不存在时也走一次 BCrypt 校验，让两种失败的耗时接近，
        // 避免通过响应时间差探测用户名是否存在
        boolean ok = u != null && encoder.matches(cmd.getPassword(), u.getPassword());
        if (u == null) {
            encoder.matches(cmd.getPassword(), DUMMY_HASH);
        }
        if (!ok) {
            // 不区分"用户不存在"与"密码错误"
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!u.isEnabled()) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号不可用，请联系管理员");
        }

        JwtService.Issued issued = jwtService.issue(u.getId(), u.getUsername(), u.getRole());
        log.info("stage=LOGIN userId={} username={}", u.getId(), u.getUsername());
        return new LoginVO(issued.token(), issued.expiresIn(),
                u.getId(), u.getUsername(), u.getRole());
    }

    @Override
    public UserVO me(long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return new UserVO(u.getId(), u.getUsername(), u.getNickname(), u.getRole(), u.getStatus());
    }

    @Override
    public void assertUsable(long userId) {
        User u = userMapper.selectById(userId);
        if (u == null || !u.isEnabled()) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号不可用");
        }
    }

    /** 一个固定的合法 BCrypt 摘要，仅用于制造等时开销，不对应任何真实密码 */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
}
