#!/usr/bin/env bash
# =============================================================================
# 故障演练辅助 —— 只做三件事：造活动、发一次秒杀、看系统状态
#
# 单独抽出来是因为每个 F 用例都要重复这几步，而演练时手敲容易漏掉某一项
# 观测（尤其是"注入前的基线"），漏了就没法说明差异是注入造成的。
#
# 用法：
#   ./drill.sh new <库存>        造一个立刻可抢的活动，输出 "activityId skuId"
#   ./drill.sh buy <act> <sku> <用户行号>   发一次秒杀，打印响应
#   ./drill.sh state <act> <sku>            打印 Redis / DB / MQ 三侧状态
#   ./drill.sh ledger <act> <sku>           只打库存账目等式
# =============================================================================
set -uo pipefail

HOST="${HOST:-127.0.0.1}"; PORT="${PORT:-8080}"
BASE="http://$HOST:$PORT"
USERS_CSV="${USERS_CSV:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../perf/results" && pwd)/users.csv}"
MYSQL="docker exec fss-mysql mysql -uroot -proot -N -B flash_sale -e"
REDIS="docker exec fss-redis redis-cli"

admin_token() {
  [ -f /tmp/drill_admin.token ] && [ -s /tmp/drill_admin.token ] && { cat /tmp/drill_admin.token; return; }
  curl -s --max-time 10 -X POST "$BASE/api/user/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"Passw0rd1"}' | jq -r '.data.token' | tee /tmp/drill_admin.token
}

cmd_new() {
  local stock="${1:-100}"
  local T; T=$(admin_token)
  # 开始时间给 8 秒余量：太短会撞上"开始时间必须晚于当前时间"，
  # 太长则每个用例都要干等。8 秒足够 publish + warmup 两次调用
  local st en
  st=$(python -c "import datetime;print((datetime.datetime.now()+datetime.timedelta(seconds=8)).strftime('%Y-%m-%dT%H:%M:%S'))")
  en=$(python -c "import datetime;print((datetime.datetime.now()+datetime.timedelta(hours=4)).strftime('%Y-%m-%dT%H:%M:%S'))")

  local sku
  sku=$(curl -s --max-time 15 -X POST "$BASE/api/admin/sku" -H 'Content-Type: application/json' \
        -H "Authorization: Bearer $T" \
        -d "{\"productId\":1,\"spec\":\"DRILL-$(date +%s)\",\"price\":7999.00,\"stock\":$((stock*10)),\"status\":1}" \
        | jq -r '.data.skuId')

  printf '{"name":"DRILL-%s","startTime":"%s","endTime":"%s","goods":[{"skuId":%s,"seckillPrice":99.00,"totalStock":%s,"limitPerUser":1}]}' \
    "$(date +%H%M%S)" "$st" "$en" "$sku" "$stock" > /tmp/drill_act.json

  local aid
  aid=$(curl -s --max-time 15 -X POST "$BASE/api/admin/activity" -H 'Content-Type: application/json' \
        -H "Authorization: Bearer $T" --data-binary @/tmp/drill_act.json | jq -r '.data.activityId')
  curl -s -o /dev/null --max-time 10 -X POST "$BASE/api/admin/activity/$aid/publish" -H "Authorization: Bearer $T"
  curl -s -o /dev/null --max-time 15 -X POST "$BASE/api/admin/activity/$aid/warmup"  -H "Authorization: Bearer $T"
  sleep 9   # 等过开始时间
  echo "$aid $sku"
}

cmd_buy() {
  local aid="$1" sku="$2" row="${3:-1}"
  local tok; tok=$(sed -n "${row}p" "$USERS_CSV" | cut -d, -f3)
  curl -s -w "\n" --max-time 15 -X POST "$BASE/api/seckill/do" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $tok" \
    -d "{\"activityId\":$aid,\"skuId\":$sku,\"quantity\":1}"
}

cmd_ledger() {
  local aid="$1" sku="$2"
  $MYSQL "SELECT CONCAT(total_stock,' = ',available_stock,' + ',locked_stock,' + ',sold_stock,
          '   等式',IF(total_stock=available_stock+locked_stock+sold_stock,' 成立','【破了】'),
          '   released=',released_stock,' status=',status)
          FROM t_seckill_goods WHERE activity_id=$aid AND sku_id=$sku;" 2>/dev/null
}

cmd_state() {
  local aid="$1" sku="$2"
  echo "--- Redis ---"
  echo "  stock            = $($REDIS GET "seckill:stock:{$aid:$sku}" 2>/dev/null)"
  echo "  goods.status     = $($REDIS HGET "seckill:goods:{$aid:$sku}" status 2>/dev/null)"
  echo "  bought 人数      = $($REDIS HLEN "seckill:bought:{$aid:$sku}" 2>/dev/null)"
  echo "  req key 数       = $($REDIS --scan --pattern "seckill:req:{$aid:$sku}:*" 2>/dev/null | wc -l)"
  echo "  uncertain 待确认 = $($REDIS ZCARD seckill:uncertain 2>/dev/null)"
  echo "  degrade 人工/自动= $($REDIS GET degrade:level 2>/dev/null)/$($REDIS GET degrade:level:auto 2>/dev/null)"
  echo "--- MySQL 库存账目 ---"
  echo "  $(cmd_ledger "$aid" "$sku")"
  echo "--- MySQL 计数 ---"
  $MYSQL "SELECT CONCAT('  订单 ',(SELECT COUNT(*) FROM t_order WHERE activity_id=$aid),
          '  请求 ',(SELECT COUNT(*) FROM t_seckill_request WHERE activity_id=$aid),
          '  消息待发/已发/已消费 ',
          (SELECT COUNT(*) FROM t_mq_message WHERE status=0),'/',
          (SELECT COUNT(*) FROM t_mq_message WHERE status=1),'/',
          (SELECT COUNT(*) FROM t_mq_message WHERE status=2),
          '  对账任务 ',(SELECT COUNT(*) FROM t_reconcile_task));" 2>/dev/null
  echo "--- 指标 ---"
  curl -s --max-time 5 "$BASE/actuator/prometheus" 2>/dev/null \
    | grep -E "^fss_(alarm|redis_uncertain|stock_rollback|dlq|mq_give_up|degrade_level)" | sed 's/^/  /' | head -8
}

case "${1:-}" in
  new)    shift; cmd_new "$@" ;;
  buy)    shift; cmd_buy "$@" ;;
  state)  shift; cmd_state "$@" ;;
  ledger) shift; cmd_ledger "$@" ;;
  *) echo "用法: ./drill.sh {new <库存>|buy <act> <sku> <行号>|state <act> <sku>|ledger <act> <sku>}"; exit 1 ;;
esac
