"""
XceptionNet 模型实现
基于论文：Xception: Deep Learning with Depthwise Separable Convolutions
用于 DeepFake 视频换脸检测
"""

import torch
import torch.nn as nn
import torch.nn.functional as F


class SeparableConv2d(nn.Module):
    """深度可分离卷积"""
    
    def __init__(self, in_channels, out_channels, kernel_size=1, stride=1, padding=0, dilation=1, bias=False):
        super(SeparableConv2d, self).__init__()
        
        # 深度卷积：每个通道独立卷积
        self.conv1 = nn.Conv2d(in_channels, in_channels, kernel_size, stride, padding, 
                               dilation, groups=in_channels, bias=bias)
        # 逐点卷积：1x1 卷积组合通道
        self.pointwise = nn.Conv2d(in_channels, out_channels, 1, 1, 0, 1, 1, bias=bias)
    
    def forward(self, x):
        x = self.conv1(x)
        x = self.pointwise(x)
        return x


class Block(nn.Module):
    """Xception 基础模块 - 修复版"""
    
    def __init__(self, in_filters, out_filters, reps, strides=1, start_with_relu=True, grow_first=True):
        super(Block, self).__init__()
        
        # 修复：正确设置 skip 连接的输出通道数
        if out_filters != in_filters or strides != 1:
            self.skip = nn.Conv2d(in_filters, out_filters, 1, stride=strides, bias=False)
            self.skipbn = nn.BatchNorm2d(out_filters)
        else:
            self.skip = None
        
        self.relu = nn.ReLU(inplace=True)
        rep = []
        
        filters = in_filters
        if grow_first:
            rep.append(self.relu)
            rep.append(SeparableConv2d(in_filters, out_filters, 3, stride=1, padding=1, bias=False))
            rep.append(nn.BatchNorm2d(out_filters))
            filters = out_filters
        else:
            rep.append(SeparableConv2d(in_filters, out_filters, 3, stride=1, padding=1, bias=False))
            rep.append(nn.BatchNorm2d(out_filters))
        
        for i in range(reps - 1):
            rep.append(self.relu)
            rep.append(SeparableConv2d(filters, out_filters, 3, stride=1, padding=1, bias=False))
            rep.append(nn.BatchNorm2d(out_filters))
        
        if not start_with_relu:
            rep = rep[1:]
        else:
            rep[0] = nn.ReLU(inplace=True)
        
        # 只在 strides != 1 时添加 MaxPool
        if strides != 1:
            rep.append(nn.MaxPool2d(3, strides, 1))
        
        self.rep = nn.Sequential(*rep)
    
    def forward(self, inp):
        x = self.rep(inp)
        
        if self.skip is not None:
            skip = self.skip(inp)
            skip = self.skipbn(skip)
        else:
            skip = inp
        
        x = x + skip
        return x


class Xception(nn.Module):
    """
    XceptionNet 模型
    用于二分类任务（真实/换脸）
    """
    
    def __init__(self, num_classes=2, pretrained=False):
        super(Xception, self).__init__()
        
        self.num_classes = num_classes
        
        # 入口层
        self.conv1 = nn.Conv2d(3, 32, 3, 2, 0, bias=False)
        self.bn1 = nn.BatchNorm2d(32)
        self.relu = nn.ReLU(inplace=True)
        
        self.conv2 = nn.Conv2d(32, 64, 3, bias=False)
        self.bn2 = nn.BatchNorm2d(64)
        
        # Xception 模块 - 修复参数
        self.block1 = Block(64, 128, 2, 2, start_with_relu=False, grow_first=True)
        self.block2 = Block(128, 256, 2, 2, start_with_relu=True, grow_first=True)
        self.block3 = Block(256, 728, 2, 2, start_with_relu=True, grow_first=True)
        
        # 中间流 - 8 个重复块
        self.block4 = Block(728, 728, 3, 1, start_with_relu=True, grow_first=True)
        self.block5 = Block(728, 728, 3, 1, start_with_relu=True, grow_first=True)
        self.block6 = Block(728, 728, 3, 1, start_with_relu=True, grow_first=True)
        self.block7 = Block(728, 728, 3, 1, start_with_relu=True, grow_first=True)
        self.block8 = Block(728, 728, 3, 1, start_with_relu=True, grow_first=True)
        self.block9 = Block(728, 728, 3, 1, start_with_relu=True, grow_first=True)
        self.block10 = Block(728, 728, 3, 1, start_with_relu=True, grow_first=True)
        self.block11 = Block(728, 728, 3, 1, start_with_relu=True, grow_first=True)
        
        # 出口流 - 修复：out_filters=1024
        self.block12 = Block(728, 1024, 2, 2, start_with_relu=True, grow_first=True)
        
        self.conv3 = SeparableConv2d(1024, 1536, 3, 1, 1)
        self.bn3 = nn.BatchNorm2d(1536)
        
        self.conv4 = SeparableConv2d(1536, 2048, 3, 1, 1)
        self.bn4 = nn.BatchNorm2d(2048)
        
        # 分类头
        self.dropout = nn.Dropout(0.5)
        self.fc = nn.Linear(2048, num_classes)
        
        # 权重初始化
        if pretrained:
            self._load_pretrained_weights()
        else:
            self._initialize_weights()
    
    def features(self, x):
        x = self.conv1(x)
        x = self.bn1(x)
        x = self.relu(x)
        
        x = self.conv2(x)
        x = self.bn2(x)
        x = self.relu(x)
        
        x = self.block1(x)
        x = self.block2(x)
        x = self.block3(x)
        x = self.block4(x)
        x = self.block5(x)
        x = self.block6(x)
        x = self.block7(x)
        x = self.block8(x)
        x = self.block9(x)
        x = self.block10(x)
        x = self.block11(x)
        x = self.block12(x)
        
        x = self.conv3(x)
        x = self.bn3(x)
        x = self.relu(x)
        
        x = self.conv4(x)
        x = self.bn4(x)
        
        return x
    
    def logits(self, features):
        x = self.relu(features)
        x = F.adaptive_avg_pool2d(x, (1, 1))
        x = x.view(x.size(0), -1)
        return x
    
    def classifier(self, x):
        return self.fc(x)
    
    def forward(self, x):
        x = self.features(x)
        x = self.logits(x)
        x = self.dropout(x)
        x = self.classifier(x)
        return x
    
    def _initialize_weights(self):
        """初始化模型权重"""
        for m in self.modules():
            if isinstance(m, nn.Conv2d):
                nn.init.kaiming_normal_(m.weight, mode='fan_out', nonlinearity='relu')
                if m.bias is not None:
                    nn.init.constant_(m.bias, 0)
            elif isinstance(m, nn.BatchNorm2d):
                nn.init.constant_(m.weight, 1)
                nn.init.constant_(m.bias, 0)
            elif isinstance(m, nn.Linear):
                nn.init.normal_(m.weight, 0, 0.01)
                nn.init.constant_(m.bias, 0)
    
    def _load_pretrained_weights(self):
        """加载预训练权重（可选）"""
        print("加载预训练权重...")
        # 这里可以加载 ImageNet 预训练权重


def xception(num_classes=2, pretrained=False):
    """创建 Xception 模型"""
    model = Xception(num_classes=num_classes, pretrained=pretrained)
    return model


if __name__ == '__main__':
    # 测试模型
    model = xception(num_classes=2)
    print(model)
    
    # 测试前向传播
    input = torch.randn(1, 3, 299, 299)
    output = model(input)
    print(f"输入形状：{input.shape}")
    print(f"输出形状：{output.shape}")