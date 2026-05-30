"""
DeepFake 检测 API 测试脚本（集成测试）

说明：
- 这是用于人工/集成联调的脚本，不是 pytest 单元测试文件。
- 运行前请先启动服务：python api/app.py

用法：
  python scripts/api_test.py
  python scripts/api_test.py --base_url http://localhost:5002
"""

import argparse
import os
import sys
import json
import time
import requests
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser(description="DeepFake 检测 API 集成测试脚本")
    parser.add_argument(
        "--base_url",
        type=str,
        default=os.environ.get("VIDEO_BASE_URL", "http://localhost:5002"),
        help="服务地址，例如 http://localhost:5002",
    )
    parser.add_argument(
        "--data_dir",
        type=str,
        default="data",
        help="数据目录（用于自动查找测试图片）",
    )
    parser.add_argument(
        "--test_video",
        type=str,
        default=None,
        help="可选：测试视频路径（mp4）",
    )
    return parser.parse_args()


args = parse_args()
BASE_URL = args.base_url.rstrip("/")
DATA_DIR = args.data_dir
TEST_VIDEO = args.test_video

# 测试图像路径（优先使用数据集中的图片）
TEST_IMAGE_REAL = None  # 会自动查找
TEST_IMAGE_FAKE = None


# ========== 自动查找测试图片 ==========
def find_test_images():
    """从数据集中自动查找真实和伪造测试图片"""
    global TEST_IMAGE_REAL, TEST_IMAGE_FAKE

    for split in ["test", "valid", "train"]:
        for label, var in [("real", "TEST_IMAGE_REAL"), ("fake", "TEST_IMAGE_FAKE")]:
            target_var = TEST_IMAGE_REAL if var == "TEST_IMAGE_REAL" else TEST_IMAGE_FAKE
            if target_var is not None:
                continue

            search_dir = Path(DATA_DIR) / split / label
            if not search_dir.exists():
                continue

            # 扁平结构
            for ext in ["*.png", "*.jpg", "*.jpeg"]:
                images = list(search_dir.glob(ext))
                if images:
                    if var == "TEST_IMAGE_REAL":
                        TEST_IMAGE_REAL = str(images[0])
                    else:
                        TEST_IMAGE_FAKE = str(images[0])
                    break

            # 嵌套结构
            if var == "TEST_IMAGE_REAL" and TEST_IMAGE_REAL is None:
                subdirs = [d for d in search_dir.iterdir() if d.is_dir()]
                if subdirs:
                    for ext in ["*.jpg", "*.png", "*.jpeg"]:
                        images = list(subdirs[0].glob(ext))
                        if images:
                            TEST_IMAGE_REAL = str(images[0])
                            break
            elif var == "TEST_IMAGE_FAKE" and TEST_IMAGE_FAKE is None:
                subdirs = [d for d in search_dir.iterdir() if d.is_dir()]
                if subdirs:
                    for ext in ["*.jpg", "*.png", "*.jpeg"]:
                        images = list(subdirs[0].glob(ext))
                        if images:
                            TEST_IMAGE_FAKE = str(images[0])
                            break


find_test_images()


# ========== 测试结果统计 ==========
class TestReport:
    def __init__(self):
        self.total = 0
        self.passed = 0
        self.failed = 0
        self.results = []

    def add(self, name, passed, message=""):
        self.total += 1
        if passed is True:
            self.passed += 1
            status = "PASS"
        elif passed is False:
            self.failed += 1
            status = "FAIL"
        else:
            # 允许 None：表示跳过
            status = "SKIP"
        self.results.append({"name": name, "status": status, "message": message})
        print(f"  [{status}] {name}")
        if message:
            print(f"         {message}")

    def summary(self):
        print("\n" + "=" * 60)
        print(f"  测试完成：{self.total} 项 | 通过：{self.passed} | 失败：{self.failed}")
        print("=" * 60)
        return self.failed == 0


report = TestReport()


# ========== 测试用例 ==========
def test_health():
    """测试 1：健康检查"""
    print("\n[TEST 1] 健康检查 /api/health")
    try:
        resp = requests.get(f"{BASE_URL}/api/health", timeout=5)
        data = resp.json()
        passed = (
            resp.status_code == 200
            and data.get("status") == "healthy"
            and data.get("model_loaded") is True
        )
        report.add("健康检查", passed, f"状态码={resp.status_code}, 响应={json.dumps(data, ensure_ascii=False)}")
    except requests.ConnectionError:
        report.add("健康检查", False, "无法连接服务器，请先启动：python api/app.py")
    except Exception as e:
        report.add("健康检查", False, str(e))


def test_image_detect_no_file():
    """测试 2：图片检测 - 无文件"""
    print("\n[TEST 2] 图片检测 - 缺少文件参数")
    try:
        resp = requests.post(f"{BASE_URL}/api/detect/image", timeout=5)
        data = resp.json()
        passed = resp.status_code == 400 and data.get("success") is False
        report.add("缺少文件参数", passed, f"状态码={resp.status_code}, 错误信息={data.get('error', '')}")
    except Exception as e:
        report.add("缺少文件参数", False, str(e))


def test_image_detect_empty_file():
    """测试 3：图片检测 - 空文件名"""
    print("\n[TEST 3] 图片检测 - 空文件名")
    try:
        files = {"file": ("", b"", "application/octet-stream")}
        resp = requests.post(f"{BASE_URL}/api/detect/image", files=files, timeout=5)
        data = resp.json()
        passed = resp.status_code == 400 and data.get("success") is False
        report.add("空文件名", passed, f"状态码={resp.status_code}, 错误信息={data.get('error', '')}")
    except Exception as e:
        report.add("空文件名", False, str(e))


def test_image_detect_no_face():
    """测试 4：图片检测 - 无人脸图片"""
    print("\n[TEST 4] 图片检测 - 无人脸图片（预期返回错误）")
    try:
        import io
        from PIL import Image

        img = Image.new("RGB", (100, 100), color="gray")
        buf = io.BytesIO()
        img.save(buf, format="JPEG")
        buf.seek(0)

        files = {"file": ("no_face.jpg", buf, "image/jpeg")}
        resp = requests.post(f"{BASE_URL}/api/detect/image", files=files, timeout=10)
        data = resp.json()
        passed = resp.status_code == 400 and "未检测到人脸" in data.get("error", "")
        report.add("无人脸图片", passed, f"状态码={resp.status_code}, 错误信息={data.get('error', '')}")
    except Exception as e:
        report.add("无人脸图片", False, str(e))


def test_image_detect_real():
    """测试 5：图片检测 - 真实人脸"""
    print("\n[TEST 5] 图片检测 - 真实人脸")
    if TEST_IMAGE_REAL is None:
        report.add("真实人脸检测", False, "未找到测试图片，请在 data/test/real/ 下放置图片")
        return

    try:
        with open(TEST_IMAGE_REAL, "rb") as f:
            files = {"file": (os.path.basename(TEST_IMAGE_REAL), f, "image/jpeg")}
            resp = requests.post(f"{BASE_URL}/api/detect/image", files=files, timeout=30)

        data = resp.json()
        if resp.status_code == 400 and "未检测到人脸" in data.get("error", ""):
            report.add("真实人脸检测", True, f"图片无人脸（正常）：{TEST_IMAGE_REAL}")
            return

        if not data.get("success"):
            report.add("真实人脸检测", False, f"失败：{data.get('error', '')}")
            return

        result = data["data"]
        is_fake = result.get("is_fake")
        fake_prob = result.get("fake_probability")
        faces = result.get("faces_detected", 0)

        passed = resp.status_code == 200 and data.get("success") is True and faces > 0
        msg = f"人脸数={faces}, 伪造={is_fake}, 伪造概率={fake_prob:.4f}, 图片={os.path.basename(TEST_IMAGE_REAL)}"
        report.add("真实人脸检测", passed, msg)
    except Exception as e:
        report.add("真实人脸检测", False, str(e))


def test_image_detect_fake():
    """测试 6：图片检测 - 伪造人脸"""
    print("\n[TEST 6] 图片检测 - 伪造人脸")
    if TEST_IMAGE_FAKE is None:
        report.add("伪造人脸检测", False, "未找到测试图片，请在 data/test/fake/ 下放置图片")
        return

    try:
        with open(TEST_IMAGE_FAKE, "rb") as f:
            files = {"file": (os.path.basename(TEST_IMAGE_FAKE), f, "image/jpeg")}
            resp = requests.post(f"{BASE_URL}/api/detect/image", files=files, timeout=30)

        data = resp.json()
        if resp.status_code == 400 and "未检测到人脸" in data.get("error", ""):
            report.add("伪造人脸检测", True, f"图片无人脸（正常）：{TEST_IMAGE_FAKE}")
            return

        if not data.get("success"):
            report.add("伪造人脸检测", False, f"失败：{data.get('error', '')}")
            return

        result = data["data"]
        is_fake = result.get("is_fake")
        fake_prob = result.get("fake_probability")
        faces = result.get("faces_detected", 0)

        passed = resp.status_code == 200 and data.get("success") is True and faces > 0
        msg = f"人脸数={faces}, 伪造={is_fake}, 伪造概率={fake_prob:.4f}, 图片={os.path.basename(TEST_IMAGE_FAKE)}"
        report.add("伪造人脸检测", passed, msg)
    except Exception as e:
        report.add("伪造人脸检测", False, str(e))


def test_text_endpoint():
    """测试 7：文本接口"""
    print("\n[TEST 7] 文本检测接口 /api/detect/text")
    try:
        resp = requests.post(
            f"{BASE_URL}/api/detect/text",
            json={"text": "测试文本"},
            headers={"Content-Type": "application/json"},
            timeout=5,
        )
        data = resp.json()
        passed = resp.status_code == 200 and data.get("success") is True
        report.add("文本检测接口", passed, f"响应={json.dumps(data, ensure_ascii=False)}")
    except Exception as e:
        report.add("文本检测接口", False, str(e))


def test_video_detect():
    """测试 8：视频检测（可选）"""
    print("\n[TEST 8] 视频检测 /api/detect/video")
    if not TEST_VIDEO or not os.path.exists(TEST_VIDEO):
        report.add("视频检测", None, "跳过（未配置测试视频，使用 --test_video 指定路径）")
        return

    try:
        with open(TEST_VIDEO, "rb") as f:
            files = {"file": (os.path.basename(TEST_VIDEO), f, "video/mp4")}
            start = time.time()
            resp = requests.post(f"{BASE_URL}/api/detect/video", files=files, timeout=120)
            elapsed = time.time() - start

        data = resp.json()
        if not data.get("success"):
            report.add("视频检测", False, f"失败：{data.get('error', '')}")
            return

        result = data["data"]
        frames = result.get("total_frames", 0)
        faces = result.get("total_faces", 0)
        is_fake = result.get("is_fake")
        avg_prob = result.get("average_fake_probability", 0)

        passed = resp.status_code == 200 and data.get("success") is True and frames > 0
        msg = f"帧数={frames}, 人脸数={faces}, 是否伪造={is_fake}, 平均伪造概率={avg_prob:.4f}, 耗时={elapsed:.1f}s"
        report.add("视频检测", passed, msg)
    except Exception as e:
        report.add("视频检测", False, str(e))


def main():
    print("=" * 60)
    print("  DeepFake 检测 API 集成测试套件")
    print(f"  目标地址：{BASE_URL}")
    print(f"  测试图片-真实：{TEST_IMAGE_REAL or '未找到'}")
    print(f"  测试图片-伪造：{TEST_IMAGE_FAKE or '未找到'}")
    print(f"  测试视频：{TEST_VIDEO or '未配置'}")
    print("=" * 60)

    test_health()
    test_image_detect_no_file()
    test_image_detect_empty_file()
    test_image_detect_no_face()
    test_image_detect_real()
    test_image_detect_fake()
    test_text_endpoint()
    test_video_detect()

    all_passed = report.summary()
    return 0 if all_passed else 1


if __name__ == "__main__":
    sys.exit(main())

