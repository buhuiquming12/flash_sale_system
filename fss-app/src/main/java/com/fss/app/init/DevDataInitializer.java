package com.fss.app.init;

import com.fss.biz.activity.model.ActivityCreateCmd;
import com.fss.biz.activity.model.SeckillGoodsCmd;
import com.fss.biz.activity.service.ActivityService;
import com.fss.biz.activity.service.WarmupService;
import com.fss.biz.product.model.ProductCreateCmd;
import com.fss.biz.product.model.SkuCreateCmd;
import com.fss.biz.product.service.ProductService;
import com.fss.biz.user.model.RegisterCmd;
import com.fss.biz.user.service.UserService;
import com.fss.common.enums.ActivityStatus;
import com.fss.domain.entity.User;
import com.fss.domain.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 演示数据初始化。
 *
 * <p>只在 {@code dev} profile 下运行，且仅当用户表为空时执行一次。
 *
 * <p><b>为什么不用 SQL 种子脚本</b>：管理员密码需要 BCrypt 摘要，写死在 SQL 里
 * 意味着所有人的演示环境共用同一个已公开的哈希；活动时间需要"相对当前时间"，
 * 静态 SQL 表达不了。走 Service 还能顺带验证注册、发布校验这两条路径确实能跑通。
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevDataInitializer implements ApplicationRunner {

    private static final String ADMIN_USERNAME = "admin";
    private static final String DEMO_PASSWORD  = "Passw0rd1";

    private final UserMapper      userMapper;
    private final UserService     userService;
    private final ProductService  productService;
    private final ActivityService activityService;
    private final WarmupService   warmupService;
    private final JdbcTemplate    jdbc;

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        if (userMapper.selectByUsername(ADMIN_USERNAME) != null) {
            log.info("演示数据已存在，跳过初始化");
            return;
        }

        long adminId = register(ADMIN_USERNAME, "管理员");
        // register 只能创建普通用户，管理员权限单独提升，避免注册接口成为提权入口
        jdbc.update("UPDATE t_user SET role = 1 WHERE id = ?", adminId);

        for (int i = 1; i <= 3; i++) {
            register("demo" + i, "演示用户" + i);
        }

        long activityId = createDemoActivity(adminId);
        log.info("""

                ========================= 演示数据就绪 =========================
                管理员    : {} / {}
                普通用户  : demo1 / demo2 / demo3  密码同上
                秒杀活动  : activityId={}，1 分钟后开抢，库存 100，每人限 1 件
                Swagger  : http://localhost:8080/swagger-ui.html
                ==============================================================
                """, ADMIN_USERNAME, DEMO_PASSWORD, activityId);
    }

    private long register(String username, String nickname) {
        RegisterCmd cmd = new RegisterCmd();
        cmd.setUsername(username);
        cmd.setPassword(DEMO_PASSWORD);
        cmd.setNickname(nickname);
        return userService.register(cmd);
    }

    private long createDemoActivity(long adminId) {
        ProductCreateCmd p = new ProductCreateCmd();
        p.setTitle("iPhone 16 Pro");
        p.setSubTitle("秒杀专场特价");
        p.setMainImage("https://placehold.co/300x300?text=iPhone+16+Pro");
        p.setStatus(1);
        long productId = productService.createProduct(p, adminId);

        SkuCreateCmd s = new SkuCreateCmd();
        s.setProductId(productId);
        s.setSpec("黑色/256G");
        s.setPrice(new BigDecimal("7999.00"));
        s.setStock(1000);
        s.setStatus(1);
        long skuId = productService.createSku(s, adminId);

        SeckillGoodsCmd g = new SeckillGoodsCmd();
        g.setSkuId(skuId);
        g.setSeckillPrice(new BigDecimal("4999.00"));
        g.setTotalStock(100);
        g.setLimitPerUser(1);

        ActivityCreateCmd a = new ActivityCreateCmd();
        a.setName("iPhone 秒杀专场");
        // 发布校验要求开始时间晚于当前时间；给 1 分钟，方便演示倒计时与"未开始"拦截
        a.setStartTime(LocalDateTime.now().plusMinutes(1));
        a.setEndTime(LocalDateTime.now().plusHours(2));
        a.setGoods(List.of(g));

        long activityId = activityService.create(a, adminId);
        activityService.publish(activityId, adminId);

        // 立即预热一次。定时任务（job profile）也会在开抢前 5 分钟自动预热，
        // 这里主动调一次是为了让"只起 web profile"的部署也能直接演示 ——
        // 没有预热，Lua 读不到 seckill:goods，所有请求都返回 2005 未预热
        warmupService.warmupOne(activityId);

        log.info("演示活动已发布并预热 activityId={} skuId={} status={}",
                activityId, skuId, ActivityStatus.READY.getDesc());
        return activityId;
    }
}
