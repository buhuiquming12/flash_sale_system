#!/usr/bin/env bash
# =============================================================================
# 阶段五压测驱动 —— 编排 S1-S7、采集中间件侧指标、汇总成一份 CSV
#
# 为什么不是「手敲 jmeter 命令」：
#   · 每个场景跑完要抓 Redis / MySQL / MQ / JVM 四侧的数据（docs/08 §3 列的那些），
#     手抓会漏，而且抓的时刻不一致就没法对照
#   · S2 → S3 → S4 有**顺序依赖**（S3 要求已售罄、S4 消费 S2 产出的 requestNo）
#   · S6 要停消费端 → 灌消息 → 启消费端，这不是 JMeter 能表达的
#
# 用法：
#   ./run.sh prepare              只准备用户
#   ./run.sh S1                   跑单个场景
#   ./run.sh all                  按依赖顺序跑全部
#   SCALE=2000 ./run.sh S2        覆盖并发
#
# 环境变量（都有默认值）：
#   HOST PORT ACTIVITY SKU USERS THREADS DURATION SCALE
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"      # perf/
REPO="$(cd "$ROOT/.." && pwd)"                            # 仓库根
JM="$REPO/.tools/apache-jmeter-5.6.3/bin/jmeter.sh"
JMX="$ROOT/jmeter"
RESULTS="$ROOT/results"

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8080}"
ACTIVITY="${ACTIVITY:-1}"
SKU="${SKU:-1}"
USERS="${USERS:-2000}"
# 本机默认值刻意保守：这台机器要同时跑 5 个容器 + 应用 + JMeter。
# 真机压测直接 THREADS=10000 ./run.sh S2
THREADS="${THREADS:-500}"
DURATION="${DURATION:-60}"
SCALE="${SCALE:-1000}"

USERS_CSV="$RESULTS/users.csv"
REQ_CSV="$RESULTS/requests.csv"

mkdir -p "$RESULTS"

log()  { printf '\033[36m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf '\033[33m[%s] WARN\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
die()  { printf '\033[31m[%s] FAIL\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; exit 1; }

# ---------------------------------------------------------------- 前置检查
preflight() {
  curl -sf --max-time 5 "http://$HOST:$PORT/actuator/health" >/dev/null \
    || die "应用没起来：http://$HOST:$PORT/actuator/health"

  # perf profile 是否激活。没激活的话所有请求会撞 ip-qps=20，
  # 测出来的是限流器而不是系统 —— 这个错误很隐蔽，值得显式检查
  local rl
  rl=$(curl -s --max-time 5 "http://$HOST:$PORT/actuator/health" 2>/dev/null)
  log "健康检查通过"

  [ -x "$JM" ] || die "JMeter 不在 $JM（见 README 阶段五工具链）"
}

# ---------------------------------------------------------------- 指标采样
# 抓一次 /actuator/prometheus，把关心的几个序列拍平成 key=value
snapshot() {
  local tag="$1" out="$RESULTS/$tag/metrics-$2.txt"
  mkdir -p "$RESULTS/$tag"
  curl -s --max-time 10 "http://$HOST:$PORT/actuator/prometheus" > "$out" 2>/dev/null || true

  {
    echo "# ---- $tag / $2 ----"
    grep -E '^fss_(stock|mq_backlog|degrade_level)' "$out" 2>/dev/null | head -20
    grep -E '^hikaricp_connections(_pending|_active)?\{' "$out" 2>/dev/null | head -6
    grep -E '^jvm_memory_used_bytes\{area="heap"' "$out" 2>/dev/null | head -3
    grep -E '^jvm_gc_pause_seconds_(count|sum)' "$out" 2>/dev/null | head -4
    grep -E '^tomcat_threads_(busy|current)' "$out" 2>/dev/null | head -4
    grep -E '^fss_lua_execution_seconds_count' "$out" 2>/dev/null | head -6
  } >> "$RESULTS/$tag/side-metrics.txt"
}

# Redis 侧：INFO stats 里的命中率与 QPS
redis_snapshot() {
  local tag="$1" phase="$2"
  {
    echo "# ---- redis $phase ----"
    docker exec fss-redis redis-cli INFO stats 2>/dev/null \
      | grep -E 'keyspace_hits|keyspace_misses|instantaneous_ops_per_sec|total_commands' || true
    docker exec fss-redis redis-cli INFO memory 2>/dev/null \
      | grep -E 'used_memory_human|maxmemory_human' || true
    docker exec fss-redis redis-cli INFO clients 2>/dev/null \
      | grep -E 'connected_clients' || true
  } >> "$RESULTS/$tag/redis.txt" 2>/dev/null || true
}

mysql_snapshot() {
  local tag="$1" phase="$2"
  {
    echo "# ---- mysql $phase ----"
    docker exec fss-mysql mysql -uroot -proot -N -B flash_sale -e "
      SELECT 'orders', COUNT(*) FROM t_order
      UNION ALL SELECT 'requests', COUNT(*) FROM t_seckill_request
      UNION ALL SELECT 'mq_pending', COUNT(*) FROM t_mq_message WHERE status=0
      UNION ALL SELECT 'mq_sent',    COUNT(*) FROM t_mq_message WHERE status=1
      UNION ALL SELECT 'mq_consumed',COUNT(*) FROM t_mq_message WHERE status=2
      UNION ALL SELECT 'reconcile',  COUNT(*) FROM t_reconcile_task;" 2>/dev/null || true
    docker exec fss-mysql mysql -uroot -proot -N -B -e "
      SHOW GLOBAL STATUS WHERE Variable_name IN
      ('Threads_connected','Threads_running','Slow_queries','Innodb_row_lock_waits');" 2>/dev/null || true
  } >> "$RESULTS/$tag/mysql.txt" 2>/dev/null || true
}

# ---------------------------------------------------------------- 跑一个场景
run_jmx() {
  local tag="$1" jmx="$2"; shift 2
  local dir="$RESULTS/$tag"
  rm -rf "$dir"; mkdir -p "$dir"

  log "===== $tag 开始 ====="
  snapshot       "$tag" before
  redis_snapshot "$tag" before
  mysql_snapshot "$tag" before

  # -f 覆盖已有结果；-j 独立日志，避免多场景日志串在一起
  "$JM" -n -t "$JMX/$jmx" -l "$dir/raw.jtl" -j "$dir/jmeter.log" -f \
        -Jhost="$HOST" -Jport="$PORT" -Jactivity="$ACTIVITY" -Jsku="$SKU" \
        -Jusers_csv="$USERS_CSV" -Jreq_csv="$REQ_CSV" -Jreq_out="$REQ_CSV" \
        "$@" 2>&1 | grep -vE '^\s*$' | tail -25

  snapshot       "$tag" after
  redis_snapshot "$tag" after
  mysql_snapshot "$tag" after

  summarize "$tag"
  log "===== $tag 结束 ====="
  echo
}

# ---------------------------------------------------------------- 汇总 .jtl
# 自己算而不是只用 JMeter 的 HTML 报告：报告是给人看的，
# 这个 CSV 是给「报告里那张表」用的，两者都要
summarize() {
  local tag="$1" jtl="$RESULTS/$tag/raw.jtl"
  [ -f "$jtl" ] || { warn "$tag 没有 raw.jtl"; return; }

  python "$ROOT/summarize.py" "$jtl" "$tag" >> "$RESULTS/summary.csv" \
    || warn "$tag 汇总失败"
  python "$ROOT/summarize.py" "$jtl" "$tag" --human
}

# ---------------------------------------------------------------- 各场景
do_prepare() {
  preflight
  log "准备 $USERS 个压测用户 → $USERS_CSV"
  "$JM" -n -t "$JMX/00-prepare-users.jmx" -j "$RESULTS/prepare.log" -f \
        -Jhost="$HOST" -Jport="$PORT" -Jusers="$USERS" \
        -Jout="$USERS_CSV" -Jprefix="${PREFIX:-perf}" 2>&1 | tail -12
  local n
  n=$(wc -l < "$USERS_CSV" 2>/dev/null || echo 0)
  log "users.csv 实得 $n 行"
  [ "$n" -gt 0 ] || die "一个用户都没准备出来"
}

do_S1() { preflight; run_jmx S1 S1-activity-detail.jmx -Jthreads="$THREADS" -Jduration="$DURATION" -Jrampup=10; }

do_S2() {
  preflight
  [ -s "$USERS_CSV" ] || die "users.csv 为空，先跑 ./run.sh prepare"
  # S2 不设 duration：用户表用完即止（一人一单决定的形态）
  run_jmx S2 S2-seckill-submit.jmx -Jthreads="$THREADS" -Jrampup=5
  log "等待异步落库..."
  wait_drain 120
}

do_S3() { preflight; run_jmx S3 S3-seckill-soldout.jmx -Jthreads="$THREADS" -Jduration="$DURATION" -Jrampup=5; }
do_S4() { preflight; run_jmx S4 S4-result-poll.jmx     -Jthreads="$THREADS" -Jduration="$DURATION" -Jrampup=10; }
do_S5() { preflight; run_jmx S5 S5-mixed.jmx           -Jscale="$SCALE"     -Jduration="$DURATION" -Jrampup=15; }
do_S7() { preflight; run_jmx S7 S7-ratelimit.jmx       -Jduration=20; }

# 等消息消费完。轮询而不是固定 sleep —— 固定 sleep 要么白等要么不够
wait_drain() {
  local timeout="${1:-120}" i=0 pending
  while [ "$i" -lt "$timeout" ]; do
    pending=$(docker exec fss-mysql mysql -uroot -proot -N -B flash_sale \
      -e "SELECT COUNT(*) FROM t_mq_message WHERE status IN (0,1) AND topic='FSS_ORDER_CREATE';" 2>/dev/null || echo -1)
    [ "$pending" = "0" ] && { log "消息已全部消费（${i}s）"; return 0; }
    sleep 2; i=$((i + 2))
  done
  warn "等待 ${timeout}s 后仍有 $pending 条未消费"
}

usage() {
  cat <<EOF
用法: ./run.sh {prepare|S1|S2|S3|S4|S5|S7|all}

  prepare  注册 \$USERS 个用户并取 JWT（跑一次即可）
  S1       活动详情读        S2  秒杀提交（有库存）
  S3       秒杀提交（售罄）   S4  结果轮询
  S5       混合场景          S7  限流验证（需用默认配置重启应用）
  all      prepare → S1 → S2 → S3 → S4 → S5

环境变量: HOST PORT ACTIVITY SKU USERS THREADS DURATION SCALE PREFIX
EOF
}

case "${1:-}" in
  prepare) do_prepare ;;
  S1) do_S1 ;; S2) do_S2 ;; S3) do_S3 ;; S4) do_S4 ;; S5) do_S5 ;; S7) do_S7 ;;
  all)
    echo "scenario,samples,error_pct,tps,avg_ms,p50_ms,p90_ms,p95_ms,p99_ms,p999_ms,max_ms" \
      > "$RESULTS/summary.csv"
    do_prepare; do_S1; do_S2; do_S3; do_S4; do_S5
    log "全部完成，汇总见 $RESULTS/summary.csv"
    column -s, -t < "$RESULTS/summary.csv"
    ;;
  *) usage; exit 1 ;;
esac
