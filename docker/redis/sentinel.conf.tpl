# =====================================================================
# Redis 哨兵配置<b>模板</b>——这个文件不直接被哨兵读取。
#
# docker-compose.yml 里每个哨兵服务的 command 会用 sed 把两个占位符换掉，
# 写到容器内的 /tmp/sentinel.conf 再启动：
#   __MASTER_HOST__    哨兵要监控的 master 地址（容器名 redis）
#   __ANNOUNCE_IP__    这个哨兵自己的容器名
#
# 通告地址用容器名是刻意的，理由与实测症状见 docker-compose.yml 的
# sentinel 段：哨兵对外只通告一个地址，它必须同时被哨兵自己和客户端解析到。
#
# 与 docker/rocketmq/broker.conf 的 brokerIP1 是同一套做法，理由也一样：
# 地址只在"容器网络内"和"宿主机"两种形态下不同，而写死的那一份一定会在
# 切换形态时暴露。直接改这个文件没用——启动时会被覆盖。
# =====================================================================

port 26379
dir /tmp

# master 名必须与 application-sentinel.yml 的
# spring.data.redis.sentinel.master 一致。对不上时两类客户端各自建连失败，
# 报错发生在 Redisson / Lettuce 内部，看不出是名字不一致
sentinel monitor mymaster __MASTER_HOST__ 6379 2

# 5 秒判死。默认 30 秒对演示太长——秒杀活动总共才几十分钟，
# 而"停了 master 之后要等半分钟才看到选主"会让人以为哨兵根本没工作
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 10000

# 故障转移后逐个重新同步。parallel-syncs 等于从库数时，新 master 要同时
# 接受多个全量复制，正好在最脆弱的时候被打满
sentinel parallel-syncs mymaster 1

# 把主机名原样转交给客户端，而不是换成哨兵自己解析出来的 IP。
# 换成 IP 的话宿主机上的应用会拿到一个容器内网地址，连不上——
# 而症状是"连接超时"，看不出是哨兵交出了一个不可达的地址
sentinel resolve-hostnames yes
sentinel announce-hostnames yes

# 哨兵之间互相通报的地址，用各自的容器名 + 容器内端口。通告的必须是
# "客户端也解析得到"的名字——宿主机上跑的应用解析不到容器名，
# 所以应用也必须在容器里，原因与实测症状见 docker-compose.yml 的 sentinel 段
sentinel announce-ip __ANNOUNCE_IP__
sentinel announce-port 26379
