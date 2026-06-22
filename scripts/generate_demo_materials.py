from pathlib import Path
import wave
import math
import struct
import textwrap
import subprocess

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "demo_materials"
TEXT_DIR = OUT_DIR / "text"
AUDIO_DIR = OUT_DIR / "audio"
VIDEO_DIR = OUT_DIR / "video"


TEXT_CASES = {
    "01_冒充客服退款_长文本.txt": """
【场景】冒充电商平台客服退款诈骗

您好，这里是某电商平台客服中心。系统刚刚排查到，您在上周购买的“居家净水器延保服务”存在重复投保记录。如果今天不及时取消，系统会在今晚23:30自动续费，每月扣款 698 元，并且会连续扣费 12 个月。因为这项服务是和您的支付账户绑定的，所以普通用户在订单页面里看不到取消入口。

现在我可以帮您走“快速取消通道”，但需要您本人配合验证身份。接下来我会发给您一个官方处理链接，打开后按照页面提示填写姓名、手机号和常用银行卡。提交后页面会弹出一个验证码，您把验证码念给我，我这边才能帮您把自动续费关闭。整个流程大概三到五分钟，今天之内处理都有效，但如果超过今晚，系统一旦批量扣款，后续就只能走财务审批，退款至少要等十五个工作日。

如果您担心安全问题，也可以现在先去应用商店搜索我们平台的官方客服，不过由于今天取消的人很多，线上排队时间普遍超过两小时。为了不影响您今晚的扣费节点，建议您直接按我这边的指导操作。请注意，接下来如果有任何来自银行或支付平台的短信验证码，都是用于关闭自动续费，并不是消费扣款，请您不要紧张，按顺序告诉我就可以了。
""".strip(),
    "02_刷单返利骗局_长文本.txt": """
【场景】刷单返利诈骗

您好，我们是某短视频平台的品牌推广合作方，最近在做“商家热度冲榜计划”。现在招募一批线上兼职人员，主要工作就是帮店铺做浏览、收藏和下单冲量，任务非常简单，不需要押金，不需要坐班，手机就能做。新手前两单是福利单，每单佣金 8 到 20 元，完成后五分钟内返本返佣。

为了让您放心，我们会先安排一笔小单。您只需要按照任务群里发的商品链接下单一件 38 元的小商品，付款后把截图发到群里，财务就会立刻把 38 元本金和 10 元佣金一起返给您。等您完成两到三单之后，系统会自动升级到“联单任务”，那时收益更高，单次佣金可以达到 188 元到 588 元。

需要提醒您的是，联单任务中途不能单独退出，因为商家冲量是成组结算的。如果您做到一半停下来，系统会判定任务异常，前面的本金和佣金都会被冻结。很多新人就是因为不理解这个规则，明明前面都赚到了，最后却在关键时候犹豫。只要您按要求做完，所有垫付资金都会和佣金一起返还。现在名额不多，想做的话我先给您拉进任务群，您看可以吗？
""".strip(),
    "03_AI换脸视频诈骗话术.txt": """
【场景】AI换脸视频通话诈骗

喂，是我，你先别挂。我现在在外地处理一个很急的项目，刚刚签合同的时候公司账户出了点问题，对方财务要求今天下午四点前先把保证金打过去，不然这个单子就没了。因为我现在在会议室，不方便一直说话，你看我这边信号也不太稳定，所以我长话短说。

你先帮我转 48000 元到这个临时账户，等我今晚回去之后立刻还你。你要是担心，可以先看我视频，我现在真的是本人。你不用再给我打电话确认了，我这边几个领导都在旁边，真的不方便。这个事情特别急，对方一直在催，晚一分钟都可能出问题。你先把钱转了，然后把转账截图发给我，我马上让财务给你补回去。

另外，这个账户不是我常用的，是项目合作方指定的监管账户，你不要备注“借款”之类的内容，直接写“材料费”就行。转完以后删除聊天记录，避免项目资料泄露。你别多想，这就是公司流程的问题，先帮我把这次应急处理过去。
""".strip(),
}


VOICE_SCRIPT = """
您好，这里是某电商平台客户服务中心。系统刚刚检测到，您名下账户开通了一项会员保障续费服务。

如果今天不取消，今晚二十三点三十分将自动扣费，每月六百九十八元，连续扣费十二个月。由于该服务与您的支付账户绑定，普通订单页面无法直接关闭。

现在可以为您开通紧急取消通道，请您准备好手机。稍后会收到一个验证短信，这不是扣款，而是关闭自动续费的身份校验。为了避免错过截止时间，请您在收到验证码后，按照语音提示完成验证。

如果您对本次通知有疑问，也可以选择前往官方客服页面排队处理，但当前人工咨询量较大，预计等待时间在两小时以上。为了不影响您的扣费节点，建议您现在优先办理取消。

请注意，工作人员不会向您索要密码，但可能需要您配合提供验证码或进入屏幕共享页面，以便完成远程关闭服务。请保持电话畅通，按照提示一步一步操作。
""".strip()


VIDEO_SCENES = [
    {
        "name": "01_冒充熟人视频借款_长视频.mp4",
        "title": "视频通话片段 01",
        "subtitle": "熟人突然视频联系，要求紧急转账 48000 元",
        "dialog_lines": [
            "先别挂，我这边在开会，时间特别紧。",
            "项目保证金今天下午四点前必须到账，不然合同就废了。",
            "你先帮我垫 48000，我晚上回去第一时间还你。",
            "不要给我原来的卡转，这是合作方临时监管账户。",
            "你先截图发我，别备注借款，写材料费就行。",
            "我这边信号不稳，别再打语音确认，直接转。"
        ],
        "accent": (36, 96, 168),
    },
    {
        "name": "02_冒充客服视频指导操作_长视频.mp4",
        "title": "视频通话片段 02",
        "subtitle": "客服引导关闭续费，要求共享屏幕和验证码",
        "dialog_lines": [
            "您现在打开我发给您的链接，进入快速取消入口。",
            "页面上看到的验证码不是扣款，是关闭服务验证。",
            "如果页面弹出银行保护提醒，您点继续办理就可以。",
            "接下来请打开屏幕共享，不然我这边无法帮您核对。",
            "稍后会跳转到支付页面，这是取消协议校验，不会真实消费。",
            "请按顺序操作，不要退出，否则系统会默认您放弃取消。"
        ],
        "accent": (0, 132, 108),
    },
]


def ensure_dirs():
    for d in [TEXT_DIR, AUDIO_DIR, VIDEO_DIR]:
        d.mkdir(parents=True, exist_ok=True)


def write_text_materials():
    for name, content in TEXT_CASES.items():
        (TEXT_DIR / name).write_text(content + "\n", encoding="utf-8")


def write_readme():
    content = """
# 演示素材包

本目录用于微信小程序和 Web 端现场演示，素材偏向真实诈骗场景，长度足够讲解，不是接口冒烟测试数据。

## 目录说明

- `text/`：可直接复制到 AI 问答、知识库检索、文本分析场景的长文本话术
- `audio/`：可上传到音频检测的较长语音文件
- `video/`：可上传到视频检测的较长 MP4 演示文件

## 推荐演示顺序

1. 文本场景：先把 `text/` 中的长文本复制到 AI 问答或诈骗分析页面
2. 音频场景：上传 `audio/01_冒充客服退款电话_长音频.wav`
3. 视频场景：优先上传 `video/03_可检测人脸视频通话_长视频.mp4`
4. 需要展示带字幕的视频通话画面时，可播放 `video/01_冒充熟人视频借款_长视频.mp4` 或 `video/02_冒充客服视频指导操作_长视频.mp4`

## 说明

- 这些素材用于课程演示，不代表真实用户数据
- 音频由本机语音合成生成，视频由本地脚本生成，便于重复分发和重新生成
- `03/04` 两个视频由项目自带短样例循环扩展而来，更适合上传检测；`01/02` 两个视频更适合现场讲解诈骗话术
- 若需要更长版本，可重新运行 `scripts/generate_demo_materials.py`
""".strip()
    (OUT_DIR / "README.md").write_text(content + "\n", encoding="utf-8")


def synthesize_speech_windows(text: str, out_path: Path):
    escaped_text = text.replace("'", "''")
    escaped_path = str(out_path).replace("'", "''")
    cmd = [
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-Command",
        (
            "Add-Type -AssemblyName System.Speech; "
            "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
            "$voices = $s.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name }; "
            "if ($voices -contains 'Microsoft Yaoyao') { $s.SelectVoice('Microsoft Yaoyao') } "
            "elseif ($voices -contains 'Microsoft Huihui') { $s.SelectVoice('Microsoft Huihui') } "
            "elseif ($voices -contains 'Microsoft Huihui Desktop') { $s.SelectVoice('Microsoft Huihui Desktop') } "
            "elseif ($voices -contains 'Microsoft Kangkang') { $s.SelectVoice('Microsoft Kangkang') } "
            "else { $s.SelectVoice($voices[0]) }; "
            "$s.Rate = -2; "
            f"$s.SetOutputToWaveFile('{escaped_path}'); "
            f"$s.Speak('{escaped_text}'); "
            "$s.Dispose();"
        ),
    ]
    subprocess.run(cmd, check=True)


def generate_beep_audio(out_path: Path, seconds: int = 75):
    sample_rate = 16000
    frames = []
    for i in range(sample_rate * seconds):
        t = i / sample_rate
        carrier = 0.25 * math.sin(2 * math.pi * 220 * t)
        tremolo = 0.08 * math.sin(2 * math.pi * 2 * t)
        noise = 0.02 * math.sin(2 * math.pi * 511 * t)
        value = max(-0.95, min(0.95, carrier + tremolo + noise))
        frames.append(struct.pack("<h", int(value * 32767)))
    with wave.open(str(out_path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(b"".join(frames))


def generate_audio_materials():
    synthesize_speech_windows(VOICE_SCRIPT, AUDIO_DIR / "01_冒充客服退款电话_长音频.wav")
    generate_beep_audio(AUDIO_DIR / "02_可疑变声来电_长音频.wav", seconds=78)


def draw_wrapped_text(img, text, origin, width, line_height, color, scale=0.7, thickness=2):
    x, y = origin
    lines = textwrap.wrap(text, width=width)
    for line in lines:
        cv2.putText(img, line, (x, y), cv2.FONT_HERSHEY_SIMPLEX, scale, color, thickness, cv2.LINE_AA)
        y += line_height
    return y


def get_chinese_font(size: int):
    candidates = [
        Path("C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/simhei.ttf"),
        Path("C:/Windows/Fonts/simsun.ttc"),
    ]
    for path in candidates:
        if path.exists():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def draw_chinese_block(img, text, box, font_size, fill, line_spacing=10):
    pil_img = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(pil_img)
    font = get_chinese_font(font_size)

    left, top, right, bottom = box
    max_width = right - left
    lines = []
    current = ""
    for ch in text:
        if ch == "\n":
            lines.append(current)
            current = ""
            continue
        test = current + ch
        bbox = draw.textbbox((0, 0), test, font=font)
        if bbox[2] - bbox[0] <= max_width:
            current = test
        else:
            if current:
                lines.append(current)
            current = ch
    if current:
        lines.append(current)

    y = top
    for line in lines:
        draw.text((left, y), line, font=font, fill=fill)
        bbox = draw.textbbox((left, y), line, font=font)
        y += (bbox[3] - bbox[1]) + line_spacing
        if y > bottom:
            break

    return cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)


def generate_video_case(out_path: Path, title: str, subtitle: str, dialog_lines, accent):
    width, height = 1280, 720
    fps = 24
    section_seconds = 3.4
    frames_per_section = int(section_seconds * fps)
    total_frames = frames_per_section * len(dialog_lines)
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(out_path), fourcc, fps, (width, height))

    for idx in range(total_frames):
        section = min(len(dialog_lines) - 1, idx // frames_per_section)
        img = np.zeros((height, width, 3), dtype=np.uint8)
        img[:] = (242, 246, 250)

        cv2.rectangle(img, (0, 0), (width, 88), accent, -1)
        cv2.putText(img, title, (48, 56), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (255, 255, 255), 3, cv2.LINE_AA)

        cv2.rectangle(img, (56, 120), (744, 610), (255, 255, 255), -1)
        cv2.rectangle(img, (56, 120), (744, 610), (214, 220, 228), 2)

        # 模拟视频画面
        pulse = 10 * math.sin(idx / 12.0)
        center_x, center_y = 400, 314
        cv2.circle(img, (center_x, center_y - 72), 70, (196, 206, 220), -1)
        cv2.ellipse(img, (center_x, center_y + 78), (112, 138), 0, 0, 360, (196, 206, 220), -1)
        cv2.circle(img, (center_x + int(pulse), center_y - 72), 52, accent, -1)
        cv2.ellipse(img, (center_x, center_y + 86), (84, 104), 0, 0, 360, accent, -1)
        cv2.putText(img, "LIVE", (610, 160), cv2.FONT_HERSHEY_SIMPLEX, 0.85, (40, 72, 120), 2, cv2.LINE_AA)

        # 信息侧栏
        cv2.rectangle(img, (786, 120), (1228, 610), (255, 255, 255), -1)
        cv2.rectangle(img, (786, 120), (1228, 610), (214, 220, 228), 2)
        img = draw_chinese_block(img, "场景说明", (826, 146, 1160, 190), 30, (38, 50, 56))
        img = draw_chinese_block(img, subtitle, (826, 206, 1196, 300), 26, (76, 92, 104))

        img = draw_chinese_block(img, "当前话术", (826, 318, 1160, 352), 30, (38, 50, 56))
        img = draw_chinese_block(img, dialog_lines[section], (826, 362, 1196, 520), 28, accent)

        progress = (idx + 1) / total_frames
        cv2.rectangle(img, (826, 560), (1178, 584), (230, 235, 240), -1)
        cv2.rectangle(img, (826, 560), (826 + int(352 * progress), 584), accent, -1)
        cv2.putText(img, f"演示进度 {int(progress * 100)}%", (826, 538), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (92, 104, 112), 2, cv2.LINE_AA)

        writer.write(img)

    writer.release()


def generate_video_materials():
    for scene in VIDEO_SCENES:
        generate_video_case(
            VIDEO_DIR / scene["name"],
            scene["title"],
            scene["subtitle"],
            scene["dialog_lines"],
            scene["accent"],
        )
    generate_looped_video_materials()


def loop_video(source: Path, target: Path, seconds: int = 24):
    if not source.exists():
        return

    cap = cv2.VideoCapture(str(source))
    fps = cap.get(cv2.CAP_PROP_FPS) or 24
    frames = []
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            frames.append(frame)
    finally:
        cap.release()

    if not frames:
        return

    height, width = frames[0].shape[:2]
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(target), fourcc, fps, (width, height))
    total = int(seconds * fps)
    try:
        for i in range(total):
            frame = frames[i % len(frames)].copy()
            writer.write(frame)
    finally:
        writer.release()


def generate_looped_video_materials():
    source_dir = ROOT / "test_data" / "video"
    loop_video(
        source_dir / "sample_real_face.mp4",
        VIDEO_DIR / "03_可检测人脸视频通话_长视频.mp4",
        seconds=24,
    )
    loop_video(
        source_dir / "sample_fake_face.mp4",
        VIDEO_DIR / "04_可检测人脸对照素材_长视频.mp4",
        seconds=24,
    )


def main():
    ensure_dirs()
    write_text_materials()
    write_readme()
    generate_audio_materials()
    generate_video_materials()
    print(f"演示素材已生成: {OUT_DIR}")


if __name__ == "__main__":
    main()
