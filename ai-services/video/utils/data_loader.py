"""
PyTorch 数据加载器
用于加载 WildDeepfake 数据集
"""

import os
import torch
from torch.utils.data import Dataset, DataLoader
from torchvision import transforms
from PIL import Image
from pathlib import Path


class DeepFakeDataset(Dataset):
    """DeepFake 数据集（支持 WildDeepfake）"""
    
    def __init__(self, data_dir, split='train', transform=None):
        """
        参数:
            data_dir: 数据根目录
            split: 数据集划分 ('train', 'valid', 'test')
            transform: 数据变换
        """
        self.data_dir = data_dir
        self.split = split
        self.transform = transform
        
        self.image_paths = []
        self.labels = []
        
        self._load_dataset()
    
    def _load_dataset(self):
        """加载数据集路径和标签"""
        # WildDeepfake 使用 valid 而不是 val
        split_name = self.split if self.split != 'val' else 'valid'
        split_dir = os.path.join(self.data_dir, split_name)
        
        if not os.path.exists(split_dir):
            print(f"警告：{split_dir} 不存在")
            return
        
        for label in ['real', 'fake']:
            label_dir = os.path.join(split_dir, label)
            if not os.path.exists(label_dir):
                continue
            
            # 检查目录结构类型
            # 类型 1：扁平结构（data/train/real/*.png）
            # 类型 2：嵌套结构（data/train/real/0/*.jpg）
            
            # 先检查是否有子文件夹
            has_subdirs = any(d.is_dir() for d in Path(label_dir).iterdir())
            
            if not has_subdirs:
                # 扁平结构：直接查找图片文件
                print(f"检测到扁平结构：{label_dir}/*.png")
                for image_file in Path(label_dir).glob("*.png"):
                    if image_file.is_file():
                        self.image_paths.append(str(image_file))
                        self.labels.append(1 if label == 'fake' else 0)
                
                # 也检查 .jpg 文件（兼容性）
                for image_file in Path(label_dir).glob("*.jpg"):
                    if image_file.is_file():
                        self.image_paths.append(str(image_file))
                        self.labels.append(1 if label == 'fake' else 0)
            else:
                # 嵌套结构：遍历子文件夹
                print(f"检测到嵌套结构：{label_dir}/序列号/*.jpg")
                for sequence_dir in Path(label_dir).iterdir():
                    if not sequence_dir.is_dir():
                        continue
                    
                    for image_file in sequence_dir.glob("*.jpg"):
                        self.image_paths.append(str(image_file))
                        self.labels.append(1 if label == 'fake' else 0)
                    
                    # 也支持 .png 格式
                    for image_file in sequence_dir.glob("*.png"):
                        self.image_paths.append(str(image_file))
                        self.labels.append(1 if label == 'fake' else 0)
        
        print(f"加载了 {len(self.image_paths)} 张图片 ({self.split})")
        print(f"  真实图片：{self.labels.count(0)}")
        print(f"  伪造图片：{self.labels.count(1)}")
    
    def __len__(self):
        return len(self.image_paths)
    
    def __getitem__(self, idx):
        image_path = self.image_paths[idx]
        label = self.labels[idx]
        
        try:
            image = Image.open(image_path).convert('RGB')
        except Exception as e:
            # 损坏图片：返回占位张量，形状与 transform 输出对齐 (3, 299, 299)
            print(f"警告: 加载图片失败 {image_path}: {e}")
            dummy = torch.zeros((3, 299, 299))
            return dummy, torch.tensor(label, dtype=torch.long)
        
        if self.transform:
            image = self.transform(image)
        
        return image, torch.tensor(label, dtype=torch.long)


class VideoFrameDataset(Dataset):
    """视频帧数据集（用于批量视频检测）"""
    
    def __init__(self, video_paths, transform=None, max_frames=30):
        """
        参数:
            video_paths: 视频路径列表
            transform: 数据变换
            max_frames: 每个视频最大帧数
        """
        self.video_paths = video_paths
        self.transform = transform
        self.max_frames = max_frames
    
    def __len__(self):
        return len(self.video_paths)
    
    def __getitem__(self, idx):
        video_path = self.video_paths[idx]
        
        frames = self._extract_frames(video_path)
        
        if self.transform:
            frames = [self.transform(frame) for frame in frames]
        
        if len(frames) == 0:
            frames = torch.zeros(1, 3, 299, 299)
        else:
            frames = torch.stack(frames)
        
        return frames, video_path
    
    def _extract_frames(self, video_path):
        """从视频中提取帧"""
        import cv2
        
        frames = []
        cap = cv2.VideoCapture(video_path)
        
        frame_count = 0
        while cap.isOpened() and frame_count < self.max_frames:
            ret, frame = cap.read()
            if not ret:
                break
            
            frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            frame = Image.fromarray(frame)
            frames.append(frame)
            
            frame_count += 1
        
        cap.release()
        return frames


def get_transforms(split='train'):
    """获取数据变换"""
    
    if split == 'train':
        return transforms.Compose([
            transforms.Resize((320, 320)),
            transforms.RandomCrop((299, 299)),
            transforms.RandomHorizontalFlip(p=0.5),
            transforms.ColorJitter(brightness=0.3, contrast=0.3, saturation=0.3, hue=0.1),
            transforms.RandomRotation(10),
            transforms.RandomAffine(degrees=0, translate=(0.1, 0.1)),
            transforms.ToTensor(),
            transforms.Normalize(mean=[0.485, 0.456, 0.406],
                               std=[0.229, 0.224, 0.225])
        ])
    else:
        return transforms.Compose([
            transforms.Resize((299, 299)),
            transforms.ToTensor(),
            transforms.Normalize(mean=[0.485, 0.456, 0.406],
                               std=[0.229, 0.224, 0.225])
        ])


def get_dataloaders(data_dir, batch_size=32, num_workers=4):
    """获取数据加载器"""
    
    train_dataset = DeepFakeDataset(data_dir, split='train', transform=get_transforms('train'))
    val_dataset = DeepFakeDataset(data_dir, split='valid', transform=get_transforms('valid'))
    test_dataset = DeepFakeDataset(data_dir, split='test', transform=get_transforms('test'))
    
    train_loader = DataLoader(train_dataset, batch_size=batch_size, shuffle=True, 
                             num_workers=num_workers, pin_memory=True)
    val_loader = DataLoader(val_dataset, batch_size=batch_size, shuffle=False, 
                           num_workers=num_workers, pin_memory=True)
    test_loader = DataLoader(test_dataset, batch_size=batch_size, shuffle=False, 
                            num_workers=num_workers, pin_memory=True)
    
    return train_loader, val_loader, test_loader


if __name__ == '__main__':
    # 测试
    transform = get_transforms('train')
    print("数据变换配置完成")
