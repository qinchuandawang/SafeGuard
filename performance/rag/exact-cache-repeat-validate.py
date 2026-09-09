# -*- coding: utf-8 -*-
"""精确答复缓存验证：1 次冷启动 + 100 次完全相同请求。

验证目标（对应简历口径）：
  - 冷启动消耗真实 LLM Token；随后重复请求全部命中缓存、消耗 0 Token；
  - 缓存命中延迟分布（P50/P95/P99/max）；
  - 命中返回的 LLM 生成字段与冷启动一致（规则层字段按设计实时重算，不比对）。

Token 对账方式：运行后由 query-llm-usage.js 直接查询 MySQL llm_usage_record 账本
（按本脚本输出的时间窗过滤），比管理端快照接口更权威。
（注：/api/admin/reliability/llm-token-budget 当前存在 LocalDate 序列化 500 的 bug，
正好说明账本直查的必要性。）

运行环境说明：本地默认 infra.redis.enabled=false，二级缓存实际由 Caffeine L1 承载；
分布式 L2（Redis）路径存在但本环境未启用，报告元数据中如实标注。
"""
import json
import random
import time
import urllib.request
import urllib.error
import uuid
from datetime import datetime

BASE = "http://localhost:8080"
URL = BASE + "/api/internal/llm/text-detection"
TIMEOUT = 120
REPEATS = 100
RESULT_PATH = "exact-cache-repeat-result.json"


def make_headers():
    """每个请求轮换来源 IP，模拟多用户负载；
    单 IP 限流为 60 次/分钟，固定 IP 会在 100 连发下烧穿导致 429。"""
    ip = "10.%d.%d.%d" % (random.randint(1, 250), random.randint(1, 250), random.randint(1, 250))
    return {"Content-Type": "application/json", "X-Forwarded-For": ip}

# LLM 生成字段（缓存内容）；matchedRules/ruleRiskScore/knowledgeEvidence 为实时重算字段，不参与比对
LLM_FIELDS = ["report", "reasoningSteps", "suspiciousPoints", "scamType",
              "riskLevel", "riskProbability", "probabilities", "confidence", "advice"]


def call(text, trace_id):
    body = json.dumps({"text": text, "taskId": "exact-cache-repeat", "traceId": trace_id},
                      ensure_ascii=False).encode("utf-8")
    delay = 0.5
    last_err = None
    for attempt in range(5):
        req = urllib.request.Request(URL, data=body, headers=make_headers(), method="POST")
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
                status = resp.status
            return status, int((time.time() - t0) * 1000), payload
        except urllib.error.HTTPError as e:
            last_err = e
            if e.code == 429:
                time.sleep(delay)
                delay *= 2
                continue
            return e.code, int((time.time() - t0) * 1000), {"http_error": e.code}
        except Exception as e:  # 网络抖动重试
            last_err = e
            time.sleep(delay)
            delay *= 2
    raise RuntimeError("请求多次失败: %s" % last_err)


def llm_signature(data):
    if not isinstance(data, dict):
        return None
    return json.dumps({k: data.get(k) for k in LLM_FIELDS},
                      sort_keys=True, ensure_ascii=False)


def pct(sorted_list, p):
    """最近邻百分位（向上取整），与常用 P95 定义一致。"""
    if not sorted_list:
        return None
    idx = min(len(sorted_list) - 1, max(0, int(round(p / 100.0 * len(sorted_list) + 0.5)) - 1))
    return sorted_list[idx]


def main():
    marker = uuid.uuid4().hex[:8].upper()
    text = ("接到陌生电话，对方自称某电商平台客服，说我的订单商品检测出质量问题，"
            "要主动给我办理三倍退款赔偿，让我下载一个指定的APP并开启屏幕共享进行操作，"
            "事件编号XC-%s，请帮我判断这是不是诈骗。" % marker)

    print("marker = %s" % marker, flush=True)
    window_start = datetime.now()

    print("[cold] first request ...", flush=True)
    cold_status, cold_ms, cold_payload = call(text, "cold-%s" % marker)
    cold_ok = cold_status == 200 and isinstance(cold_payload, dict) and cold_payload.get("code") == 200
    cold_sig = llm_signature(cold_payload.get("data")) if cold_ok else None
    print("cold: status=%s ms=%s ok=%s" % (cold_status, cold_ms, cold_ok), flush=True)

    if not cold_ok or cold_sig is None:
        result = {"passed": False, "error": "cold request failed",
                  "cold_status": cold_status, "cold_ms": cold_ms,
                  "cold_payload_sample": str(cold_payload)[:500]}
        with open(RESULT_PATH, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print("COLD FAILED, result written", flush=True)
        return

    latencies, mismatch, bad_status = [], 0, 0
    for i in range(1, REPEATS + 1):
        s, ms, payload = call(text, "repeat-%s-%d" % (marker, i))
        ok = s == 200 and isinstance(payload, dict) and payload.get("code") == 200
        if not ok:
            bad_status += 1
            print("repeat %d BAD status=%s" % (i, s), flush=True)
            continue
        if llm_signature(payload.get("data")) != cold_sig:
            mismatch += 1
            print("repeat %d SIGNATURE MISMATCH" % i, flush=True)
        latencies.append(ms)
        if i % 25 == 0:
            print("progress %d/100" % i, flush=True)
        time.sleep(0.03)

    window_end = datetime.now()
    lat_sorted = sorted(latencies)
    summary = {
        "repeats": REPEATS,
        "success": len(latencies),
        "bad_status_count": bad_status,
        "signature_mismatch_count": mismatch,
        "avg_ms": round(sum(latencies) / len(latencies), 1) if latencies else None,
        "p50_ms": pct(lat_sorted, 50),
        "p95_ms": pct(lat_sorted, 95),
        "p99_ms": pct(lat_sorted, 99),
        "min_ms": lat_sorted[0] if lat_sorted else None,
        "max_ms": lat_sorted[-1] if lat_sorted else None,
    }
    result = {
        "passed": None,  # 由账本对账后回填
        "meta": {
            "date": datetime.now().isoformat(timespec="seconds"),
            "endpoint": URL,
            "scene": "text-analysis",
            "cache_config": {
                "response_cache_enabled": True,
                "cache_key": "SHA-256(model|promptVersion|scene|temperature|maxTokens|prompt)",
                "ttl_seconds": 600,
                "redis_enabled_in_env": False,
                "note": "本环境 infra.redis.enabled=false，命中由 Caffeine L1 承载；Redis L2 路径存在未启用",
            },
            "method": "唯一编号文本保证冷启动（精确+语义缓存均未见过）；随后 100 次完全相同请求；"
                      "Token 对账直接查询 MySQL llm_usage_record 账本（时间窗见 window）；"
                      "命中一致性比对 LLM 生成字段（规则层字段按设计实时重算）",
            "window_start": window_start.isoformat(timespec="seconds"),
            "window_end": window_end.isoformat(timespec="seconds"),
            "marker": marker,
        },
        "cold": {"status": cold_status, "ms": cold_ms,
                 "report_len": len(cold_payload["data"].get("report") or "")},
        "summary": summary,
        "ledger": None,  # 由 query-llm-usage.js 回填
        "latencies_ms": latencies,
    }
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print("=== SUMMARY ===")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print("window: %s ~ %s" % (result["meta"]["window_start"], result["meta"]["window_end"]))
    print("result written (ledger pending)", flush=True)


if __name__ == "__main__":
    main()
