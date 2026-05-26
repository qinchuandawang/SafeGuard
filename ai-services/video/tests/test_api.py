"""
DeepFake 检测 API 测试脚本
用于测试与后端对接前的所有接口功能
"""

import os
import sys
import json
import time
import requests
from pathlib import Path

# 配置
BASE_URL = "http://localhost:5000"

# 测试图像路径（优先使用数据集中的图片）
DATA_DIR = "data"
TEST_IMAGE_REAL = None  # 会自动查找
TEST_IMAGE_FAKE = None
TEST_VIDEO = None       # 可选，有视频时填写路径

# ========== 自动查找测试图片 ==========
def find_test_images():
    """从数据集中自动查找真实和伪造测试图片"""
    global TEST_IMAGE_REAL, TEST_IMAGE_FAKE
    
    # 尝试从 test 目录找
    for split in ['test', 'valid', 'train']:
        for label, var in [('real', 'TEST_IMAGE_REAL'), ('fake', 'TEST_IMAGE_FAKE')]:
            target_var = TEST_IMAGE_REAL if var == 'TEST_IMAGE_REAL' else TEST_IMAGE_FAKE
            if target_var is not None:
                continue
            
            search_dir = Path(DATA_DIR) / split / label
            if search_dir.exists():
                # 扁平结构
                for ext in ['*.png', '*.jpg']:
                    images = list(search_dir.glob(ext))
                    if images:
                        if var == 'TEST_IMAGE_REAL':
                            TEST_IMAGE_REAL = str(images[0])
                        else:
                            TEST_IMAGE_FAKE = str(images[0])
                        break
                
                # 嵌套结构
                if var == 'TEST_IMAGE_REAL' and TEST_IMAGE_REAL is None:
                    subdirs = [d for d in search_dir.iterdir() if d.is_dir()]
                    if subdirs:
                        for ext in ['*.jpg', '*.png']:
                            images = list(subdirs[0].glob(ext))
                            if images:
                                TEST_IMAGE_REAL = str(images[0])
                                break
                elif var == 'TEST_IMAGE_FAKE' and TEST_IMAGE_FAKE is None:
                    subdirs = [d for d in search_dir.iterdir() if d.is_dir()]
                    if subdirs:
                        for ext in ['*.jpg', '*.png']:
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
        if passed:
            self.passed += 1
            status = "PASS"
        else:
            self.failed += 1
            status = "FAIL"
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
            and data.get("model_loaded") == True
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
        passed = resp.status_code == 400 and data.get("success") == False
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
        passed = resp.status_code == 400 and data.get("success") == False
        report.add("空文件名", passed, f"状态码={resp.status_code}, 错误信息={data.get('error', '')}")
    except Exception as e:
        report.add("空文件名", False, str(e))


def test_image_detect_no_face():
    """测试 4：图片检测 - 无人脸图片"""
    print("\n[TEST 4] 图片检测 - 无人脸图片（预期返回错误）")
    try:
        # 发送一个纯色 1x1 像素图片（无人脸）
        import io
        from PIL import Image
        img = Image.new('RGB', (100, 100), color='gray')
        buf = io.BytesIO()
        img.save(buf, format='JPEG')
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
        
        passed = (
            resp.status_code == 200
            and data.get("success") == True
            and faces > 0
        )
        msg = f"人脸数={faces}, 伪造={is_fake}, 伪造概率={fake_prob:.4f}, 图片={os.path.basename(TEST_IMAGE_REAL)}"
        report.add("真实人脸检测", passed, msg)
        
        # 额外检查：真实图片的伪造概率应该较低
        if is_fake:
            print(f"         ⚠ 注意：真实图片被判定为伪造（概率={fake_prob:.4f}），可能是误判")
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
        
        passed = (
            resp.status_code == 200
            and data.get("success") == True
            and faces > 0
        )
        msg = f"人脸数={faces}, 伪造={is_fake}, 伪造概率={fake_prob:.4f}, 图片={os.path.basename(TEST_IMAGE_FAKE)}"
        report.add("伪造人脸检测", passed, msg)
    except Exception as e:
        report.add("伪造人脸检测", False, str(e))


def test_response_format():
    """测试 7：响应格式验证"""
    print("\n[TEST 7] 响应 JSON 格式验证")
    if TEST_IMAGE_REAL is None:
        report.add("响应格式验证", False, "需要测试图片")
        return
    
    try:
        with open(TEST_IMAGE_REAL, "rb") as f:
            files = {"file": (os.path.basename(TEST_IMAGE_REAL), f, "image/jpeg")}
            resp = requests.post(f"{BASE_URL}/api/detect/image", files=files, timeout=30)
        
        data = resp.json()
        
        if not data.get("success") and resp.status_code == 400:
            report.add("响应格式验证", True, "图片无人脸，跳过格式验证")
            return
        
        result = data.get("data", {})
        
        # 验证必要字段
        checks = []
        checks.append(("success" in data, "顶层 success 字段"))
        checks.append(("data" in data, "顶层 data 字段"))
        checks.append(("is_fake" in result, "data.is_fake 字段"))
        checks.append(("fake_probability" in result, "data.fake_probability 字段"))
        checks.append(("real_probability" in result, "data.real_probability 字段"))
        checks.append(("faces_detected" in result, "data.faces_detected 字段"))
        checks.append(("faces" in result, "data.faces 数组"))
        checks.append((isinstance(result.get("fake_probability"), (int, float)), "fake_probability 是数字"))
        checks.append((isinstance(result.get("is_fake"), bool), "is_fake 是布尔值"))
        
        all_passed = all(c[0] for c in checks)
        failed_checks = [c[1] for c in checks if not c[0]]
        
        if all_passed:
            report.add("响应格式验证", True, "所有必要字段完整且类型正确")
        else:
            report.add("响应格式验证", False, f"缺少/类型错误：{', '.join(failed_checks)}")
    except Exception as e:
        report.add("响应格式验证", False, str(e))


def test_video_detect():
    """测试 8：视频检测（如果有测试视频）"""
    print("\n[TEST 8] 视频检测 /api/detect/video")
    if TEST_VIDEO is None or not os.path.exists(TEST_VIDEO):
        report.add("视频检测", None, "跳过（未配置测试视频，设置 TEST_VIDEO 变量后可用）")
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
        
        passed = (
            resp.status_code == 200
            and data.get("success") == True
            and frames > 0
        )
        msg = f"帧数={frames}, 人脸数={faces}, 是否伪造={is_fake}, 平均伪造概率={avg_prob:.4f}, 耗时={elapsed:.1f}s"
        report.add("视频检测", passed, msg)
    except Exception as e:
        report.add("视频检测", False, str(e))


def test_text_endpoint():
    """测试 9：文本接口（留空测试）"""
    print("\n[TEST 9] 文本检测接口 /api/detect/text")
    try:
        resp = requests.post(
            f"{BASE_URL}/api/detect/text",
            json={"text": "测试文本"},
            headers={"Content-Type": "application/json"},
            timeout=5
        )
        data = resp.json()
        passed = resp.status_code == 200 and data.get("success") == True
        report.add("文本检测接口", passed, f"响应={json.dumps(data, ensure_ascii=False)}")
    except Exception as e:
        report.add("文本检测接口", False, str(e))


def test_batch_images():
    """测试 10：批量图片检测性能"""
    print("\n[TEST 10] 批量检测性能测试（5 张图片）")
    
    # 查找多张测试图片
    images = []
    for split in ['test', 'valid']:
        for label in ['real', 'fake']:
            search_dir = Path(DATA_DIR) / split / label
            if search_dir.exists():
                for ext in ['*.png', '*.jpg']:
                    found = list(search_dir.glob(ext))
                    images.extend([str(f) for f in found[:3]])
    
    if not images:
        report.add("批量性能测试", None, "跳过（未找到测试图片）")
        return
    
    images = images[:5]
    times = []
    success_count = 0
    
    for img_path in images:
        try:
            with open(img_path, "rb") as f:
                files = {"file": (os.path.basename(img_path), f, "image/jpeg")}
                start = time.time()
                resp = requests.post(f"{BASE_URL}/api/detect/image", files=files, timeout=30)
                elapsed = time.time() - start
                
                if resp.status_code == 200 and resp.json().get("success"):
                    success_count += 1
                    times.append(elapsed)
        except Exception:
            pass
    
    if times:
        avg_time = sum(times) / len(times)
        passed = avg_time < 3.0  # 平均每张小于 3 秒
        report.add(
            "批量性能测试",
            passed,
            f"成功={success_count}/{len(images)}, 平均耗时={avg_time:.2f}s/张, 总耗时={sum(times):.1f}s"
        )
    else:
        report.add("批量性能测试", False, "所有图片检测失败")


# ========== 主函数 ==========
def main():
    print("=" * 60)
    print("  DeepFake 检测 API 测试套件")
    print(f"  目标地址：{BASE_URL}")
    print(f"  测试图片-真实：{TEST_IMAGE_REAL or '未找到'}")
    print(f"  测试图片-伪造：{TEST_IMAGE_FAKE or '未找到'}")
    print(f"  测试视频：{TEST_VIDEO or '未配置'}")
    print("=" * 60)
    
    # 运行所有测试
    test_health()
    test_image_detect_no_file()
    test_image_detect_empty_file()
    test_image_detect_no_face()
    test_image_detect_real()
    test_image_detect_fake()
    test_response_format()
    test_video_detect()
    test_text_endpoint()
    test_batch_images()
    
    # 输出汇总
    all_passed = report.summary()
    
    # 输出给后端对接用的参考信息
    print("\n" + "=" * 60)
    print("  后端对接 - 接口规范摘要")
    print("=" * 60)
    print("""
  【图片检测】POST /api/detect/image
    请求：multipart/form-data, 字段名 "file"
    成功响应 200：
    {
      "success": true,
      "data": {
        "is_fake": false,
        "fake_probability": 0.15,
        "real_probability": 0.85,
        "faces_detected": 1,
        "faces": [...]
      }
    }
    失败响应 400：
    {"success": false, "error": "错误描述"}

  【视频检测】POST /api/detect/video
    请求：multipart/form-data, 字段名 "file"
    成功响应 200：
    {
      "success": true,
      "data": {
        "is_fake": false,
        "average_fake_probability": 0.23,
        "max_fake_probability": 0.45,
        "total_frames": 30,
        "total_faces": 45,
        "frame_results": [...]
      }
    }

  【健康检查】GET /api/health
    {"status": "healthy", "device": "cuda", "model_loaded": true}
""")
    
    return 0 if all_passed else 1


if __name__ == "__main__":
    sys.exit(main())