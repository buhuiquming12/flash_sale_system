-- =====================================================================
-- flash_sale_system 建表脚本
-- MySQL 8.0 / InnoDB / utf8mb4
-- 时间列统一 DATETIME(3)，金额统一 DECIMAL(12,2)
-- 对应设计文档 docs/02-数据模型.md
--
-- 本文件只含建表语句，不含 CREATE DATABASE / USE：
--   Docker Compose 由 MYSQL_DATABASE=flash_sale 指定默认库；
--   Testcontainers 由 withDatabaseName 指定。
--   两边共用同一份 DDL，避免测试与部署的表结构漂移。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 用户
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    username     VARCHAR(64)  NOT NULL              COMMENT '登录名',
    password     VARCHAR(100) NOT NULL              COMMENT 'BCrypt 摘要',
    phone        VARCHAR(20)  DEFAULT NULL          COMMENT '手机号',
    nickname     VARCHAR(64)  DEFAULT NULL,
    role         TINYINT      NOT NULL DEFAULT 0    COMMENT '0=普通用户 1=管理员',
    status       TINYINT      NOT NULL DEFAULT 1    COMMENT '0=禁用 1=正常 2=风控冻结',
    create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- ---------------------------------------------------------------------
-- 2. 商品与 SKU
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_product (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    title        VARCHAR(200) NOT NULL,
    sub_title    VARCHAR(500) DEFAULT NULL,
    detail       TEXT         DEFAULT NULL,
    main_image   VARCHAR(500) DEFAULT NULL,
    status       TINYINT      NOT NULL DEFAULT 0    COMMENT '0=下架 1=上架',
    create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品';

CREATE TABLE IF NOT EXISTS t_sku (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    product_id    BIGINT        NOT NULL,
    spec          VARCHAR(200)  NOT NULL             COMMENT '规格描述，如 黑色/256G',
    spec_json     VARCHAR(1000) DEFAULT NULL         COMMENT '结构化规格',
    price         DECIMAL(12,2) NOT NULL             COMMENT '日常售价',
    stock         INT           NOT NULL DEFAULT 0   COMMENT '日常库存',
    status        TINYINT       NOT NULL DEFAULT 1   COMMENT '0=下架 1=上架',
    create_time   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_product (product_id),
    CONSTRAINT ck_sku_stock CHECK (stock >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU';

-- ---------------------------------------------------------------------
-- 3. 秒杀活动
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_seckill_activity (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(200) NOT NULL,
    start_time     DATETIME(3)  NOT NULL,
    end_time       DATETIME(3)  NOT NULL,
    status         TINYINT      NOT NULL DEFAULT 0
                   COMMENT '0=待发布 1=待开始 2=进行中 3=已结束 4=已关闭',
    warmup_state   TINYINT      NOT NULL DEFAULT 0
                   COMMENT '0=未预热 1=预热中 2=预热完成 3=预热失败',
    warmup_version INT          NOT NULL DEFAULT 0   COMMENT '预热版本，每次预热+1',
    warmup_time    DATETIME(3)  DEFAULT NULL,
    creator_id     BIGINT       NOT NULL,
    create_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_status_start (status, start_time),
    KEY idx_start_end (start_time, end_time),
    CONSTRAINT ck_activity_time CHECK (end_time > start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动';

-- ---------------------------------------------------------------------
-- 4. 秒杀商品（核心热点表）
--    主要扣减手段是条件更新 WHERE available_stock >= qty，
--    下面的 CHECK 只是最后一道防线。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_seckill_goods (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    activity_id      BIGINT        NOT NULL,
    sku_id           BIGINT        NOT NULL,
    seckill_price    DECIMAL(12,2) NOT NULL           COMMENT '秒杀价，服务端唯一价格来源',
    total_stock      INT           NOT NULL           COMMENT '活动总库存',
    available_stock  INT           NOT NULL           COMMENT '可售库存，扣减目标',
    locked_stock     INT           NOT NULL DEFAULT 0 COMMENT '已下单未支付占用（统计用）',
    sold_stock       INT           NOT NULL DEFAULT 0 COMMENT '已成交',
    released_stock   INT           NOT NULL DEFAULT 0 COMMENT '累计回补，用于对账',
    limit_per_user   INT           NOT NULL DEFAULT 1 COMMENT '每人限购',
    status           TINYINT       NOT NULL DEFAULT 1 COMMENT '0=停售 1=在售 2=售罄',
    version          INT           NOT NULL DEFAULT 0 COMMENT '预留乐观锁，主链路不用',
    create_time      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_sku (activity_id, sku_id),
    KEY idx_sku (sku_id),
    CONSTRAINT ck_goods_stock CHECK (available_stock >= 0),
    CONSTRAINT ck_goods_total CHECK (total_stock >= 0),
    CONSTRAINT ck_goods_price CHECK (seckill_price >= 0),
    CONSTRAINT ck_goods_limit CHECK (limit_per_user >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀商品';

-- ---------------------------------------------------------------------
-- 5. 订单
--    三个唯一键各拦一类异常：
--      uk_request_no        —— MQ 重复投递、消费端重试
--      uk_activity_sku_user —— Redis 数据丢失后重抢、不同 requestNo 并发
--      uk_order_no          —— 订单号碰撞兜底
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_order (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    order_no        VARCHAR(40)   NOT NULL           COMMENT '业务订单号',
    request_no      VARCHAR(40)   NOT NULL           COMMENT '秒杀请求号，幂等键',
    user_id         BIGINT        NOT NULL,
    activity_id     BIGINT        NOT NULL,
    sku_id          BIGINT        NOT NULL,
    status          TINYINT       NOT NULL DEFAULT 0
                    COMMENT '0=待支付 1=已支付 2=已取消 3=已完成 4=退款中 5=已退款',
    total_amount    DECIMAL(12,2) NOT NULL           COMMENT '原价合计',
    pay_amount      DECIMAL(12,2) NOT NULL           COMMENT '应付金额',
    quantity        INT           NOT NULL DEFAULT 1,
    stock_released  TINYINT       NOT NULL DEFAULT 0 COMMENT '0=未释放 1=已释放',
    expire_time     DATETIME(3)   NOT NULL           COMMENT '支付截止时间',
    pay_time        DATETIME(3)   DEFAULT NULL,
    cancel_time     DATETIME(3)   DEFAULT NULL,
    finish_time     DATETIME(3)   DEFAULT NULL,
    cancel_reason   VARCHAR(200)  DEFAULT NULL,
    create_time     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_request_no (request_no),
    UNIQUE KEY uk_activity_sku_user (activity_id, sku_id, user_id),
    KEY idx_user_create (user_id, create_time DESC),
    KEY idx_status_expire (status, expire_time),
    CONSTRAINT ck_order_amount CHECK (pay_amount >= 0 AND total_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单';

-- ---------------------------------------------------------------------
-- 6. 订单明细（全部字段是快照）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_order_item (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    order_no       VARCHAR(40)   NOT NULL,
    sku_id         BIGINT        NOT NULL,
    product_id     BIGINT        NOT NULL,
    product_title  VARCHAR(200)  NOT NULL           COMMENT '快照',
    spec_snapshot  VARCHAR(200)  NOT NULL           COMMENT '快照',
    image_snapshot VARCHAR(500)  DEFAULT NULL       COMMENT '快照',
    unit_price     DECIMAL(12,2) NOT NULL           COMMENT '成交单价快照',
    quantity       INT           NOT NULL,
    create_time    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

-- ---------------------------------------------------------------------
-- 7. 支付流水
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_payment (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    pay_no        VARCHAR(40)   NOT NULL           COMMENT '内部支付流水号',
    out_trade_no  VARCHAR(64)   DEFAULT NULL       COMMENT '外部渠道流水号',
    order_no      VARCHAR(40)   NOT NULL,
    user_id       BIGINT        NOT NULL,
    amount        DECIMAL(12,2) NOT NULL,
    channel       TINYINT       NOT NULL DEFAULT 0 COMMENT '0=模拟 1=支付宝 2=微信',
    status        TINYINT       NOT NULL DEFAULT 0 COMMENT '0=待支付 1=成功 2=失败 3=已退款',
    notify_body   TEXT          DEFAULT NULL       COMMENT '回调原文，审计用',
    create_time   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finish_time   DATETIME(3)   DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_no (pay_no),
    UNIQUE KEY uk_out_trade_no (out_trade_no),
    KEY idx_order_no (order_no),
    KEY idx_status_create (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付流水';

-- ---------------------------------------------------------------------
-- 8. 秒杀请求记录
--    异步化后（阶段三）本表不在主链路同步写，由消费端写入。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_seckill_request (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    request_no   VARCHAR(40)  NOT NULL,
    user_id      BIGINT       NOT NULL,
    activity_id  BIGINT       NOT NULL,
    sku_id       BIGINT       NOT NULL,
    quantity     INT          NOT NULL DEFAULT 1,
    status       TINYINT      NOT NULL DEFAULT 0
                 COMMENT '0=排队中 1=成功 2=库存不足 3=重复购买 4=创建失败 5=已补偿',
    order_no     VARCHAR(40)  DEFAULT NULL,
    fail_reason  VARCHAR(200) DEFAULT NULL,
    trace_id     VARCHAR(64)  DEFAULT NULL,
    create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_no (request_no),
    KEY idx_status_create (status, create_time),
    KEY idx_user_activity (user_id, activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀请求记录';

-- ---------------------------------------------------------------------
-- 9. 本地消息表（决策 5）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_mq_message (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    msg_id        VARCHAR(40)  NOT NULL           COMMENT '业务消息号',
    topic         VARCHAR(100) NOT NULL,
    tags          VARCHAR(64)  DEFAULT NULL,
    biz_key       VARCHAR(64)  NOT NULL           COMMENT 'RocketMQ key，通常=request_no',
    body          TEXT         NOT NULL           COMMENT 'JSON 消息体',
    deliver_time  DATETIME(3)  DEFAULT NULL       COMMENT '定时投递时刻，null=即时',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待发送 1=已发送 2=已消费 3=发送失败',
    send_count    INT          NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3)  DEFAULT NULL,
    last_error    VARCHAR(500) DEFAULT NULL,
    create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id),
    KEY idx_status_retry (status, next_retry_at),
    KEY idx_status_update (status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';

-- ---------------------------------------------------------------------
-- 10. 库存流水
--     uk_biz_type (biz_no, change_type) 是库存幂等的关键。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_stock_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    biz_no       VARCHAR(40)  NOT NULL           COMMENT '业务号：request_no 或 order_no',
    activity_id  BIGINT       NOT NULL,
    sku_id       BIGINT       NOT NULL,
    change_type  TINYINT      NOT NULL
                 COMMENT '1=预扣 2=确认扣减 3=取消回补 4=补偿回补 5=管理调整',
    quantity     INT          NOT NULL           COMMENT '正=增加 负=减少',
    before_stock INT          DEFAULT NULL,
    after_stock  INT          DEFAULT NULL,
    operator     VARCHAR(64)  DEFAULT NULL,
    remark       VARCHAR(200) DEFAULT NULL,
    create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_type (biz_no, change_type),
    KEY idx_activity_sku (activity_id, sku_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存流水';

-- ---------------------------------------------------------------------
-- 11. 对账任务
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_reconcile_task (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    task_type     TINYINT      NOT NULL COMMENT '1=资格对账 2=库存对账 3=支付对账 4=订单对账',
    biz_no        VARCHAR(64)  DEFAULT NULL,
    activity_id   BIGINT       DEFAULT NULL,
    sku_id        BIGINT       DEFAULT NULL,
    detail        TEXT         DEFAULT NULL COMMENT 'JSON 差异明细',
    status        TINYINT      NOT NULL DEFAULT 0
                  COMMENT '0=待处理 1=自动修复成功 2=需人工 3=人工已处理 4=忽略',
    handle_result VARCHAR(500) DEFAULT NULL,
    handler       VARCHAR(64)  DEFAULT NULL,
    create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_type_status (task_type, status),
    KEY idx_biz_no (biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账任务';

-- ---------------------------------------------------------------------
-- 12. 管理操作审计
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_admin_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    admin_id    BIGINT       NOT NULL,
    action      VARCHAR(64)  NOT NULL COMMENT '如 ACTIVITY_PUBLISH / STOCK_ADJUST',
    target_type VARCHAR(32)  DEFAULT NULL,
    target_id   VARCHAR(64)  DEFAULT NULL,
    before_val  TEXT         DEFAULT NULL,
    after_val   TEXT         DEFAULT NULL,
    ip          VARCHAR(64)  DEFAULT NULL,
    create_time DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_admin_time (admin_id, create_time),
    KEY idx_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理操作审计';
