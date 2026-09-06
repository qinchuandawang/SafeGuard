# -*- coding: utf-8 -*-
"""语义缓存 10 对验证：每对同语义不同措辞，第二次应命中语义缓存。"""
import json
import time
import urllib.request
import urllib.error

URL = "http://localhost:8080/api/internal/llm/text-detection"
HEADERS = {"Content-Type": "application/json", "X-Forwarded-For": "10.0.0.9"}
TIMEOUT = 120

# 每对 (A, B)：A 为冷启动措辞，B 为同语义不同措辞（应命中语义缓存）
PAIRS = [
    ("刷单", 
     "有人自称刷单平台客服，让我先垫付货款，完成任务后返还本金和佣金，还发来了二维码让我扫码付款。",
     "客服说动动手指做刷单兼职就能赚佣金，需要我自己先付钱下单，做完后连本带利一起退给我。"),
    ("客服退款",
     "接到陌生电话，对方自称电商平台客服，说我的订单商品有质量问题，要主动给我办理双倍退款赔偿，让我下载一个APP进行操作。",
     "有个陌生人打来电话说是某购物网站售后人员，因商品质量缺陷要给我三倍赔付，指导我安装一个视频会议软件处理退款事宜。"),
    ("公检法",
     "一个自称公安局刑侦队长的人打来电话，说我名下的银行卡涉嫌洗钱案件，要求我配合调查，把资金转入所谓的安全账户自证清白。",
     "来电者声称是检察院工作人员，说我身份证被冒用涉及一桩洗钱大案，让我把钱全部转到指定账户进行资金清查，否则就逮捕我。"),
    ("出借银行卡",
     "网上有人说花钱租我的银行卡和手机卡用，每张卡每月给我两千块租金，让我把卡和密码、绑定的手机号都交给他。",
     "有人在群里收购闲置银行卡，说只要把卡借出去躺赚，一张卡每月能拿两千元好处费，还会收购配套的SIM卡。"),
    ("屏幕共享",
     "自称银行客服的人让我下载一个会议软件并打开屏幕共享，说要指导我操作关闭会员扣费，还在一步步看我手机屏幕。",
     "对方说是支付平台的工作人员，要求我安装某款远程软件并开启屏幕共享功能，全程指导我关闭自动续费，一直在实时观看我的操作。"),
    ("96110",
     "接到96110打来的电话，工作人员说我是正在遭受电信网络诈骗的风险人群，让我立即停止和陌生人的资金往来。",
     "手机上来电显示96110，反诈中心的工作人员提醒我目前正在被电信诈骗分子盯上，劝我不要再给陌生账户转账了。"),
    ("游戏账号",
     "有买家在游戏里说要高价收购我的账号，让我去一个陌生的担保交易平台交易，网站客服说我的卡号填错资金被冻结，要交解冻金才能提现。",
     "有人出高价买我的游戏账号，指定去一个没听说过的交易网站，充值提现时系统提示银行卡信息错误被冻结，让我先缴纳保证金解冻。"),
    ("ETC",
     "收到短信说我ETC账户已失效无法通行，让我点击短信里的链接去更新信息，否则将被列入高速黑名单。",
     "手机收到一条通知，声称我的ETC设备认证过期，需要尽快点击附带网址验证身份续期，逾期会影响通行并被拉黑。"),
    ("解冻费",
     "网上兼职说要交300元会员费才能接单，我交了之后对方又说任务单被冻结，需要再交2000元解冻费才能返款。",
     "做网络任务赚佣金，平台先让我支付了三百块入会押金，随后说我操作失误导致账户冻结，必须再充值两千块才能解冻返现。"),
    ("注销白条",
     "一通自称金融平台客服的电话说我不注销京东白条额度会影响征信，让我配合把额度清零，下载APP操作并开启屏幕共享。",
     "陌生来电声称是借贷平台工作人员，说我的校园贷白条记录不取消将影响个人征信记录，催促我马上把额度注销掉。"),
]


def call(text, trace_id):
    body = json.dumps({"text": text, "taskId": "cache-validate", "traceId": trace_id},
                      ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(URL, data=body, headers=HEADERS, method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
            status = resp.status
    except urllib.error.HTTPError as e:
        payload = {"http_error": e.code, "body": e.read().decode("utf-8", "replace")[:500]}
        status = e.code
    elapsed_ms = int((time.time() - t0) * 1000)
    return status, elapsed_ms, payload


def main():
    results = []
    for i, (topic, a_text, b_text) in enumerate(PAIRS, 1):
        print(f"[{i}/10] {topic} ...", flush=True)
        s1, ms1, p1 = call(a_text, f"pair{i}-a")
        s2, ms2, p2 = call(b_text, f"pair{i}-b")

        budget_err = any("预算已用尽" in json.dumps(p, ensure_ascii=False) for p in (p1, p2))
        ok1 = s1 == 200 and p1.get("code") == 200
        ok2 = s2 == 200 and p2.get("code") == 200
        d1 = json.dumps(p1.get("data"), sort_keys=True, ensure_ascii=False) if ok1 else None
        d2 = json.dumps(p2.get("data"), sort_keys=True, ensure_ascii=False) if ok2 else None
        same = (d1 is not None and d1 == d2)
        hit = ok1 and ok2 and same and ms2 < 4000 and not budget_err

        results.append({
            "pair": i, "topic": topic, "hit": hit,
            "first_status": s1, "first_ms": ms1,
            "second_status": s2, "second_ms": ms2,
            "report_identical": same, "budget_error": budget_err,
            "first_data": p1.get("data"), "second_data": p2.get("data"),
        })
        print(f"    first={ms1}ms({s1})  second={ms2}ms({s2})  identical={same}  HIT={hit}", flush=True)
        time.sleep(1)

    hits = sum(1 for r in results if r["hit"])
    summary = {
        "total_pairs": len(results), "hits": hits,
        "hit_rate": round(hits / len(results), 4),
        "avg_second_ms": round(sum(r["second_ms"] for r in results if r["hit"]) / max(hits, 1), 1),
        "max_second_ms": max((r["second_ms"] for r in results if r["hit"]), default=None),
        "first_ms_list": [r["first_ms"] for r in results],
        "second_ms_list": [r["second_ms"] for r in results],
        "budget_error_count": sum(1 for r in results if r["budget_error"]),
    }
    with open("performance/rag/semantic-cache-pairs-result.json", "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "pairs": results}, f, ensure_ascii=False, indent=2)
    print("\nRESULT " + json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
