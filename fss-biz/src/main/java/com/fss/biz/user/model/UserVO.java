package com.fss.biz.user.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 当前用户信息。不含密码、手机号等敏感字段。 */
@Data
@AllArgsConstructor
public class UserVO {

    private Long    userId;
    private String  username;
    private String  nickname;
    private Integer role;
    private Integer status;
}
