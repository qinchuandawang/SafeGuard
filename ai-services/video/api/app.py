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
import os
from pathlib import Path
import json
from datetime import datetime

from models.xception import xception
from utils.video_processor import FrameExtractor, FaceDetector


app = Flask(__name__)
CORS(app)

# 配置
DEVICE = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
MODEL_PATH = 'checkpoints/best_model.pth'
IMAGE_SIZE = (299, 299)

# 加载模型（半精度推理，显存减半）
def load_model(model_path):
    model = xception(num_classes=2)
    if os.path.exists(model_path):
        checkpoint = torch.load(model_path, map_location=DEVICE, weights_only=False)
        model.load_state_dict(checkpoint['model_state_dict'])
        print(f"模型加载成功：{model_path}")
    else:
        print(f"警告：模型文件不存在 {model_path}")
    model.to(DEVICE)
    model.eval()
    # 半精度推理 —— 显存占用约减半，速度基本不变
    model.half()
    return model

model = load_model(MODEL_PATH)

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
    """预测单张图片（半精度推理）"""
    image_tensor = transform(image).unsqueeze(0).to(DEVICE).half()

    with torch.no_grad():
        outputs = model(image_tensor)
        probs = F.softmax(outputs, dim=1)
        fake_prob = probs[0][1].item()
    
    return {
        'is_fake': fake_prob > 0.5,
        'fake_probability': fake_prob,
        'real_probability': 1 - fake_prob
    }


def predict_video(video_path, max_frames=30):
    """预测视频"""
    frame_extractor = FrameExtractor(frame_interval=1)
    
    # 创建临时目录存储帧
    temp_dir = tempfile.mkdtemp()
    frames_dir = os.path.join(temp_dir, 'frames')
    faces_dir = os.path.join(temp_dir, 'faces')
    os.makedirs(frames_dir, exist_ok=True)
    os.makedirs(faces_dir, exist_ok=True)
    
    # 提取帧
    frame_extractor.extract_frames(video_path, frames_dir, max_frames=max_frames)
    
    # 检测人脸并预测
    frame_results = []
    face_detector = FaceDetector(detection_method='hog')
    
    for frame_file in sorted(Path(frames_dir).glob("*.jpg")):
        faces, locations = face_detector.detect_faces(str(frame_file), IMAGE_SIZE)
        
        frame_result = {
            'frame_name': frame_file.name,
            'faces': []
        }
        
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
    else:
        avg_fake_prob = 0.0
        max_fake_prob = 0.0
    
    # 清理临时文件
    import shutil
    shutil.rmtree(temp_dir)
    
    return {
        'frame_results': frame_results,
        'total_frames': len(frame_results),
        'total_faces': len(all_fake_probs),
        'average_fake_probability': float(avg_fake_prob),
        'max_fake_probability': float(max_fake_prob),
        'is_fake': avg_fake_prob > 0.5
    }


@app.route('/api/health', methods=['GET'])
def health_check():
    """健康检查"""
    return jsonify({
        'status': 'healthy',
        'device': str(DEVICE),
        'model_loaded': True
    })


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
        return jsonify({'success': False, 'error': '未找到文件'}), 400
    
    file = request.files['file']
    if file.filename == '':
        return jsonify({'success': False, 'error': '文件名为空'}), 400
    
    # 保存临时文件
    temp_path = tempfile.mktemp(suffix='.jpg')
    file.save(temp_path)
    
    try:
        # 检测人脸
        faces, locations = detect_faces_in_image(temp_path)
        
        if len(faces) == 0:
            return jsonify({
                'success': False,
                'error': '未检测到人脸'
            }), 400
        
        # 预测每个人脸
        face_results = []
        for i, face in enumerate(faces):
            face_pil = Image.fromarray(cv2.cvtColor(face, cv2.COLOR_BGR2RGB))
            prediction = predict_image(face_pil)
            prediction['face_location'] = locations[i]
            face_results.append(prediction)
        
        # 综合结果
        avg_fake_prob = float(np.mean([f['fake_probability'] for f in face_results]))
        
        result = {
            'is_fake': avg_fake_prob > 0.5,
            'fake_probability': float(avg_fake_prob),
            'real_probability': float(1 - avg_fake_prob),
            'faces_detected': len(face_results),
            'faces': face_results
        }
        
        return jsonify({'success': True, 'data': result})
    
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
        return jsonify({'success': False, 'error': '未找到文件'}), 400
    
    file = request.files['file']
    if file.filename == '':
        return jsonify({'success': False, 'error': '文件名为空'}), 400
    
    # 保存临时文件
    temp_path = tempfile.mktemp(suffix='.mp4')
    file.save(temp_path)
    
    try:
        # 检测视频
        result = predict_video(temp_path)
        
        return jsonify({'success': True, 'data': result})
    
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
        return jsonify({'success': False, 'error': '缺少 text 字段'}), 400
    
    # 这里预留接口，实际由大模型处理
    return jsonify({
        'success': True,
        'data': {
            'message': '文本检测由大模型处理，请调用大模型接口',
            'text': data['text']
        }
    })


@app.errorhandler(404)
def not_found(error):
    return jsonify({'success': False, 'error': '接口不存在'}), 404


@app.errorhandler(500)
def internal_error(error):
    return jsonify({'success': False, 'error': '服务器内部错误'}), 500


if __name__ == '__main__':
    print(f"启动 DeepFake 检测服务...")
    print(f"设备：{DEVICE}")
    port = int(os.environ.get('PORT', '5002'))
    print(f"模型路径：{MODEL_PATH}")
    print(f"API 文档：http://localhost:{port}/api/health")

    app.run(host='0.0.0.0', port=port, debug=os.environ.get('FLASK_DEBUG', '0') == '1')
