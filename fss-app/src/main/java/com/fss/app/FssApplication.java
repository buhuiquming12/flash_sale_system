package com.fss.app;

import com.fss.infra.config.FssProperties;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 单 jar 三角色：{@code --spring.profiles.active=web|consumer|job}。
 *
 * <pre>
 * java -jar fss-app.jar --spring.profiles.active=web,prod      --server.port=8080
 * java -jar fss-app.jar --spring.profiles.active=consumer,prod --server.port=8090
 * java -jar fss-app.jar --spring.profiles.active=job,prod      --server.port=8099
 * 本地开发：--spring.profiles.active=web,job,dev
 * </pre>
 */
@Slf4j
@SpringBootApplication(scanBasePackages = "com.fss")
@MapperScan("com.fss.domain.mapper")
@EnableConfigurationProperties(FssProperties.class)
@EnableScheduling
public class FssApplication {

    public static void main(String[] args) {
        SpringApplication.run(FssApplication.class, args);
    }

    /**
     * 启动自检。
     *
     * <p>{@code allow-repurchase-after-cancel} 一旦被误开成 true，
     * 表现是"用户取消后重抢 → 一直创建失败 → 一直补偿"的死循环，而且只在生产的
     * 真实并发下才显现。宁可启动失败，也不让这种配置进到运行时。
     */
    @Bean
    public ApplicationListener<ContextRefreshedEvent> configSelfCheck(FssProperties props) {
        return event -> {
            if (props.getSeckill().isAllowRepurchaseAfterCancel()) {
                throw new IllegalStateException("""
                        fss.seckill.allow-repurchase-after-cancel 必须为 false。
                        t_order 的 uk_activity_sku_user 对已取消订单同样生效，
                        允许重抢会导致"创建失败 → 补偿回补 → 再抢"的死循环。
                        详见 docs/00-设计总览.md 决策 1。""");
            }
            log.info("配置自检通过: payTimeout={} closeScanDelay={}",
                    props.getOrder().getPayTimeout(), props.getOrder().getCloseScanDelay());
        };
    }
}
