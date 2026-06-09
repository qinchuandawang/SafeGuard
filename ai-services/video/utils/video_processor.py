"""
视频帧提取和人脸检测工具
用于从视频中提取帧并检测人脸区域
"""

import cv2
import os
import face_recognition


class FrameExtractor:
    """视频帧提取器"""
    
    def __init__(self, frame_interval=1):
        """
        参数:
            frame_interval: 每隔多少帧提取一帧
        """
        self.frame_interval = frame_interval
    
    def extract_frames(self, video_path, output_dir, max_frames=100):
        """
        从视频中提取帧
        
        参数:
            video_path: 视频文件路径
            output_dir: 输出目录
            max_frames: 最大提取帧数
        """
        os.makedirs(output_dir, exist_ok=True)

        cap = cv2.VideoCapture(video_path)
        try:
            frame_count = 0
            saved_count = 0

            while cap.isOpened():
                ret, frame = cap.read()
                if not ret:
                    break

                # 每隔 frame_interval 帧提取一帧
                if frame_count % self.frame_interval == 0:
                    frame_path = os.path.join(output_dir, f"frame_{saved_count:04d}.jpg")
                    cv2.imwrite(frame_path, frame)
                    saved_count += 1

                    if saved_count >= max_frames:
                        break

                frame_count += 1
        finally:
            cap.release()
        print(f"从视频 {video_path} 提取了 {saved_count} 帧")
        return saved_count


class FaceDetector:
    """人脸检测器"""
    
    def __init__(self, detection_method='hog'):
        """
        参数:
            detection_method: 检测方法 ('hog' 或 'cnn')
        """
        self.detection_method = detection_method
    
    def detect_faces(self, image_path, output_size=(299, 299)):
        """
        检测图片中的人脸并裁剪
        
        参数:
            image_path: 图片路径
            output_size: 输出图片尺寸
        
        返回:
            faces: 人脸图片列表
            face_locations: 人脸位置列表
        """
        # 加载图片
        image = face_recognition.load_image_file(image_path)
        
        # 检测人脸
        face_locations = face_recognition.face_locations(image, model=self.detection_method)
        
        faces = []
        for (top, right, bottom, left) in face_locations:
            # 裁剪人脸区域
            face = image[top:bottom, left:right]
            # 转换为 BGR（OpenCV 格式）
            face = cv2.cvtColor(face, cv2.COLOR_RGB2BGR)
            # 调整大小
            face = cv2.resize(face, output_size)
            faces.append(face)
        
        return faces, face_locations
    
    def extract_faces_from_frames(self, frames_dir, output_dir, output_size=(299, 299)):
        """
        从帧目录中提取所有人脸
        
        参数:
            frames_dir: 帧目录
            output_dir: 输出目录
            output_size: 输出图片尺寸
        """
        os.makedirs(output_dir, exist_ok=True)
        
        face_count = 0
        for frame_file in Path(frames_dir).glob("*.jpg"):
            faces, locations = self.detect_faces(str(frame_file), output_size)
            
            for i, face in enumerate(faces):
                face_path = os.path.join(output_dir, f"{frame_file.stem}_face_{i}.jpg")
                cv2.imwrite(face_path, face)
                face_count += 1
        
        print(f"从 {frames_dir} 提取了 {face_count} 张人脸")
        return face_count


class DataPreprocessor:
    """数据预处理工具"""
    
    def __init__(self, image_size=(299, 299)):
        self.image_size = image_size
        self.frame_extractor = FrameExtractor()
        self.face_detector = FaceDetector()
    
    def process_video(self, video_path, output_dir, label, video_name=None):
        """
        处理单个视频：提取帧 -> 检测人脸 -> 保存
        
        参数:
            video_path: 视频路径
            output_dir: 输出目录
            label: 标签（'real' 或 'fake'）
            video_name: 视频名称
        """
        if video_name is None:
            video_name = Path(video_path).stem
        
        # 创建输出目录
        video_output_dir = os.path.join(output_dir, label, video_name)
        os.makedirs(video_output_dir, exist_ok=True)
        
        # 提取帧
        frames_dir = os.path.join(video_output_dir, "frames")
        self.frame_extractor.extract_frames(video_path, frames_dir)
        
        # 检测人脸
        faces_dir = os.path.join(video_output_dir, "faces")
        self.face_detector.extract_faces_from_frames(frames_dir, faces_dir, self.image_size)
        
        return faces_dir
    
    def process_dataset(self, videos_dir, output_dir, label_map):
        """
        批量处理数据集
        
        参数:
            videos_dir: 视频目录
            output_dir: 输出目录
            label_map: 标签映射 {目录名：标签}
        """
        for dir_name, label in label_map.items():
            dir_path = os.path.join(videos_dir, dir_name)
            if not os.path.exists(dir_path):
                continue
            
            for video_file in Path(dir_path).glob("*.mp4"):
                print(f"处理视频：{video_file}")
                self.process_video(str(video_file), output_dir, label)


if __name__ == '__main__':
    # 测试
    detector = FaceDetector()
    faces, locations = detector.detect_faces("test.jpg")
    print(f"检测到 {len(faces)} 张人脸")
