package com.fss.biz.user.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterCmd {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度需 3~32 位")
    @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "用户名只允许字母、数字、下划线")
    private String username;

    /** 密码策略：8~32 位，含大小写与数字 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度需 8~32 位")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
             message = "密码必须同时包含大写字母、小写字母和数字")
    private String password;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Size(max = 32, message = "昵称最长 32 位")
    private String nickname;
}
