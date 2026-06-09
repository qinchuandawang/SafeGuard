"""
XceptionNet 训练脚本
用于 DeepFake 视频换脸检测
"""

import os
import torch
import torch.nn as nn
import torch.optim as optim
from torch.optim import lr_scheduler
# 兼容 PyTorch 2.0.x ~ 2.5.x
# 2.0.x 中 autocast/GradScaler 在 torch.cuda.amp 下
# 2.1+ 开始独立暴露在 torch.amp 下
try:
    from torch.amp import autocast, GradScaler
except ImportError:
    from torch.cuda.amp import autocast, GradScaler
from tqdm import tqdm
import argparse
import json

from models.xception import xception
from utils.data_loader import get_dataloaders


class Trainer:
    """训练器"""
    
    def __init__(self, model, train_loader, val_loader, criterion, optimizer, scheduler, device, save_dir):
        self.model = model.to(device)
        self.train_loader = train_loader
        self.val_loader = val_loader
        self.criterion = criterion
        self.optimizer = optimizer
        self.scheduler = scheduler
        self.device = device
        self.save_dir = save_dir
        
        self.best_acc = 0.0
        self.history = {'train_loss': [], 'train_acc': [], 'val_loss': [], 'val_acc': []}
        
        # 新增：混合精度训练器（仅在 CUDA 上启用；CPU 上启用会导致不支持/报错）
        self.use_amp = (str(device).startswith("cuda") or getattr(device, "type", None) == "cuda")
        self.scaler = GradScaler(enabled=self.use_amp)
    
    def train_epoch(self, accumulation_steps=2):
        """训练一个 epoch - 支持梯度累积和混合精度"""
        self.model.train()
        running_loss = 0.0
        correct = 0
        total = 0
        
        # 梯度累积：每 accumulation_steps 步更新一次
        self.optimizer.zero_grad()
        
        pbar = tqdm(self.train_loader, desc='Training')
        for i, (inputs, labels) in enumerate(pbar):
            inputs = inputs.to(self.device)
            labels = labels.to(self.device)
            
            # 混合精度训练
            with autocast('cuda', enabled=self.use_amp):
                outputs = self.model(inputs)
                loss = self.criterion(outputs, labels)
                # 梯度累积：loss 除以累积步数
                loss = loss / accumulation_steps
                _, preds = torch.max(outputs, 1)
            
            # 混合精度反向传播
            if self.use_amp:
                self.scaler.scale(loss).backward()
            else:
                loss.backward()
            
            # 每 accumulation_steps 步更新一次参数
            if (i + 1) % accumulation_steps == 0:
                if self.use_amp:
                    self.scaler.step(self.optimizer)
                    self.scaler.update()
                else:
                    self.optimizer.step()
                self.optimizer.zero_grad()
            
            running_loss += loss.item() * inputs.size(0) * accumulation_steps
            correct += torch.sum(preds == labels.data)
            total += labels.size(0)
            
            pbar.set_postfix({'loss': f'{running_loss/total:.4f}', 'acc': f'{100*correct/total:.2f}%'})
        
        # 处理最后可能剩余的梯度
        if total % accumulation_steps != 0:
            if self.use_amp:
                self.scaler.step(self.optimizer)
                self.scaler.update()
            else:
                self.optimizer.step()
            self.optimizer.zero_grad()
        
        epoch_loss = running_loss / total
        epoch_acc = 100 * correct / total
        
        return epoch_loss, epoch_acc
    
    def validate_epoch(self):
        """验证一个 epoch"""
        self.model.eval()
        running_loss = 0.0
        correct = 0
        total = 0
        
        with torch.no_grad():
            pbar = tqdm(self.val_loader, desc='Validating')
            for inputs, labels in pbar:
                inputs = inputs.to(self.device)
                labels = labels.to(self.device)
                
                # 验证时不需要混合精度
                outputs = self.model(inputs)
                loss = self.criterion(outputs, labels)
                _, preds = torch.max(outputs, 1)
                
                running_loss += loss.item() * inputs.size(0)
                correct += torch.sum(preds == labels.data)
                total += labels.size(0)
                
                pbar.set_postfix({'loss': f'{running_loss/total:.4f}', 'acc': f'{100*correct/total:.2f}%'})
        
        epoch_loss = running_loss / total
        epoch_acc = 100 * correct / total
        
        return epoch_loss, epoch_acc
    
    def train(self, num_epochs=50, accumulation_steps=2, resume_epoch=0):
        """训练模型"""
        start_epoch = resume_epoch
        print(f"开始训练 {num_epochs} 个 epoch...")
        if start_epoch > 0:
            print(f"从第 {start_epoch + 1} 轮继续训练...")
        print(f"梯度累积步数：{accumulation_steps} (等效 batch_size = {self.train_loader.batch_size * accumulation_steps})")
        
        for epoch in range(start_epoch, num_epochs):
            print(f'\nEpoch {epoch+1}/{num_epochs}')
            print('-' * 30)
            
            train_loss, train_acc = self.train_epoch(accumulation_steps=accumulation_steps)
            val_loss, val_acc = self.validate_epoch()
            
            self.scheduler.step()
            
            self.history['train_loss'].append(train_loss)
            self.history['train_acc'].append(train_acc)
            self.history['val_loss'].append(val_loss)
            self.history['val_acc'].append(val_acc)
            
            print(f'Train Loss: {train_loss:.4f} | Train Acc: {train_acc:.2f}%')
            print(f'Val Loss: {val_loss:.4f} | Val Acc: {val_acc:.2f}%')
            
            # 保存最佳模型
            if val_acc > self.best_acc:
                self.best_acc = val_acc
                self.save_checkpoint(epoch, f'best_model.pth')
                print(f'保存最佳模型 (Acc: {val_acc:.2f}%)')
        
        # 保存训练历史
        self.save_training_history()
        
        print(f'\n训练完成！最佳验证准确率：{self.best_acc:.2f}%')
        return self.best_acc
    
    def save_checkpoint(self, epoch, filename):
        """保存检查点"""
        checkpoint = {
            'epoch': epoch,
            'model_state_dict': self.model.state_dict(),
            'optimizer_state_dict': self.optimizer.state_dict(),
            'scaler_state_dict': self.scaler.state_dict(),  # 新增：保存scaler状态
            'best_acc': self.best_acc,
            'history': self.history
        }
        torch.save(checkpoint, os.path.join(self.save_dir, filename))
    
    def save_training_history(self):
        """保存训练历史"""
        history_path = os.path.join(self.save_dir, 'training_history.json')
        history_to_save = {}
        for key, values in self.history.items():
            history_to_save[key] = [float(v) if torch.is_tensor(v) else v for v in values]
        with open(history_path, 'w') as f:
            json.dump(history_to_save, f, indent=2)


def main():
    parser = argparse.ArgumentParser(description='训练 XceptionNet DeepFake 检测模型')
    parser.add_argument('--data_dir', type=str, default='data', help='数据目录')
    parser.add_argument('--save_dir', type=str, default='checkpoints', help='模型保存目录')
    parser.add_argument('--batch_size', type=int, default=32, help='批次大小')
    parser.add_argument('--num_epochs', type=int, default=50, help='训练轮数')
    parser.add_argument('--lr', type=float, default=0.001, help='学习率')
    parser.add_argument('--num_workers', type=int, default=4, help='数据加载线程数')
    parser.add_argument('--pretrained', action='store_true', help='使用预训练权重')
    parser.add_argument('--accumulation_steps', type=int, default=2, help='梯度累积步数（等效增大 batch size）')
    parser.add_argument('--resume', type=str, default=None, help='从检查点恢复训练（指定检查点路径）')
    parser.add_argument('--weight_decay', type=float, default=1e-5, help='权重衰减（L2正则化）')
    args = parser.parse_args()
    
    # 设置设备
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    print(f'使用设备：{device}')
    
    # 创建保存目录
    os.makedirs(args.save_dir, exist_ok=True)
    
    # 获取数据加载器
    print('加载数据...')
    train_loader, val_loader, test_loader = get_dataloaders(
        args.data_dir, 
        batch_size=args.batch_size, 
        num_workers=args.num_workers
    )
    
    # 创建模型
    print('创建模型...')
    model = xception(num_classes=2, pretrained=args.pretrained and args.resume is None)
    
    # 定义损失函数和优化器
    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    scheduler = lr_scheduler.StepLR(optimizer, step_size=10, gamma=0.1)
    
    # 创建训练器
    trainer = Trainer(
        model=model,
        train_loader=train_loader,
        val_loader=val_loader,
        criterion=criterion,
        optimizer=optimizer,
        scheduler=scheduler,
        device=device,
        save_dir=args.save_dir
    )
    
    # 恢复训练
    resume_epoch = 0
    if args.resume:
        if os.path.exists(args.resume):
            print(f'从检查点恢复训练：{args.resume}')
            checkpoint = torch.load(args.resume, map_location=device)
            
            # 加载模型权重（strict=False 兼容新增的 Dropout 层）
            model.load_state_dict(checkpoint['model_state_dict'], strict=False)
            
            # 加载优化器状态
            optimizer.load_state_dict(checkpoint['optimizer_state_dict'])
            
            # 加载学习率调度器
            if 'scheduler_state_dict' in checkpoint:
                scheduler.load_state_dict(checkpoint['scheduler_state_dict'])
            
            # 恢复训练历史
            trainer.best_acc = checkpoint['best_acc']
            trainer.history = checkpoint['history']
            
            resume_epoch = checkpoint['epoch'] + 1
            print(f'恢复到 Epoch {resume_epoch}，最佳准确率：{trainer.best_acc:.2f}%')
            print(f'训练历史：已训练 {len(trainer.history["train_acc"])} 个 epoch')
        else:
            print(f'警告：检查点文件不存在 {args.resume}')
            args.resume = None
    
    # 开始训练
    if args.resume:
        # 从恢复点继续训练到指定的 num_epochs
        print(f'从第 {resume_epoch} 轮继续训练到第 {args.num_epochs} 轮')
        best_acc = trainer.train(
            num_epochs=args.num_epochs, 
            accumulation_steps=args.accumulation_steps,
            resume_epoch=resume_epoch
        )
    else:
        best_acc = trainer.train(num_epochs=args.num_epochs, accumulation_steps=args.accumulation_steps)
    
    print(f'\n训练完成！最佳验证准确率：{best_acc:.2f}%')
    print(f'模型保存在：{args.save_dir}')


if __name__ == '__main__':
    main()
