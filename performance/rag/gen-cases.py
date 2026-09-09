# -*- coding: utf-8 -*-
"""基于知识库生成扩充版 RAG 评测集（对齐 RAGAS TestsetGenerator 思路）。

思路：
1. 解析 anti_fraud_knowledge.txt 的 40 个主题节
2. 节序号 i (1-based) 对应切分后的 source: anti_fraud_knowledge_{i}.txt
   （已用 cases.json 中前 15 节交叉验证映射正确）
3. 每个节用 LLM 生成 N 个「答案可在该节中找到」的用户问题
"""
import json
import os
import re
import sys
import time
import urllib.request
import urllib.error

KB = r'd:\Git\Job Search Preparation\简历\简历内容详解\SafeGuard\backend\src\main\resources\knowledge\anti_fraud_knowledge.txt'
ENV_PATH = r'd:\Git\Job Search Preparation\简历\简历内容详解\SafeGuard\.env'
OUT_PATH = r'C:\Temp\generated_cases.json'

PER_SECTION = int(os.environ.get('GEN_PER_SECTION', '10'))


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
BASE = ENV.get('LLM_BASE_URL', '').rstrip('/')
URL = ENV.get('LLM_API_URL') or (BASE + '/v1/chat/completions')
KEY = ENV.get('LLM_API_KEY') or ENV.get('DEEPSEEK_API_KEY')
MODEL = ENV.get('LLM_MODEL', '')


def call_llm(prompt, max_tokens=2000, temperature=0.5, retries=3):
    body = {
        'model': MODEL,
        'messages': [{'role': 'user', 'content': prompt}],
        'temperature': temperature,
        'max_tokens': max_tokens,
    }
    req = urllib.request.Request(
        URL,
        data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + KEY},
        method='POST',
    )
    last_err = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                data = json.loads(resp.read().decode('utf-8'))
            return data['choices'][0]['message']['content']
        except Exception as e:
            last_err = e
            time.sleep(2 + attempt * 3)
    raise RuntimeError('LLM call failed: %s' % last_err)


def parse_sections():
    with open(KB, encoding='utf-8') as f:
        text = f.read()
    lines = text.split('\n')
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


PROMPT_TPL = """你是反诈知识问答测试集的生成助手。下面是一段反诈知识条目，请基于它生成 {n} 个真实用户可能会提出的问题。

要求：
1. 中文、口语化，像普通用户在反诈 App 里向 AI 助手提问的口吻
2. 每个问题的答案必须能在下面这段材料中找到，不要出材料覆盖不到的问题
3. {n} 个问题互不重复，尽量覆盖材料的不同要点（识别要点、处置建议、风险场景等）
4. 只输出 JSON 字符串数组，格式 ["问题1","问题2",...]，不要输出任何解释文字

材料标题：{title}

材料内容：
{body}
"""


def extract_json_array(text):
    m = re.search(r'\[.*\]', text, re.S)
    if not m:
        return []
    try:
        return json.loads(m.group(0))
    except Exception:
        # 容错：按行提取引号内容
        return re.findall(r'"([^"]{6,})"', m.group(0))


def main():
    sections = parse_sections()
    print('sections=%d, per_section=%d' % (len(sections), PER_SECTION))
    out = []
    for idx, sec in enumerate(sections, start=1):
        body = sec['body'].strip()
        if len(body) < 30:
            continue
        prompt = PROMPT_TPL.format(n=PER_SECTION, title=sec['title'], body=body[:2500])
        try:
            raw = call_llm(prompt)
            qs = extract_json_array(raw)
        except Exception as e:
            print('[ERR] section %d %s: %s' % (idx, sec['title'], e))
            continue
        got = 0
        for q in qs:
            q = str(q).strip()
            if len(q) < 6:
                continue
            out.append({
                'q': q,
                'sources': ['anti_fraud_knowledge_%d.txt' % idx],
                'topic': sec['title'],
                'generated': True,
            })
            got += 1
        print('section %2d %-40s -> %d' % (idx, sec['title'][:38], got), flush=True)

    with open(OUT_PATH, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print('TOTAL generated = %d -> %s' % (len(out), OUT_PATH))


if __name__ == '__main__':
    main()
