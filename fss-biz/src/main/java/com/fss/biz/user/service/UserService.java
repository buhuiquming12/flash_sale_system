package com.fss.biz.user.service;

import com.fss.biz.user.model.LoginCmd;
import com.fss.biz.user.model.LoginVO;
import com.fss.biz.user.model.RegisterCmd;
import com.fss.biz.user.model.UserVO;

public interface UserService {

    long register(RegisterCmd cmd);

    LoginVO login(LoginCmd cmd);

    UserVO me(long userId);

    /** 供其它业务包校验用户可用性，禁止跨包直接使用 UserMapper */
    void assertUsable(long userId);
}
