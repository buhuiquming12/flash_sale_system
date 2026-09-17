#!/bin/sh
# =====================================================================
# 创建阶段三需要的四个 Topic。
#
# broker.conf 里 autoCreateTopicEnable = false，所以必须显式创建——
# 开着自动创建的话，Topic 会用默认的 8 个队列，且<b>一个拼错的 Topic 名
# 也会被静默创建</b>，症状是消息发出去了但没有任何消费者，
# 而两边都不报错。
#
# 队列数：FSS_ORDER_CREATE 设 16（消费端 2 实例 × 12 线程，
# 队列数需 ≥ 实例数才能均衡，且要能被实例数整除）。其余 4 个够用。
#
# ---------------------------------------------------------------------
# 为什么这个脚本要自己重试并<b>回读校验</b>：
#
# mqadmin updateTopic 在失败时<b>也返回退出码 0</b>。broker 还没注册到
# namesrv 时它打一行
#   [error] Make sure the specified clusterName exists ...
# 然后正常退出。所以 `set -e` 和 `cmd || retry` 都拦不住它——
# 实测过一次：前三个 Topic 全部失败、第四个成功，脚本退出码 0，
# compose 认为初始化完成、应用照常启动，然后所有秒杀都停在"排队中"，
# 应用日志里只有 `No route info of this topic: FSS_ORDER_CREATE`。
#
# 唯一可靠的判据是回读 topicList 确认四个都在。
# ---------------------------------------------------------------------
#
# 用法：正常情况下不用手动跑——docker-compose.yml 里的 rocketmq-init
# 一次性服务会执行它，应用用 depends_on: service_completed_successfully
# 等它跑完。需要手动补建时（比如 down 之后只重建了 broker）：
#   docker exec fss-rmq-init sh /init-topics.sh
# Git Bash 下要加 MSYS_NO_PATHCONV=1，否则 /init-topics.sh 会被
# 改写成 D:/git/Git/init-topics.sh 而报 "cannot open"。
# =====================================================================
set -e

NAMESRV="${NAMESRV:-rocketmq-namesrv:9876;rocketmq-namesrv-b:9876}"
CLUSTER="${CLUSTER:-DefaultCluster}"
ACCESS_KEY="${ACCESS_KEY:-FssAdminAccessKey}"
SECRET_KEY="${SECRET_KEY:-FssAdminSecretKeyChangeMeNow}"
MQADMIN="${ROCKETMQ_HOME:-/home/rocketmq/rocketmq-5.3.0}/bin/mqadmin"

# mqadmin 默认也要 4g 堆
export JAVA_OPT_EXT="${JAVA_OPT_EXT:--Xms64m -Xmx256m -Xmn64m}"

# "名字:队列数"
TOPICS="FSS_ORDER_CREATE:16 FSS_ORDER_CLOSE:4 FSS_STOCK_RELEASE:4 FSS_STOCK_ROLLBACK:4"

WAIT_ROUNDS="${WAIT_ROUNDS:-60}"
CREATE_ROUNDS="${CREATE_ROUNDS:-10}"

topic_list() {
    sh "$MQADMIN" topicList -n "$NAMESRV" -a "$ACCESS_KEY" -s "$SECRET_KEY" 2>&1 || true
}

# broker 向 namesrv 注册要几秒到几十秒。clusterList 的数据行以集群名开头，
# 表头是 "#Cluster Name"，所以用 "^名字+空白" 判定就能区分
wait_broker() {
    i=0
    while [ "$i" -lt "$WAIT_ROUNDS" ]; do
        cluster=$(sh "$MQADMIN" clusterList -n "$NAMESRV" -a "$ACCESS_KEY" -s "$SECRET_KEY" 2>&1 || true)
        a_count=$(echo "$cluster" | awk '$2 == "broker-a" {n++} END {print n+0}')
        b_count=$(echo "$cluster" | awk '$2 == "broker-b" {n++} END {print n+0}')
        if [ "$a_count" -ge 2 ] && [ "$b_count" -ge 2 ]; then
            echo "==> broker-a/b 的 Master+Slave 已注册到双 namesrv"
            return 0
        fi
        i=$((i + 1))
        echo "==> 等 broker 注册到 namesrv（$i/$WAIT_ROUNDS）"
        sleep 2
    done
    echo "[fatal] broker 在 $((WAIT_ROUNDS * 2))s 内没有注册到 namesrv"
    return 1
}

create() {
    echo "==> $1 (queues=$2)"
    # 退出码不可信，见文件头。失败与否一律靠后面的 verify 判定
    sh "$MQADMIN" updateTopic -n "$NAMESRV" -c "$CLUSTER" -t "$1" -r "$2" -w "$2" \
        -a "$ACCESS_KEY" -s "$SECRET_KEY" || true
}

# timerWheelEnable=false 时定时消息会静默立即投递，必须在应用启动前失败。
verify_timer_wheel() {
    cluster=$(sh "$MQADMIN" clusterList -n "$NAMESRV" -a "$ACCESS_KEY" -s "$SECRET_KEY" 2>&1)
    for broker in broker-a broker-b; do
        addr=$(echo "$cluster" | awk -v b="$broker" '$2 == b && $3 == 0 {print $4; exit}')
        if [ -z "$addr" ] || ! sh "$MQADMIN" getBrokerConfig -n "$NAMESRV" -b "$addr" \
                -a "$ACCESS_KEY" -s "$SECRET_KEY" 2>&1 \
                | grep -qE '^timerWheelEnable[[:space:]]*=[[:space:]]*true'; then
            echo "[fatal] $broker timerWheelEnable 未开启，拒绝启动应用"
            return 1
        fi
    done
}

# 回读校验。必须锚定整行：不锚的话 %RETRY%GID_FSS_ORDER_CREATE
# 也会被当成 FSS_ORDER_CREATE 存在的证据
verify() {
    listed=$(topic_list)
    missing=""
    for spec in $TOPICS; do
        name=${spec%%:*}
        echo "$listed" | grep -qE "^[[:space:]]*${name}[[:space:]]*$" \
            || missing="$missing $name"
    done
    if [ -n "$missing" ]; then
        echo "==> 还缺:$missing"
        return 1
    fi
    return 0
}

wait_broker
verify_timer_wheel

round=0
while [ "$round" -lt "$CREATE_ROUNDS" ]; do
    round=$((round + 1))
    for spec in $TOPICS; do
        create "${spec%%:*}" "${spec##*:}"
    done
    if verify; then
        echo "==> 四个 Topic 都已就绪："
        topic_list | grep '^FSS_' || true
        exit 0
    fi
    echo "==> 第 $round/$CREATE_ROUNDS 轮没建齐，2s 后重来"
    sleep 2
done

echo "[fatal] 建 Topic 失败：$CREATE_ROUNDS 轮之后仍未建齐"
exit 1
