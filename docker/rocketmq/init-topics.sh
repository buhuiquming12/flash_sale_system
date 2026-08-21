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
# 用法（容器起来之后）：
#   docker exec fss-rmq-broker sh /home/rocketmq/init-topics.sh
# 或从宿主机：
#   docker compose --profile phase3 exec rocketmq-broker \
#       sh -c "$(cat docker/rocketmq/init-topics.sh)"
# =====================================================================
set -e

NAMESRV="${NAMESRV:-rocketmq-namesrv:9876}"
CLUSTER="${CLUSTER:-DefaultCluster}"
MQADMIN="${ROCKETMQ_HOME:-/home/rocketmq/rocketmq-5.3.0}/bin/mqadmin"

# mqadmin 默认也要 4g 堆
export JAVA_OPT_EXT="-Xms64m -Xmx256m -Xmn64m"

create() {
    echo "==> $1 (queues=$2)"
    sh "$MQADMIN" updateTopic -n "$NAMESRV" -c "$CLUSTER" -t "$1" -r "$2" -w "$2"
}

create FSS_ORDER_CREATE   16
create FSS_ORDER_CLOSE     4
create FSS_STOCK_RELEASE   4
create FSS_STOCK_ROLLBACK  4

echo "==> 已创建的 Topic："
sh "$MQADMIN" topicList -n "$NAMESRV" | grep FSS_ || true
