# -*- coding: utf-8 -*-
"""SafeGuard RAG 评测（对齐 RAGAS 体系）

检索侧（全量）：hit@1/3/5、MRR、nDCG@5、precision@5，baseline(混合检索) vs agentic(反思补检)
生成侧（采样，LLM-as-judge，对齐 RAGAS 定义）：
  - Faithfulness        : response 原子语句能被 retrieved_contexts 支持的比例
  - Answer Relevancy    : 由 response 反推问题与原始问题的 embedding 余弦相似度均值
  - Context Recall      : reference(ground truth) 语句能被 contexts 支持的比例
  - Context Precision   : contexts 对得出答案是否“有用”的 Average Precision（惩罚靠前的不相关上下文）
"""
import argparse
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

ROOT = r'd:\Git\Job Search Preparation\简历\简历内容详解\SafeGuard'
CASES = os.path.join(ROOT, 'performance', 'rag', 'cases.json')
KB = os.path.join(ROOT, 'backend', 'src', 'main', 'resources', 'knowledge', 'anti_fraud_knowledge.txt')
ENV_PATH = os.path.join(ROOT, '.env')
OUT = os.path.join(ROOT, 'performance', 'rag', 'ragas-eval-result.json')

API = 'http://127.0.0.1:8080'


def load_env():
    env = {}
    with open(ENV_PATH, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                k, v = line.split('=', 1)
                env[k.strip()] = v.strip()
    return env


ENV = load_env()
LLM_URL = ENV.get('LLM_API_URL') or (ENV.get('LLM_BASE_URL', '').rstrip('/') + '/v1/chat/completions')
LLM_KEY = ENV.get('LLM_API_KEY') or ENV.get('DEEPSEEK_API_KEY')
LLM_MODEL = ENV.get('LLM_MODEL', '')
SF_KEY = ENV.get('SILICONFLOW_API_KEY', '')
SF_EMB_URL = 'https://api.siliconflow.cn/v1/embeddings'
SF_EMB_MODEL = 'BAAI/bge-large-zh-v1.5'


# ---------------- HTTP helpers ----------------
def _post(url, body, headers, timeout=120, retries=6):
    """带 429 退避重试的 POST。"""
    delay = 1.0
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(
                url, data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
                headers=headers, method='POST')
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            last = e
            if e.code == 429 and i < retries - 1:
                time.sleep(delay + (i * 0.5))
                delay *= 2
                continue
            raise
        except Exception as e:
            last = e
            if i < retries - 1:
                time.sleep(delay)
                delay *= 1.8
                continue
            raise
    raise last


def _get(url, headers=None, timeout=120, retries=6):
    """带 429 退避重试的 GET。"""
    delay = 1.0
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers=headers or {}, method='GET')
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            last = e
            if e.code == 429 and i < retries - 1:
                time.sleep(delay + (i * 0.5))
                delay *= 2
                continue
            raise
        except Exception as e:
            last = e
            if i < retries - 1:
                time.sleep(delay)
                delay *= 1.8
                continue
            raise
    raise last


def llm_chat(prompt, temperature=0.2, max_tokens=1200, retries=2):
    body = {'model': LLM_MODEL,
            'messages': [{'role': 'user', 'content': prompt}],
            'temperature': temperature, 'max_tokens': max_tokens}
    headers = {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + LLM_KEY}
    last = None
    for _ in range(retries + 1):
        try:
            d = _post(LLM_URL, body, headers)
            return d['choices'][0]['message']['content']
        except Exception as e:
            last = e
            time.sleep(1.5)
    raise RuntimeError('llm_chat failed: %s' % last)


def embed(text, retries=2):
    body = {'model': SF_EMB_MODEL, 'input': text[:2000]}
    headers = {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + SF_KEY}
    last = None
    for _ in range(retries + 1):
        try:
            d = _post(SF_EMB_URL, body, headers, timeout=60)
            return d['data'][0]['embedding']
        except Exception as e:
            last = e
            time.sleep(1.5)
    raise RuntimeError('embed failed: %s' % last)


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


# ---------------- RAG endpoints ----------------
def rag_query(q):
    url = API + '/api/rag/query?q=' + urllib.parse.quote(q)
    d = _get(url, {'X-Forwarded-For': '10.200.0.%d' % (abs(hash(q)) % 250 + 1)}, timeout=60)
    return (d.get('data') or [])


def agentic_query(q):
    url = API + '/api/rag/agentic-query?q=' + urllib.parse.quote(q)
    d = _get(url, {'X-Forwarded-For': '10.200.0.%d' % (abs(hash(q)) % 250 + 1)}, timeout=90)
    data = d.get('data') or {}
    return {
        'results': data.get('results') or [],
        'refined': data.get('refined'),
        'coverageScore': data.get('coverageScore'),
        'sufficient': data.get('sufficient'),
        'iterations': data.get('iterations'),
        'costMs': data.get('costMs'),
    }


# ---------------- metrics helpers ----------------
def rank_metrics(results, expected):
    hit1 = hit3 = hit5 = False
    rr = 0.0
    dcg = 0.0
    rel_count = 0
    limit = min(5, len(results))
    for i in range(limit):
        src = str(results[i].get('source', ''))
        rel = 1 if src in expected else 0
        if rel:
            rel_count += 1
        if i == 0 and rel:
            hit1 = True
        if i < 3 and rel:
            hit3 = True
        if i < 5 and rel:
            hit5 = True
        if rel and rr == 0.0:
            rr = 1.0 / (i + 1)
        if rel:
            dcg += 1.0 / math.log(i + 2, 2)
    rel_total = min(len(expected), 5)
    idcg = sum(1.0 / math.log(i + 2, 2) for i in range(rel_total)) or 1.0
    return {
        'hit1': hit1, 'hit3': hit3, 'hit5': hit5, 'mrr': rr,
        'ndcg': dcg / idcg if idcg else 0.0,
        'prec5': (rel_count / limit) if limit else 0.0,
    }


def mean(vals):
    vals = [v for v in vals if v is not None]
    return sum(vals) / len(vals) if vals else 0.0


def _norm(arr, key):
    out = []
    for x in arr:
        if isinstance(x, dict):
            if key:
                if key in x:
                    out.append(x[key])
            else:
                out.append(x)
        else:
            out.append(x)
    return out


def parse_json_list(text, key=None):
    """容错解析 LLM 输出：支持 markdown 代码块、纯数组、对象包裹的数组。"""
    if not text:
        return []
    t = re.sub(r'```(?:json)?', '', str(text)).strip()
    m = re.search(r'\[.*\]', t, re.S)
    if m:
        try:
            return _norm(json.loads(m.group(0)), key)
        except Exception:
            pass
    m2 = re.search(r'\{.*\}', t, re.S)
    if m2:
        try:
            obj = json.loads(m2.group(0))
            if isinstance(obj, dict):
                for v in obj.values():
                    if isinstance(v, list):
                        return _norm(v, key)
        except Exception:
            pass
    # 退化：抽取所有 {...} 小块
    objs = re.findall(r'\{[^{}]*\}', t)
    if objs and key:
        vals = []
        for o in objs:
            mm = re.search(r'"%s"\s*:\s*(1|0|true|false)' % key, o)
            if mm:
                vals.append(1 if mm.group(1) in ('1', 'true') else 0)
        if vals:
            return vals
    return []


# ---------------- RAGAS generation-side ----------------
GEN_ANSWER_TPL = """你是反诈智能助手。请严格依据下面的参考资料回答用户问题。

规则：
- 只使用参考资料中的信息，不要编造，不要使用外部知识
- 如果资料不足以回答，直接说“根据现有资料无法确认”，不要猜测
- 回答控制在 120 字以内，条理清晰

参考资料：
{contexts}

用户问题：{question}

回答："""


def gen_answer(question, contexts):
    ctx = '\n\n'.join('[%d] %s' % (i + 1, c[:800]) for i, c in enumerate(contexts[:5]))
    return llm_chat(GEN_ANSWER_TPL.format(contexts=ctx, question=question),
                    temperature=0.2, max_tokens=500)


FAITH_TPL = """请判断下面的“回答”中的每条陈述是否能被“参考资料”支持。

先把回答拆成若干条原子陈述（每条只表达一个事实，不含代词），再逐条判断该陈述是否能在参考资料中找到依据。
只输出 JSON 数组，每项形如 {{"statement":"...","supported":1或0}}。
禁止使用 markdown 代码块，禁止输出任何解释文字，直接以 [ 开头。

参考资料：
{contexts}

回答：
{answer}
"""


def faithfulness(answer, contexts):
    ctx = '\n\n'.join(c[:700] for c in contexts[:5])
    raw = llm_chat(FAITH_TPL.format(contexts=ctx, answer=answer), temperature=0.0, max_tokens=900)
    items = parse_json_list(raw)
    if not items:
        return None
    vals = []
    for it in items:
        if isinstance(it, dict) and 'supported' in it:
            try:
                vals.append(1.0 if float(it['supported']) >= 0.5 else 0.0)
            except Exception:
                continue
    return mean(vals) if vals else None


CTXRECALL_TPL = """判断“标准答案”中的每条陈述，是否能在“参考资料”中找到依据（即被资料归因/支持）。
只输出 JSON 数组，每项形如 {{"statement":"...","attributed":1或0}}。
禁止使用 markdown 代码块，禁止输出任何解释文字，直接以 [ 开头。

参考资料：
{contexts}

标准答案：
{reference}
"""


def context_recall(reference, contexts):
    ctx = '\n\n'.join(c[:700] for c in contexts[:5])
    raw = llm_chat(CTXRECALL_TPL.format(contexts=ctx, reference=reference[:1500]),
                   temperature=0.0, max_tokens=1200)
    items = parse_json_list(raw)
    if not items:
        return None
    vals = []
    for it in items:
        if isinstance(it, dict) and 'attributed' in it:
            try:
                vals.append(1.0 if float(it['attributed']) >= 0.5 else 0.0)
            except Exception:
                continue
    return mean(vals) if vals else None


CTXPREC_TPL = """下面按顺序给出若干条参考资料片段（[1] 最靠前）。判断每条片段对于回答该问题是否有用。
只输出 JSON 数组，每项形如 {{"idx":1,"verdict":1或0}}（1=有用，0=无用）。
禁止使用 markdown 代码块，禁止输出任何解释文字，直接以 [ 开头。

问题：{question}
回答：{answer}
参考资料：
{contexts}
"""


def context_precision(question, answer, contexts):
    cs = contexts[:5]
    if not cs:
        return None
    ctx = '\n\n'.join('[%d] %s' % (i + 1, c[:600]) for i, c in enumerate(cs))
    raw = llm_chat(CTXPREC_TPL.format(question=question, answer=answer[:600], contexts=ctx),
                   temperature=0.0, max_tokens=500)
    items = parse_json_list(raw)
    verdicts = [0] * len(cs)
    if items:
        for it in items:
            if isinstance(it, dict) and 'verdict' in it:
                try:
                    i = int(it.get('idx', 0)) - 1
                    if 0 <= i < len(cs):
                        verdicts[i] = 1 if float(it['verdict']) >= 0.5 else 0
                except Exception:
                    continue
    else:
        return None
    # Average Precision
    num_rel = 0
    ap = 0.0
    for k, v in enumerate(verdicts, start=1):
        if v == 1:
            num_rel += 1
            ap += num_rel / k
    total = sum(verdicts)
    return (ap / total) if total else 0.0


GENQ_TPL = """根据下面的回答，生成 {n} 个能够被该回答完整回答的问题（这些问题应由该回答推出）。
只输出 JSON 数组，如 ["问题1","问题2"]。
禁止使用 markdown 代码块，禁止输出任何解释文字，直接以 [ 开头。

回答：
{answer}
"""


def answer_relevancy(question, answer):
    raw = llm_chat(GENQ_TPL.format(n=3, answer=answer[:800]), temperature=0.3, max_tokens=400)
    qs = parse_json_list(raw)
    qs = [str(q).strip() for q in qs if str(q).strip()]
    if not qs:
        return None
    try:
        q_emb = embed(question)
        sims = []
        for gq in qs[:3]:
            try:
                sims.append(cosine(q_emb, embed(gq)))
            except Exception:
                continue
        return mean(sims) if sims else None
    except Exception:
        return None


# ---------------- knowledge base ----------------
def load_kb_sections():
    with open(KB, encoding='utf-8') as f:
        lines = f.read().split('\n')
    sections = []
    cur = None
    for line in lines:
        if line.startswith('## '):
            if cur:
                sections.append(cur)
            cur = {'title': line[3:].strip(), 'body': ''}
        elif cur is not None:
            cur['body'] += line + '\n'
    if cur:
        sections.append(cur)
    return sections


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--workers', type=int, default=3, help='后端有 429 限流，建议 2-4')
    ap.add_argument('--ragas-sample', type=int, default=100)
    ap.add_argument('--limit', type=int, default=0, help='只取前 N 条（调试用）')
    ap.add_argument('--skip-retrieval', action='store_true')
    args = ap.parse_args()

    with open(CASES, encoding='utf-8') as f:
        cases = json.load(f)
    eval_set = []
    for key in ('positive', 'multiIntent', 'generated'):
        for c in cases.get(key, []):
            eval_set.append({'q': c['q'], 'sources': c.get('sources', []), 'group': key})
    if args.limit:
        eval_set = eval_set[:args.limit]
    print('eval set size = %d' % len(eval_set), flush=True)

    sections = load_kb_sections()
    print('kb sections = %d' % len(sections), flush=True)

    # ---------- retrieval ----------
    base_m, agent_m, refined = [], [], 0
    if not args.skip_retrieval:
        def work(item):
            q, exp = item['q'], item['sources']
            try:
                b = rag_query(q)
                bm = rank_metrics(b, exp)
            except Exception as e:
                b, bm = [], None
            try:
                ag = agentic_query(q)
                am = rank_metrics(ag['results'], exp)
                rf = 1 if ag.get('refined') else 0
            except Exception as e:
                ag, am, rf = {'results': []}, None, 0
            return bm, am, rf

        t0 = time.time()
        with ThreadPoolExecutor(max_workers=args.workers) as ex:
            futs = [ex.submit(work, it) for it in eval_set]
            done = 0
            for fu in as_completed(futs):
                bm, am, rf = fu.result()
                if bm:
                    base_m.append(bm)
                if am:
                    agent_m.append(am)
                refined += rf
                done += 1
                if done % 50 == 0:
                    print('  retrieval %d/%d  %.0fs' % (done, len(eval_set), time.time() - t0), flush=True)
        print('retrieval done in %.0fs (base=%d agent=%d)' % (time.time() - t0, len(base_m), len(agent_m)), flush=True)

    def agg(ms):
        if not ms:
            return {}
        n = len(ms)
        return {
            'n': n,
            'hit@1': round(sum(m['hit1'] for m in ms) / n, 4),
            'hit@3': round(sum(m['hit3'] for m in ms) / n, 4),
            'hit@5': round(sum(m['hit5'] for m in ms) / n, 4),
            'mrr': round(mean([m['mrr'] for m in ms]), 4),
            'ndcg@5': round(mean([m['ndcg'] for m in ms]), 4),
            'precision@5': round(mean([m['prec5'] for m in ms]), 4),
        }

    result = {
        'timestamp': time.strftime('%Y-%m-%d %H:%M:%S'),
        'eval_set_size': len(eval_set),
        'ragas_sample': args.ragas_sample,
        'refined_case_count': refined,
        'baseline': agg(base_m),
        'agentic': agg(agent_m),
    }

    # ---------- RAGAS generation side (sampled) ----------
    sample_n = min(args.ragas_sample, len(eval_set))
    step = max(1, len(eval_set) // sample_n)
    sample = eval_set[::step][:sample_n]
    print('ragas sample = %d' % len(sample), flush=True)

    def ragas_work(item):
        q, exp = item['q'], item['sources']
        src = exp[0] if exp else ''
        m = re.search(r'(\d+)', src or '')
        ref = ''
        if m:
            idx = int(m.group(1)) - 1
            if 0 <= idx < len(sections):
                ref = sections[idx]['body'].strip()
        try:
            ag = agentic_query(q)
            ctxs = [r.get('content', '') for r in (ag.get('results') or [])][:5]
            ans = gen_answer(q, ctxs)
            f = faithfulness(ans, ctxs)
            ar = answer_relevancy(q, ans)
            cr = context_recall(ref or (ctxs[0] if ctxs else ''), ctxs)
            cp = context_precision(q, ans, ctxs)
            return {'faithfulness': f, 'answer_relevancy': ar,
                    'context_recall': cr, 'context_precision': cp}
        except Exception as e:
            return {'error': str(e)[:200]}

    t1 = time.time()
    rows = []
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = [ex.submit(ragas_work, it) for it in sample]
        done = 0
        for fu in as_completed(futs):
            rows.append(fu.result())
            done += 1
            if done % 10 == 0:
                print('  ragas %d/%d  %.0fs' % (done, len(sample), time.time() - t1), flush=True)
    print('ragas done in %.0fs' % (time.time() - t1), flush=True)

    def col(k):
        return [r.get(k) for r in rows if r.get(k) is not None]

    result['ragas'] = {
        'sample_size': len(rows),
        'errors': sum(1 for r in rows if 'error' in r),
        'faithfulness': round(mean(col('faithfulness')), 4),
        'answer_relevancy': round(mean(col('answer_relevancy')), 4),
        'context_recall': round(mean(col('context_recall')), 4),
        'context_precision': round(mean(col('context_precision')), 4),
        'faithfulness_n': len(col('faithfulness')),
        'answer_relevancy_n': len(col('answer_relevancy')),
        'context_recall_n': len(col('context_recall')),
        'context_precision_n': len(col('context_precision')),
    }

    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print('SAVED -> %s' % OUT)


if __name__ == '__main__':
    main()
