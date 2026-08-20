package com.fss.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    /** BCrypt 摘要，绝不出现在任何响应体或日志中 */
    private String password;
    private String phone;
    private String nickname;
    /** 0=普通用户 1=管理员 */
    private Integer role;
    /** 0=禁用 1=正常 2=风控冻结 */
    private Integer status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public boolean isAdmin() {
        return role != null && role == 1;
    }

    public boolean isEnabled() {
        return status != null && status == 1;
    }
}
