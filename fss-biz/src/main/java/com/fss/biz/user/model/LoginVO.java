package com.fss.biz.user.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginVO {

    private String token;
    /** 秒 */
    private long   expiresIn;
    private Long   userId;
    private String username;
    private Integer role;
}
