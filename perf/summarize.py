#!/usr/bin/env python3
"""
把 JMeter 的 .jtl 汇总成一行 CSV（或人类可读的一段）。

为什么不直接用 JMeter 的 -e -o HTML 报告：
  · HTML 报告是给人看的，取不出「一行数字」拼进压测报告的表格
  · 它的 P99 定义与这里一致，但把它从 HTML 里抠出来比重算一遍还麻烦
  · **JMeter 的 HTML 报告默认按 label 分组**，而混合场景 S5 有三个 label，
    要的是「按场景汇总」和「按接口分别看」两份 —— 这里两份都出

百分位取「最近秩」（nearest-rank）：排序后取第 ceil(p/100 * n) 个，
与 JMeter 的默认算法一致。不做插值 —— 插值出来的 P99 是一个不存在的观测值。

用法:
  summarize.py raw.jtl S2            → 一行 CSV（追加到 summary.csv）
  summarize.py raw.jtl S2 --human    → 人类可读，并按 label 分组
"""
import csv
import math
import sys
from collections import defaultdict


def pct(sorted_vals, p):
    """最近秩百分位。空列表返回 0"""
    if not sorted_vals:
        return 0
    k = max(1, math.ceil(p / 100.0 * len(sorted_vals)))
    return sorted_vals[min(k, len(sorted_vals)) - 1]


def load(path):
    """读 .jtl（CSV 格式，带表头）。返回 (elapsed, success, label, code) 列表"""
    rows = []
    with open(path, "r", encoding="utf-8", errors="replace", newline="") as f:
        rdr = csv.DictReader(f)
        for r in rdr:
            try:
                rows.append((
                    int(r["elapsed"]),
                    r.get("success", "true").lower() == "true",
                    r.get("label", "?"),
                    r.get("responseCode", ""),
                    int(r["timeStamp"]),
                ))
            except (KeyError, ValueError):
                # 半行/损坏行直接跳过。压测中途 kill 掉 JMeter 会留下一个半行，
                # 为它整个汇总失败不值得
                continue
    return rows


def stats(rows):
    if not rows:
        return None
    elapsed = sorted(r[0] for r in rows)
    n = len(elapsed)
    errors = sum(1 for r in rows if not r[1])
    t0 = min(r[4] for r in rows)
    t1 = max(r[4] + r[0] for r in rows)
    span = max(1.0, (t1 - t0) / 1000.0)
    return {
        "samples": n,
        "error_pct": round(100.0 * errors / n, 3),
        "tps": round(n / span, 1),
        "avg_ms": round(sum(elapsed) / n, 1),
        "p50_ms": pct(elapsed, 50),
        "p90_ms": pct(elapsed, 90),
        "p95_ms": pct(elapsed, 95),
        "p99_ms": pct(elapsed, 99),
        "p999_ms": pct(elapsed, 99.9),
        "max_ms": elapsed[-1],
        "span_s": round(span, 1),
    }


COLS = ["samples", "error_pct", "tps", "avg_ms", "p50_ms", "p90_ms",
        "p95_ms", "p99_ms", "p999_ms", "max_ms"]


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    path, tag = sys.argv[1], sys.argv[2]
    human = "--human" in sys.argv

    rows = load(path)
    overall = stats(rows)
    if not overall:
        print(f"{tag}: 没有可用样本", file=sys.stderr)
        return 2

    if not human:
        print(tag + "," + ",".join(str(overall[c]) for c in COLS))
        return 0

    print(f"\n  {tag}  样本 {overall['samples']}  时长 {overall['span_s']}s  "
          f"TPS {overall['tps']}  错误率 {overall['error_pct']}%")
    print(f"    RT(ms): avg {overall['avg_ms']}  P50 {overall['p50_ms']}  "
          f"P90 {overall['p90_ms']}  P95 {overall['p95_ms']}  "
          f"P99 {overall['p99_ms']}  P999 {overall['p999_ms']}  max {overall['max_ms']}")

    # 按 label 分组：混合场景必须分开看，否则详情读的低延迟会把秒杀的高延迟平均掉
    by_label = defaultdict(list)
    for r in rows:
        by_label[r[2]].append(r)
    if len(by_label) > 1:
        print("    按接口:")
        for label in sorted(by_label):
            s = stats(by_label[label])
            print(f"      {label:22s} n={s['samples']:7d} TPS={s['tps']:8.1f} "
                  f"P99={s['p99_ms']:6d}ms err={s['error_pct']}%")

    # HTTP 码分布：非 200 的部分要能一眼看到
    codes = defaultdict(int)
    for r in rows:
        codes[r[3]] += 1
    non200 = {k: v for k, v in codes.items() if k != "200"}
    if non200:
        print("    非 200 响应: " + "  ".join(f"{k}={v}" for k, v in sorted(non200.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
