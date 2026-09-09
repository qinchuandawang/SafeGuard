const pptxgen = require("pptxgenjs");
const fs = require("fs");
const path = require("path");

// 使用 document-skills:pptx 推荐的 PptxGenJS 路线生成可编辑 PPTX。
const pptx = new pptxgen();
pptx.defineLayout({ name: "SAFEGUARD_WIDE", width: 13.333, height: 7.5 });
pptx.layout = "SAFEGUARD_WIDE";
pptx.author = "SafeGuard Team";
pptx.company = "SDU";
pptx.subject = "基于 Agent 的多模态 AI 反诈骗检测系统";
pptx.title = "SafeGuard 项目汇报";
pptx.lang = "zh-CN";
pptx.theme = {
  headFontFace: "Microsoft YaHei",
  bodyFontFace: "Microsoft YaHei",
  lang: "zh-CN"
};

const OUT = path.resolve(__dirname, "..", "SafeGuard项目汇报-document-skills.pptx");
const DOC = path.resolve(__dirname, "..", "..", "SafeGuard项目汇报文档.md");
const docText = fs.existsSync(DOC) ? fs.readFileSync(DOC, "utf8") : "";

const C = {
  ink: "0F172A",
  muted: "64748B",
  navy: "111827",
  cyan: "0891B2",
  teal: "0F766E",
  green: "16A34A",
  amber: "F59E0B",
  red: "DC2626",
  violet: "7C3AED",
  bg: "F8FAFC",
  panel: "FFFFFF",
  line: "CBD5E1",
  softCyan: "E0F2FE",
  softTeal: "CCFBF1",
  softAmber: "FEF3C7",
  softViolet: "EDE9FE",
  darkBlue: "0B1220"
};

const W = 13.333;
const H = 7.5;
const M = 0.62;

function addFooter(slide, index) {
  slide.addShape(pptx.ShapeType.line, {
    x: M,
    y: 7.08,
    w: W - M * 2,
    h: 0,
    line: { color: "E2E8F0", width: 1 }
  });
  slide.addText("SafeGuard · Agent 应用开发汇报", {
    x: M,
    y: 7.16,
    w: 5.2,
    h: 0.18,
    fontSize: 9,
    color: C.muted,
    margin: 0
  });
  slide.addText(String(index).padStart(2, "0"), {
    x: W - M - 0.45,
    y: 7.14,
    w: 0.45,
    h: 0.2,
    fontSize: 9,
    color: C.muted,
    align: "right",
    margin: 0
  });
}

function addTitle(slide, title, subtitle, index) {
  slide.background = { color: C.bg };
  slide.addText(title, {
    x: M,
    y: 0.42,
    w: 8.6,
    h: 0.46,
    fontFace: "Microsoft YaHei",
    fontSize: 28,
    bold: true,
    color: C.ink,
    margin: 0
  });
  if (subtitle) {
    slide.addText(subtitle, {
      x: M,
      y: 0.95,
      w: 8.8,
      h: 0.26,
      fontSize: 12,
      color: C.muted,
      margin: 0
    });
  }
  slide.addShape(pptx.ShapeType.rect, {
    x: 10.55,
    y: 0.36,
    w: 2.16,
    h: 0.36,
    fill: { color: C.darkBlue },
    line: { color: C.darkBlue, transparency: 100 }
  });
  slide.addText("DeepSeek-V4-Pro 中枢", {
    x: 10.74,
    y: 0.45,
    w: 1.78,
    h: 0.14,
    fontSize: 8.8,
    bold: true,
    color: "FFFFFF",
    align: "center",
    margin: 0
  });
  addFooter(slide, index);
}

function addPill(slide, text, x, y, w, color, fill) {
  slide.addShape(pptx.ShapeType.roundRect, {
    x,
    y,
    w,
    h: 0.32,
    rectRadius: 0.08,
    fill: { color: fill },
    line: { color: fill, transparency: 100 }
  });
  slide.addText(text, {
    x,
    y: y + 0.08,
    w,
    h: 0.13,
    fontSize: 8.8,
    bold: true,
    color,
    align: "center",
    margin: 0
  });
}

function addCard(slide, x, y, w, h, title, body, accent) {
  slide.addShape(pptx.ShapeType.rect, {
    x,
    y,
    w,
    h,
    fill: { color: C.panel },
    line: { color: "E2E8F0", width: 1 },
    shadow: { type: "outer", color: "000000", blur: 1, offset: 1, angle: 45, opacity: 0.08 }
  });
  slide.addShape(pptx.ShapeType.rect, {
    x,
    y,
    w: 0.08,
    h,
    fill: { color: accent },
    line: { color: accent, transparency: 100 }
  });
  slide.addText(title, {
    x: x + 0.22,
    y: y + 0.22,
    w: w - 0.42,
    h: 0.24,
    fontSize: 13.8,
    bold: true,
    color: C.ink,
    margin: 0
  });
  slide.addText(body, {
    x: x + 0.22,
    y: y + 0.66,
    w: w - 0.42,
    h: h - 0.8,
    fontSize: 10.8,
    color: "334155",
    breakLine: false,
    fit: "shrink",
    valign: "top",
    margin: 0.02
  });
}

function addStep(slide, n, title, body, x, y, w, accent) {
  slide.addShape(pptx.ShapeType.ellipse, {
    x,
    y,
    w: 0.46,
    h: 0.46,
    fill: { color: accent },
    line: { color: accent, transparency: 100 }
  });
  slide.addText(String(n), {
    x,
    y: y + 0.12,
    w: 0.46,
    h: 0.15,
    fontSize: 10,
    bold: true,
    color: "FFFFFF",
    align: "center",
    margin: 0
  });
  slide.addText(title, {
    x: x + 0.62,
    y: y + 0.02,
    w,
    h: 0.2,
    fontSize: 12.4,
    bold: true,
    color: C.ink,
    margin: 0
  });
  slide.addText(body, {
    x: x + 0.62,
    y: y + 0.33,
    w,
    h: 0.4,
    fontSize: 10,
    color: "475569",
    fit: "shrink",
    margin: 0
  });
}

function addArrow(slide, x, y, w, color) {
  const isLeft = w < 0;
  slide.addShape(pptx.ShapeType.line, {
    x: isLeft ? x + w : x,
    y,
    w: Math.abs(w),
    h: 0,
    line: {
      color,
      width: 1.6,
      beginArrowType: isLeft ? "triangle" : "none",
      endArrowType: isLeft ? "none" : "triangle"
    }
  });
}

function bullets(items) {
  return items.map((text, i) => ({ text, options: { bullet: true, breakLine: i < items.length - 1 } }));
}

// 1. 封面
{
  const slide = pptx.addSlide();
  slide.background = { color: C.bg };
  slide.addShape(pptx.ShapeType.rect, {
    x: 0,
    y: 0,
    w: W,
    h: H,
    fill: { color: C.bg },
    line: { color: C.bg, transparency: 100 }
  });
  slide.addShape(pptx.ShapeType.rect, {
    x: 0,
    y: 0,
    w: W,
    h: 0.2,
    fill: { color: C.cyan },
    line: { color: C.cyan, transparency: 100 }
  });
  slide.addShape(pptx.ShapeType.rect, {
    x: 0,
    y: 0.2,
    w: W,
    h: 0.08,
    fill: { color: C.teal },
    line: { color: C.teal, transparency: 100 }
  });
  slide.addShape(pptx.ShapeType.arc, {
    x: 8.2,
    y: 0.34,
    w: 5.2,
    h: 5.2,
    adjustPoint: 0.25,
    line: { color: C.cyan, width: 2, transparency: 8 }
  });
  slide.addShape(pptx.ShapeType.arc, {
    x: 9.45,
    y: 1.18,
    w: 3.1,
    h: 3.1,
    adjustPoint: 0.25,
    line: { color: C.teal, width: 1.5, transparency: 20 }
  });
  slide.addText("SafeGuard", {
    x: 0.78,
    y: 1.3,
    w: 7.2,
    h: 0.72,
    fontSize: 46,
    bold: true,
    color: C.darkBlue,
    margin: 0
  });
  slide.addText("基于 Agent 的多模态 AI 反诈骗检测系统", {
    x: 0.83,
    y: 2.16,
    w: 7.1,
    h: 0.36,
    fontSize: 19,
    color: C.teal,
    margin: 0
  });
  slide.addText("DeepSeek-V4-Pro 作为中枢大脑，调度 Wav2Vec2 与 XceptionNet，结合 RAG 完成文本、音频、视频和模拟诈骗场景的可解释检测。", {
    x: 0.84,
    y: 3.12,
    w: 7.15,
    h: 0.9,
    fontSize: 15,
    color: "334155",
    fit: "shrink",
    margin: 0
  });
  slide.addText("汇报主线：中枢调度 → RAG 增强 → 模型工具化 → 小程序可视化演示。", {
    x: 0.84,
    y: 4.18,
    w: 7.55,
    h: 0.34,
    fontSize: 13,
    bold: true,
    color: C.ink,
    margin: 0
  });
  addPill(slide, "Agent 编排", 0.84, 4.82, 1.45, "FFFFFF", C.cyan);
  addPill(slide, "RAG 增强", 2.48, 4.82, 1.28, "FFFFFF", C.teal);
  addPill(slide, "模型训练", 3.95, 4.82, 1.3, "FFFFFF", C.amber);
  addPill(slide, "多模态检测", 5.44, 4.82, 1.55, "FFFFFF", C.violet);
  slide.addText("全栈开发：朱乘雨（后端 + AI 编排 + 前端）    模型训练：合作同学", {
    x: 0.84,
    y: 6.82,
    w: 8.2,
    h: 0.24,
    fontSize: 11.2,
    color: C.muted,
    margin: 0
  });
}

// 2. 项目架构
{
  const slide = pptx.addSlide();
  addTitle(slide, "项目架构：DeepSeek-V4-Pro 是中枢大脑", "小程序提交任务，后端编排，DeepSeek 调度专用模型并融合证据", 2);
  const cx = 6.05;
  const cy = 3.45;
  slide.addShape(pptx.ShapeType.ellipse, {
    x: cx - 1.05,
    y: cy - 1.05,
    w: 2.1,
    h: 2.1,
    fill: { color: C.darkBlue },
    line: { color: C.cyan, width: 2 }
  });
  slide.addText("DeepSeek\nV4-Pro", {
    x: cx - 0.7,
    y: cy - 0.28,
    w: 1.4,
    h: 0.48,
    fontSize: 16,
    bold: true,
    color: "FFFFFF",
    align: "center",
    valign: "middle",
    margin: 0
  });
  const nodes = [
    ["微信小程序", "输入、上传、进度、报告", 0.78, 2.25, C.cyan],
    ["Spring Boot 后端", "API、异步任务、数据封装", 3.02, 1.38, C.teal],
    ["RAG 知识库", "Qdrant + Embedding + Rerank", 8.38, 1.38, C.violet],
    ["Wav2Vec2", "音频伪造概率与置信度", 9.12, 3.45, C.amber],
    ["XceptionNet", "视频关键帧换脸风险", 7.98, 5.38, C.red],
    ["MySQL / 管理后台", "记录、模型、知识闭环", 2.25, 5.38, C.green]
  ];
  nodes.forEach(([title, body, x, y, color]) => {
    addCard(slide, x, y, 2.55, 0.96, title, body, color);
  });
  addArrow(slide, 3.33, 2.75, 1.45, C.cyan);
  addArrow(slide, 5.02, 2.15, -1.55, C.teal);
  addArrow(slide, 7.08, 2.18, 1.05, C.violet);
  addArrow(slide, 7.08, 3.75, 1.78, C.amber);
  addArrow(slide, 6.95, 4.45, 0.9, C.red);
  addArrow(slide, 4.88, 4.62, -1.35, C.green);
  slide.addText("架构关键点：DeepSeek 不是旁路解释器，而是中枢调度者；所有检测入口最终回到后端统一封装，专用模型输出证据，大模型负责组织结论。", {
    x: 1.05,
    y: 6.42,
    w: 11.05,
    h: 0.3,
    fontSize: 12,
    bold: true,
    color: C.ink,
    align: "center",
    margin: 0
  });
}

// 3. DeepSeek 中枢职责
{
  const slide = pptx.addSlide();
  addTitle(slide, "DeepSeek 中枢职责", "文本检测、智能问答、媒体报告、模拟诈骗和多模态综合研判统一由大模型承接", 3);
  const cards = [
    ["文本检测", "直接分析输入文本或上传文档，输出风险等级、诈骗类型、可疑点和处置建议；大模型负责语义判断，不再由后端写死结论。", C.cyan],
    ["AI 智能问答", "结合 RAG 检索结果，以 SSE 流式方式输出自然中文回答；首屏先展示增量内容，减少现场等待感。", C.teal],
    ["音频报告", "接收 Wav2Vec2 的概率、置信度、采样率、时长与设备信息，生成贴合样本的差异化解释。", C.amber],
    ["视频报告", "接收 XceptionNet 逐帧概率、聚合指标和 AIGC 元数据，区分视觉模型风险与综合证据风险。", C.red],
    ["模拟诈骗", "根据诈骗场景、历史对话和知识库，生成真实感对话与防范建议，用于课堂互动演示。", C.violet],
    ["多模态研判", "融合文本、音频、视频证据，给出统一风险判断；适合现场展示 Agent 作为中枢大脑的价值。", C.green]
  ];
  cards.forEach((c, i) => {
    const x = 0.78 + (i % 3) * 4.15;
    const y = 1.62 + Math.floor(i / 3) * 2.0;
    addCard(slide, x, y, 3.55, 1.62, c[0], c[1], c[2]);
  });
  slide.addText("设计思想：专用模型负责“识别证据”，DeepSeek 负责“理解任务、调度工具、组织证据、解释结论”，避免各模块各说各话。", {
    x: 1.08,
    y: 6.12,
    w: 11.15,
    h: 0.38,
    fontSize: 13.5,
    bold: true,
    color: C.darkBlue,
    align: "center",
    margin: 0
  });
}

// 4. RAG 流程
{
  const slide = pptx.addSlide();
  addTitle(slide, "RAG 流程：让回答贴近反诈知识库", "规则过滤、查询改写、向量检索、重排、去重，再注入 DeepSeek Prompt", 4);
  const steps = [
    ["规则过滤", "匹配刷单、公检法、安全账户、验证码、转账等高危关键词。"],
    ["查询改写", "扩展短问题，提升语义召回效果。"],
    ["向量检索", "Embedding 后到 Qdrant 检索候选知识；不可用时内存回退。"],
    ["Rerank", "使用 SiliconFlow rerank 模型重新排序候选片段。"],
    ["多因子融合", "综合 rerank、vector score、keyword score 得到最终分数。"],
    ["Prompt 注入", "formatRagContext 生成参考知识，交给 DeepSeek 生成回答。"]
  ];
  steps.forEach((s, i) => {
    const x = 0.82 + i * 2.02;
    slide.addShape(pptx.ShapeType.rect, {
      x,
      y: 2.1,
      w: 1.55,
      h: 1.85,
      fill: { color: i % 2 === 0 ? C.softCyan : C.softTeal },
      line: { color: "BAE6FD", width: 1 }
    });
    slide.addText(String(i + 1), {
      x: x + 0.5,
      y: 2.3,
      w: 0.55,
      h: 0.4,
      fontSize: 24,
      bold: true,
      color: i % 2 === 0 ? C.cyan : C.teal,
      align: "center",
      margin: 0
    });
    slide.addText(s[0], {
      x: x + 0.12,
      y: 2.94,
      w: 1.31,
      h: 0.2,
      fontSize: 10.5,
      bold: true,
      color: C.ink,
      align: "center",
      margin: 0
    });
    slide.addText(s[1], {
      x: x + 0.14,
      y: 3.28,
      w: 1.27,
      h: 0.46,
      fontSize: 8.1,
      color: "475569",
      fit: "shrink",
      align: "center",
      margin: 0
    });
    if (i < steps.length - 1) {
      addArrow(slide, x + 1.62, 3.02, 0.32, C.muted);
    }
  });
  addCard(slide, 1.05, 5.05, 5.35, 1.18, "代码依据", "RAGService.query：RuleFilter → QueryRewriter → HybridChunker → Embedding/Qdrant → rerank → embeddingDeduplicate → Top-K。缓存和内存回退保证现场演示稳定性。", C.violet);
  addCard(slide, 6.92, 5.05, 5.35, 1.18, "使用位置", "AI 助手、文本检测、模拟诈骗、多模态分析都会把相关知识注入 DeepSeek Prompt，让回答更贴近反诈业务知识。", C.green);
}

// 5. Agent 编排
{
  const slide = pptx.addSlide();
  addTitle(slide, "Agent 编排流程", "AgentOrchestrator 将一次检测拆成依赖解析、Agent 执行、结果聚合三段", 5);
  addStep(slide, 1, "依赖解析：RAG + CoT + ReAct 并行", "CompletableFuture 并行执行知识检索、分步推理和工具调用思考；不是串行等待，能兼顾质量与响应速度。", 1.0, 1.65, 4.6, C.cyan);
  addStep(slide, 2, "Agent 执行：按任务选择能力", "文本检测使用 TEXT_ANALYSIS 与 KNOWLEDGE；音视频检测把 Python 模型服务包装成可调用工具。", 1.0, 2.82, 4.6, C.teal);
  addStep(slide, 3, "结果聚合：生成综合报告", "mergeResults 汇总参考知识、CoT 评估、Agent 输出和 ReAct 过程，并生成前端能直接展示的中文报告。", 1.0, 3.99, 4.6, C.violet);
  slide.addShape(pptx.ShapeType.rect, {
    x: 7.08,
    y: 1.48,
    w: 4.9,
    h: 3.84,
    fill: { color: "FFFFFF" },
    line: { color: "E2E8F0", width: 1 }
  });
  slide.addText("OrchestratorResponse", {
    x: 7.42,
    y: 1.82,
    w: 4.2,
    h: 0.3,
    fontSize: 16,
    bold: true,
    color: C.darkBlue,
    align: "center",
    margin: 0
  });
  const fields = ["finalResult", "agentResults", "ragContext", "cotResult", "reactThoughts", "reasoningSummary"];
  fields.forEach((f, i) => {
    addPill(slide, f, 7.55 + (i % 2) * 2.0, 2.42 + Math.floor(i / 2) * 0.72, 1.58, i % 2 === 0 ? C.cyan : C.teal, i % 2 === 0 ? C.softCyan : C.softTeal);
  });
  slide.addText("这些结构化字段支撑小程序中的“AI 分析过程”“知识来源”“处理进度”和最终中文报告；也方便后续接入检测历史。", {
    x: 7.45,
    y: 4.76,
    w: 4.25,
    h: 0.32,
    fontSize: 10,
    color: "475569",
    align: "center",
    margin: 0
  });
}

// 6. 文本检测与流式问答
{
  const slide = pptx.addSlide();
  addTitle(slide, "文本检测与 AI 助手：DeepSeek 语义分析", "文档上传、RAG 增强、结构化解析、SSE 流式中文输出", 6);
  addCard(slide, 0.82, 1.58, 3.55, 3.9, "文本检测", "detectText / detectTextDocument 将输入框文本或上传文档封装为 OrchestratorRequest，开启 RAG、CoT、ReAct，由 DeepSeek 输出风险等级、诈骗类型、可疑点和建议。", C.cyan);
  addCard(slide, 4.9, 1.58, 3.55, 3.9, "结果清洗", "buildTextAgentPayload 与 buildTextDetectionReport 把模型结果转为中文报告，避免小程序直接展示 JSON、Markdown 包裹或英文键名，同时保留风险字段。", C.teal);
  addCard(slide, 8.98, 1.58, 3.55, 3.9, "流式问答", "knowledge.js 先调用 RAG，再通过 requestStream 接收 /api/llm/analyze/stream，约 120ms 节流刷新；失败时回退普通接口，保证演示可用。", C.violet);
  slide.addText("前端体验重点：文本检测支持手输和文档上传；AI 助手优先流式输出，用户看到的是连续中文，而不是中间 JSON。", {
    x: 1.08,
    y: 6.2,
    w: 11.15,
    h: 0.3,
    fontSize: 12.5,
    bold: true,
    color: C.ink,
    align: "center",
    margin: 0
  });
}

// 7. 音频/视频工具调度
{
  const slide = pptx.addSlide();
  addTitle(slide, "音频与视频检测：专用模型作为 Agent 工具", "Wav2Vec2 与 XceptionNet 输出证据，DeepSeek 生成可解释报告", 7);
  addCard(slide, 0.95, 1.58, 5.35, 3.86, "音频检测：Wav2Vec2", "Python 服务输出 label、伪造概率、真实概率、置信度、模型版本、设备、推理耗时、采样率、声道数、时长与截断状态。DeepSeek 根据这些真实字段生成贴合文件本身的报告，并支持批量检测场景。", C.amber);
  addCard(slide, 7.02, 1.58, 5.35, 3.86, "视频检测：XceptionNet", "后端抽取关键帧，做人脸裁剪或整帧降级检测；模型返回逐帧伪造概率。DeepSeek 综合平均概率、最高帧、可疑帧占比和 AIGC 元数据，说明哪些帧更可疑、为什么最终风险更高。", C.red);
  slide.addShape(pptx.ShapeType.rect, {
    x: 3.72,
    y: 5.86,
    w: 5.9,
    h: 0.56,
    fill: { color: C.darkBlue },
    line: { color: C.darkBlue, transparency: 100 }
  });
  slide.addText("重点口径：XceptionNet 聚焦换脸/面部篡改，不等同于通用视频大模型。", {
    x: 3.92,
    y: 6.03,
    w: 5.5,
    h: 0.16,
    fontSize: 11,
    bold: true,
    color: "FFFFFF",
    align: "center",
    margin: 0
  });
}

// 8. Python 模型训练与服务化
{
  const slide = pptx.addSlide();
  addTitle(slide, "Python 端：训练模型并服务化给 Agent 调用", "音频和视频不是写死结果，而是训练模型输出中间证据，再交给 DeepSeek 解释", 8);
  addCard(slide, 0.78, 1.48, 3.75, 4.08, "音频训练", "train_wav2vec2.py 读取 ASVspoof 协议文件，完成 bonafide/spoof 标签映射、16kHz 重采样、最大时长裁剪、类别权重、FP16、梯度累积、warmup、F1/accuracy 指标和最佳模型保存。训练阶段重点保证分类能力。", C.amber);
  addCard(slide, 4.78, 1.48, 3.75, 4.08, "音频服务", "utils.py 的 load_model_once 负责模型缓存，predict_audio 输出 label、伪造概率、真实概率、置信度、采样率、时长、截断状态、设备和耗时，供 DeepSeek 生成差异化报告。服务阶段重点保证稳定推理。", C.teal);
  addCard(slide, 8.78, 1.48, 3.75, 4.08, "视频训练与服务", "train.py 使用 XceptionNet 二分类模型，支持 CUDA、混合精度、梯度累积、断点恢复和 best_model.pth 保存；服务端对关键帧做人脸/整帧检测，输出逐帧概率。GPU 优先用于提高检测速度。", C.red);
  slide.addText("汇报口径：模型训练部分说明“证据从哪里来”，Agent 部分说明“证据如何被调度和解释”。", {
    x: 1.08,
    y: 6.16,
    w: 11.1,
    h: 0.28,
    fontSize: 12.2,
    bold: true,
    color: C.ink,
    align: "center",
    margin: 0
  });
}

// 9. 前端适配
{
  const slide = pptx.addSlide();
  addTitle(slide, "前端适配：小程序为主，Web 管理端辅助", "微信小程序承载现场演示；Web 管理端展示记录、模型和知识库闭环", 9);
  const items = [
    ["微信小程序检测页", "上传文本/文档/音频/视频，展示 Agent 状态、进度卡片、风险概率、可疑点和中文报告；核心功能围绕现场演示设计。", C.cyan],
    ["流式输出适配", "knowledge.js + requestStream 使用 enableChunked 接收 SSE，并做约 120ms 节流刷新，让 AI 助手先输出可读中文。", C.teal],
    ["异步任务适配", "TaskWatcher 优先 SSE，失败回退轮询，支撑视频检测长任务的进度展示，也能同步检测历史。", C.violet],
    ["Web 管理端", "Vue 3 + Element Plus + ECharts，用于检测记录、模型信息、知识库和统计展示；课堂汇报中作为辅助闭环说明。", C.green]
  ];
  items.forEach((item, i) => {
    const x = 1.0 + (i % 2) * 5.95;
    const y = 1.58 + Math.floor(i / 2) * 2.12;
    addCard(slide, x, y, 5.18, 1.68, item[0], item[1], item[2]);
  });
  slide.addText("核心代码：detection.js / detection.wxml / knowledge.js / simulate.js / request.js / taskWatcher.js", {
    x: 1.05,
    y: 6.16,
    w: 11.1,
    h: 0.28,
    fontSize: 11,
    color: C.muted,
    align: "center",
    margin: 0
  });
}

// 10. 职责范围与分工
{
  const slide = pptx.addSlide();
  addTitle(slide, "职责范围与设计重点", "本人全栈开发，模型训练由合作同学完成", 10);
  const rows = [
    ["朱乘雨", "全栈开发", "Spring Boot、AgentOrchestrator、LLMService、RAG 调用、音视频调度、异步任务、检测历史与统一结果封装；小程序检测页、AI 助手、模拟诈骗、SSE 流式输出、TaskWatcher、动态进度卡片、管理后台"],
    ["合作同学", "模型训练", "Wav2Vec2 / XceptionNet 训练与权重产出；本人负责模型接入、Python 推理服务封装、GPU 推理与调度集成"]
  ];
  const x0 = 0.78;
  const y0 = 1.52;
  const col = [1.35, 1.5, 8.9];
  const rowH = 0.94;
  slide.addShape(pptx.ShapeType.rect, {
    x: x0,
    y: y0,
    w: 11.75,
    h: 0.5,
    fill: { color: C.darkBlue },
    line: { color: C.darkBlue, transparency: 100 }
  });
  ["成员", "方向", "核心工作"].forEach((t, i) => {
    slide.addText(t, {
      x: x0 + col.slice(0, i).reduce((a, b) => a + b, 0) + 0.12,
      y: y0 + 0.15,
      w: col[i] - 0.2,
      h: 0.14,
      fontSize: 11.5,
      bold: true,
      color: "FFFFFF",
      margin: 0
    });
  });
  rows.forEach((r, i) => {
    const y = y0 + 0.5 + i * rowH;
    const fill = i % 2 === 0 ? "FFFFFF" : "F1F5F9";
    slide.addShape(pptx.ShapeType.rect, {
      x: x0,
      y,
      w: 11.75,
      h: rowH,
      fill: { color: fill },
      line: { color: "CBD5E1", width: 0.7 }
    });
    slide.addText(r[0], {
      x: x0 + 0.12,
      y: y + 0.28,
      w: col[0] - 0.2,
      h: 0.18,
      fontSize: 11.2,
      bold: true,
      color: C.ink,
      margin: 0
    });
    slide.addText(r[1], {
      x: x0 + col[0] + 0.12,
      y: y + 0.28,
      w: col[1] - 0.2,
      h: 0.18,
      fontSize: 11.2,
      bold: true,
      color: C.teal,
      margin: 0
    });
    slide.addText(r[2], {
      x: x0 + col[0] + col[1] + 0.12,
      y: y + 0.18,
      w: col[2] - 0.28,
      h: 0.42,
      fontSize: 10.6,
      color: "334155",
      fit: "shrink",
      margin: 0
    });
  });
  slide.addText("每个成员对应一条可演示链路：前端入口 → 后端 Agent 编排 → 专用模型/知识库 → 中文报告与历史记录。", {
    x: 1.0,
    y: 6.32,
    w: 11.2,
    h: 0.3,
    fontSize: 11.5,
    bold: true,
    color: C.ink,
    align: "center",
    margin: 0
  });
}

// 11. 总结
{
  const slide = pptx.addSlide();
  slide.background = { color: C.bg };
  slide.addShape(pptx.ShapeType.rect, {
    x: 0,
    y: 0,
    w: W,
    h: 0.22,
    fill: { color: C.teal },
    line: { color: C.teal, transparency: 100 }
  });
  slide.addText("总结", {
    x: 0.78,
    y: 0.82,
    w: 3.5,
    h: 0.62,
    fontSize: 38,
    bold: true,
    color: C.darkBlue,
    margin: 0
  });
  slide.addText("SafeGuard 的汇报重点应聚焦 Agent 应用开发：DeepSeek 如何调度工具，RAG 如何增强判断，前端如何适配流式输出和异步进度，专用模型如何作为可解释工具参与反诈检测。", {
    x: 0.83,
    y: 1.72,
    w: 9.6,
    h: 0.86,
    fontSize: 16,
    color: "334155",
    fit: "shrink",
    margin: 0
  });
  const summary = [
    ["中枢", "DeepSeek-V4-Pro 负责文本、问答、模拟诈骗和综合研判，是系统的统一决策与解释入口。"],
    ["工具", "Python 训练的 Wav2Vec2 与 XceptionNet 输出检测证据，后端将其封装为 Agent 可调用能力。"],
    ["知识", "RAG 提供反诈知识召回、重排和 Prompt 增强，让回答更符合诈骗识别业务语境。"],
    ["体验", "小程序负责主演示，Web 端辅助展示数据管理闭环，流式输出和进度卡片降低等待感。"]
  ];
  summary.forEach((s, i) => {
    const x = 0.95 + (i % 2) * 5.55;
    const y = 3.05 + Math.floor(i / 2) * 1.15;
    slide.addShape(pptx.ShapeType.rect, {
      x,
      y,
      w: 4.85,
      h: 0.92,
      fill: { color: "FFFFFF" },
      line: { color: i % 2 === 0 ? "BAE6FD" : "99F6E4", width: 1 }
    });
    slide.addShape(pptx.ShapeType.rect, {
      x,
      y,
      w: 0.1,
      h: 0.92,
      fill: { color: i % 2 === 0 ? C.cyan : C.teal },
      line: { color: i % 2 === 0 ? C.cyan : C.teal, transparency: 100 }
    });
    slide.addText(s[0], {
      x: x + 0.22,
      y: y + 0.19,
      w: 0.72,
      h: 0.18,
      fontSize: 12.5,
      bold: true,
      color: C.darkBlue,
      margin: 0
    });
    slide.addText(s[1], {
      x: x + 1.02,
      y: y + 0.14,
      w: 3.5,
      h: 0.42,
      fontSize: 10.5,
      color: "334155",
      fit: "shrink",
      margin: 0
    });
  });
  slide.addShape(pptx.ShapeType.rect, {
    x: 0.92,
    y: 6.2,
    w: 11.45,
    h: 0.58,
    fill: { color: C.softCyan },
    line: { color: "BAE6FD", width: 1 }
  });
  slide.addText("建议演示顺序：AI 助手 → 文本检测 → 音频检测 → 视频检测 → 模拟诈骗 → 检测历史 / 后台。结尾强调：本项目不是单点模型展示，而是围绕 DeepSeek 中枢把知识、工具、数据和前端体验串成可演示的 Agent 应用。", {
    x: 1.1,
    y: 6.33,
    w: 11.05,
    h: 0.26,
    fontSize: 11.2,
    color: C.darkBlue,
    fit: "shrink",
    margin: 0
  });
}

(async () => {
  await pptx.writeFile({ fileName: OUT });
})();
