"""
DeepFake 检测 Flask API 服务
提供视频/图片换脸检测接口
"""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from flask import Flask, request, jsonify
from flask_cors import CORS
import torch
import torch.nn.functional as F
from torchvision import transforms
from PIL import Image
import numpy as np
import cv2
import tempfile
import threading
from pathlib import Path

from models.xception import xception
from utils.video_processor import FrameExtractor, FaceDetector


app = Flask(__name__)
CORS(app)

# 配置
def select_device():
    """优先使用环境变量指定的设备，演示环境默认走 GPU。"""
    requested = os.environ.get('SAFEGUARD_DEVICE', 'cuda').strip().lower()
    if requested.startswith('cuda'):
        if torch.cuda.is_available():
            return torch.device(requested)
        print('[WARN] SAFEGUARD_DEVICE=cuda，但当前 PyTorch 未检测到 CUDA，已退回 CPU')
        return torch.device('cpu')
    if requested == 'cpu':
        return torch.device('cpu')
    return torch.device('cuda' if torch.cuda.is_available() else 'cpu')


DEVICE = select_device()
MODEL_PATH = 'pretrained/best_model.pth'
IMAGE_SIZE = (299, 299)
MAX_UPLOAD_MB = int(os.environ.get('MAX_UPLOAD_MB', '100'))
ALLOWED_IMAGE_EXTENSIONS = {'.jpg', '.jpeg', '.png', '.bmp', '.webp'}
ALLOWED_VIDEO_EXTENSIONS = {'.mp4', '.mov', '.avi', '.mkv', '.webm'}
app.config['MAX_CONTENT_LENGTH'] = MAX_UPLOAD_MB * 1024 * 1024

# PyTorch 线程数限制 -- CPU 环境下默认会占用所有物理核心，限制后显著降低内存
os.environ.setdefault("OMP_NUM_THREADS", "2")
os.environ.setdefault("MKL_NUM_THREADS", "2")
torch.set_num_threads(int(os.environ.get("OMP_NUM_THREADS", "2")))
torch.set_grad_enabled(False)


# ============ 统一响应格式 ============

def success_response(data, message="success"):
    """统一成功响应：与音频服务格式一致"""
    return jsonify({"code": 0, "message": message, "data": data})


def error_response(message, http_status=400, code=1):
    """统一错误响应"""
    return jsonify({"code": code, "message": message, "data": None}), http_status


def validate_upload(file, allowed_extensions):
    """检查上传文件名和后缀，避免非媒体文件进入推理流程"""
    if file.filename == '':
        return '文件名为空'
    suffix = Path(file.filename).suffix.lower()
    if suffix not in allowed_extensions:
        return f'不支持的文件格式: {suffix}'
    return None


# ====================================

# 模型引用，惰性加载
_model_instance = None
_model_lock = threading.RLock()

def load_model(model_path):
    """惰性加载模型，仅在首次请求时加载"""
    global _model_instance
    if _model_instance is not None:
        return _model_instance
    with _model_lock:
        if _model_instance is not None:
            return _model_instance
        print(f"[首次加载] 模型: {model_path}, 设备: {DEVICE}")

        if not os.path.exists(model_path):
            raise FileNotFoundError(
                f"模型文件不存在: {model_path}。请先下载预训练模型并放置到 {MODEL_PATH}。"
            )

        model = xception(num_classes=2)
        checkpoint = torch.load(model_path, map_location=DEVICE, weights_only=False)
        model.load_state_dict(checkpoint['model_state_dict'])
        print(f"模型加载成功：{model_path}")
        model.to(DEVICE)
        model.eval()
        _model_instance = model
        return model


def get_model():
    """获取模型实例（首次调用时惰性加载）"""
    return load_model(MODEL_PATH)


# 数据变换
transform = transforms.Compose([
    transforms.Resize((299, 299)),
    transforms.ToTensor(),
    transforms.Normalize(mean=[0.485, 0.456, 0.406],
                        std=[0.229, 0.224, 0.225])
])


def detect_faces_in_image(image_path):
    """检测图片中的人脸"""
    face_detector = FaceDetector(detection_method='hog')
    faces, locations = face_detector.detect_faces(image_path, IMAGE_SIZE)
    return faces, locations


def predict_image(image):
    """预测单张图片。

    演示场景优先保证判定稳定性，CUDA 上也使用 float32，避免半精度把低概率压得过低。
    """
    dtype = torch.float32
    image_tensor = transform(image).unsqueeze(0).to(DEVICE, dtype=dtype)

    with _model_lock:
        with torch.no_grad():
            outputs = get_model()(image_tensor)
            probs = F.softmax(outputs, dim=1)
            fake_prob = probs[0][1].item()
    
    return {
        'is_fake': fake_prob > 0.5,
        'fake_probability': fake_prob,
        'real_probability': 1 - fake_prob
    }


def predict_video(video_path, max_frames=None):
    """预测视频"""
    if max_frames is None:
        # 与后端 application.yml video.preprocess.max-frames=24 保持一致
        # 8 帧是 CPU 推理下的速度/准确度平衡点（每帧约 3-5 秒，全视频约 25-40 秒）
        max_frames = int(os.environ.get("MAX_FRAMES", "8"))
    frame_extractor = FrameExtractor(frame_interval=2)
    
    # 创建临时目录存储帧
    temp_dir = tempfile.mkdtemp()
    try:
        frames_dir = os.path.join(temp_dir, 'frames')
        faces_dir = os.path.join(temp_dir, 'faces')
        os.makedirs(frames_dir, exist_ok=True)
        os.makedirs(faces_dir, exist_ok=True)
        
        # 提取帧
        frame_extractor.extract_frames(video_path, frames_dir, max_frames=max_frames)
        
        # 检测人脸并预测
        frame_results = []
        face_detector = FaceDetector(detection_method='hog')
        frames_with_face = 0  # 至少有一张人脸的帧数
        
        for frame_file in sorted(Path(frames_dir).glob("*.jpg")):
            faces, locations = face_detector.detect_faces(str(frame_file), IMAGE_SIZE)
            
            frame_result = {
                'frame_name': frame_file.name,
                'faces': []
            }
            
            if not faces:
                # 与图片接口保持一致：无人脸时降级为全图检测，
                # 否则 total_faces=0 直接判定 is_fake=False，会把"完全没人脸的视频"判为真实
                full_image = Image.open(str(frame_file)).convert('RGB')
                full_pred = predict_image(full_image)
                full_pred['face_location'] = None
                full_pred['fallback'] = True
                full_pred['fallback_reason'] = '未检测到独立人脸，使用全图检测'
                frame_result['faces'].append(full_pred)
            else:
                frames_with_face += 1
                for i, face in enumerate(faces):
                    face_pil = Image.fromarray(cv2.cvtColor(face, cv2.COLOR_BGR2RGB))
                    prediction = predict_image(face_pil)
                    prediction['face_location'] = locations[i]
                    frame_result['faces'].append(prediction)
            
            frame_results.append(frame_result)
        
        # 计算视频整体结果
        all_fake_probs = []
        for frame in frame_results:
            for face in frame['faces']:
                all_fake_probs.append(face['fake_probability'])
        
        if len(all_fake_probs) > 0:
            avg_fake_prob = float(np.mean(all_fake_probs))
            max_fake_prob = float(np.max(all_fake_probs))
            suspicious_frame_ratio = float(sum(1 for p in all_fake_probs if p >= 0.5) / len(all_fake_probs))
        else:
            avg_fake_prob = 0.0
            max_fake_prob = 0.0
            suspicious_frame_ratio = 0.0

        # 全部帧都无人脸时：不能简单判定为"真实"
        # 标记为"uncertain"并把 fake_probability 设为 0.5
        is_uncertain = frames_with_face == 0
        if is_uncertain:
            avg_fake_prob = 0.5
            max_fake_prob = 0.5
            suspicious_frame_ratio = 0.0

        aggregate_fake_probability = max(
            avg_fake_prob,
            max_fake_prob * 0.82,
            0.62 + min(0.18, suspicious_frame_ratio * 0.25) if suspicious_frame_ratio >= 0.25 else 0.0,
            0.45 + suspicious_frame_ratio * 0.5 if suspicious_frame_ratio > 0 else 0.0,
        )

        return {
            'frame_results': frame_results,
            'total_frames': len(frame_results),
            'total_faces': len(all_fake_probs),
            'frames_with_face': frames_with_face,
            'average_fake_probability': float(avg_fake_prob),
            'max_fake_probability': float(max_fake_prob),
            'aggregate_fake_probability': float(aggregate_fake_probability),
            'suspicious_frame_ratio': float(suspicious_frame_ratio),
            'is_fake': (not is_uncertain) and (aggregate_fake_probability >= 0.55),
            'is_uncertain': is_uncertain,
            'uncertain_reason': '所有帧均未检测到人脸' if is_uncertain else None,
        }
    finally:
        # 确保临时目录始终被清理
        import shutil
        shutil.rmtree(temp_dir, ignore_errors=True)


@app.route('/api/health', methods=['GET'])
def health_check():
    """健康检查，同时预热模型，避免演示时第一次检测才暴露模型问题"""
    try:
        get_model()
        return success_response({
            'status': 'healthy',
            'device': str(DEVICE),
            'model_loaded': True,
            'model_path': MODEL_PATH
        })
    except Exception as exc:
        return error_response(
            f'视频模型未就绪: {exc}',
            http_status=503,
            code=503
        )


@app.route('/api/detect/image', methods=['POST'])
def detect_image():
    """
    检测图片是否换脸
    
    请求格式：
        multipart/form-data
        file: 图片文件
    
    返回格式：
        {
            "success": true,
            "data": {
                "is_fake": true/false,
                "fake_probability": 0.95,
                "real_probability": 0.05,
                "faces_detected": 2,
                "faces": [
                    {
                        "is_fake": true,
                        "fake_probability": 0.95,
                        "real_probability": 0.05,
                        "face_location": (top, right, bottom, left)
                    }
                ]
            }
        }
    """
    if 'file' not in request.files:
        return error_response('未找到文件')

    file = request.files['file']
    validation_error = validate_upload(file, ALLOWED_IMAGE_EXTENSIONS)
    if validation_error:
        return error_response(validation_error)
    
    # 保存临时文件
    # 使用 NamedTemporaryFile 避免 mktemp 的竞态风险；Windows 下需先关闭句柄再保存
    with tempfile.NamedTemporaryFile(delete=False, suffix=".jpg") as tmp:
        temp_path = tmp.name
    file.save(temp_path)
    
    try:
        # 检测人脸
        faces, locations = detect_faces_in_image(temp_path)

        if len(faces) == 0:
            # 无人脸时降级为全图检测，而不是直接返回错误
            # 使得视频管道即使无可见人脸也能继续运行
            full_image = Image.open(temp_path).convert('RGB')
            full_pred = predict_image(full_image)
            result = {
                'is_fake': full_pred['is_fake'],
                'fake_probability': full_pred['fake_probability'],
                'real_probability': full_pred['real_probability'],
                'confidence': abs(full_pred['fake_probability'] - 0.5) * 2,
                'fake_type': 'full_image',
                'faces_detected': 0,
                'faces': [],
                'fallback': True,
                'fallback_reason': '未检测到独立人脸，使用全图检测'
            }
            return success_response(result)

        # 预测每个人脸
        face_results = []
        for i, face in enumerate(faces):
            face_pil = Image.fromarray(cv2.cvtColor(face, cv2.COLOR_BGR2RGB))
            prediction = predict_image(face_pil)
            prediction['face_location'] = locations[i]
            face_results.append(prediction)
        
        # 综合结果
        avg_fake_prob = float(np.mean([f['fake_probability'] for f in face_results]))
        avg_confidence = abs(avg_fake_prob - 0.5) * 2  # 离 0.5 越远置信度越高

        result = {
            'is_fake': avg_fake_prob > 0.5,
            'fake_probability': float(avg_fake_prob),
            'real_probability': float(1 - avg_fake_prob),
            'confidence': float(avg_confidence),
            'fake_type': 'face_swapped' if avg_fake_prob > 0.5 else 'none',
            'faces_detected': len(face_results),
            'faces': face_results
        }
        
        return success_response(result)

    finally:
        # 清理临时文件
        if os.path.exists(temp_path):
            os.remove(temp_path)


@app.route('/api/detect/video', methods=['POST'])
def detect_video():
    """
    检测视频是否换脸
    
    请求格式：
        multipart/form-data
        file: 视频文件
    
    返回格式：
        {
            "success": true,
            "data": {
                "is_fake": true/false,
                "average_fake_probability": 0.85,
                "max_fake_probability": 0.98,
                "total_frames": 30,
                "total_faces": 45,
                "frame_results": [
                    {
                        "frame_name": "frame_0001.jpg",
                        "faces": [
                            {
                                "is_fake": true,
                                "fake_probability": 0.95,
                                "face_location": (top, right, bottom, left)
                            }
                        ]
                    }
                ]
            }
        }
    """
    if 'file' not in request.files:
        return error_response('未找到文件')

    file = request.files['file']
    validation_error = validate_upload(file, ALLOWED_VIDEO_EXTENSIONS)
    if validation_error:
        return error_response(validation_error)
    
    # 保存临时文件
    # 使用 NamedTemporaryFile 避免 mktemp 的竞态风险；Windows 下需先关闭句柄再保存
    with tempfile.NamedTemporaryFile(delete=False, suffix=".mp4") as tmp:
        temp_path = tmp.name
    file.save(temp_path)
    
    try:
        # 检测视频
        result = predict_video(temp_path)

        return success_response(result)

    finally:
        # 清理临时文件
        if os.path.exists(temp_path):
            os.remove(temp_path)


@app.route('/api/detect/text', methods=['POST'])
def detect_text():
    """
    文本检测接口（预留，由大模型处理）
    
    请求格式：
        application/json
        {
            "text": "可疑文本内容"
        }
    """
    data = request.get_json()
    
    if not data or 'text' not in data:
        return error_response('缺少 text 字段')

    # 这里预留接口，实际由大模型处理
    return success_response({
        'message': '文本检测由大模型处理，请调用大模型接口',
        'text': data['text']
    })


@app.errorhandler(404)
def not_found(error):
    return error_response('接口不存在', 404)


@app.errorhandler(500)
def internal_error(error):
    return error_response('服务器内部错误', 500, 500)


@app.errorhandler(413)
def request_too_large(error):
    return error_response(f'文件过大，最大支持 {MAX_UPLOAD_MB}MB', 413, 413)


if __name__ == '__main__':
    print(f"启动 DeepFake 检测服务...")
    print(f"设备：{DEVICE}")
    port = int(os.environ.get('PORT', '5002'))
    print(f"模型路径：{MODEL_PATH}")
    print(f"API 文档：http://localhost:{port}/api/health")

    app.run(host='0.0.0.0', port=port, threaded=True, debug=os.environ.get('FLASK_DEBUG', '0') == '1')
