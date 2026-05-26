# DeepFake 视频换脸检测 API 接口文档

## 1. 服务信息

| 项目 | 说明 |
|------|------|
| 服务名称 | DeepFake 视频换脸检测服务 |
| 模型 | XceptionNet（训练至验证准确率 85.00%） |
| 检测能力 | 真实 / 伪造 二分类 + 伪造概率 |
| 支持格式 | 图片：JPG / PNG；视频：MP4 / AVI 等 |
| 运行设备 | NVIDIA RTX 3060 Laptop GPU (CUDA) |

---

## 2. 访问地址

| 环境 | 地址 |
|------|------|
| 本地 | `http://127.0.0.1:5000` |
| 局域网 | `http://10.27.242.183:5000` |

> 启动命令：`python api/app.py`

---

## 3. 接口列表

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| POST | `/api/detect/image` | 图片换脸检测 |
| POST | `/api/detect/video` | 视频换脸检测 |
| POST | `/api/detect/text` | 文本检测（预留） |

---

## 4. 通用说明

### 4.1 Content-Type

- 文件上传接口：`multipart/form-data`，字段名统一为 `file`
- JSON 接口：`application/json`

### 4.2 统一响应格式

```json
// 成功
{
    "success": true,
    "data": { ... }
}

// 失败
{
    "success": false,
    "error": "错误描述"
}
```

### 4.3 状态码

| 状态码 | 含义 |
|:------:|------|
| 200 | 请求成功 |
| 400 | 请求参数错误（缺文件、无人脸等） |
| 500 | 服务器内部错误 |

---

## 5. 接口详情

### 5.1 健康检查

```
GET /api/health
```

**请求**：无参数

**响应示例**：

```json
{
    "device": "cuda",
    "model_loaded": true,
    "status": "healthy"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | string | `"healthy"` 表示服务正常 |
| `device` | string | 推理设备（`cuda` / `cpu`） |
| `model_loaded` | bool | 模型是否加载成功 |

**调用示例**：

```bash
curl http://127.0.0.1:5000/api/health
```

```python
import requests
resp = requests.get("http://127.0.0.1:5000/api/health")
print(resp.json())
```

---

### 5.2 图片换脸检测

```
POST /api/detect/image
Content-Type: multipart/form-data
```

#### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| `file` | File | 是 | 待检测的图片文件（JPG / PNG） |

#### 成功响应（200）

```json
{
    "success": true,
    "data": {
        "is_fake": false,
        "fake_probability": 0.0024,
        "real_probability": 0.9976,
        "faces_detected": 1,
        "faces": [
            {
                "is_fake": false,
                "fake_probability": 0.0024,
                "real_probability": 0.9976,
                "face_location": [52, 178, 230, 0]
            }
        ]
    }
}
```

#### 响应字段说明

**data 层**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `is_fake` | bool | 整体判定：`true`=伪造，`false`=真实 |
| `fake_probability` | float | 伪造概率（0.0 ~ 1.0） |
| `real_probability` | float | 真实概率（0.0 ~ 1.0） |
| `faces_detected` | int | 检测到的人脸数量 |
| `faces` | array | 每张人脸的详细检测结果 |

**faces[i] 层**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `is_fake` | bool | 该人脸的判定 |
| `fake_probability` | float | 该人脸的伪造概率 |
| `real_probability` | float | 该人脸的真实概率 |
| `face_location` | [int] | 人脸位置 `[top, right, bottom, left]` |

#### 失败响应（400）

```json
// 未上传文件
{"success": false, "error": "未找到文件"}

// 文件名为空
{"success": false, "error": "文件名为空"}

// 未检测到人脸
{"success": false, "error": "未检测到人脸"}
```

#### 调用示例

**curl**：

```bash
curl -X POST http://127.0.0.1:5000/api/detect/image \
     -F "file=@/path/to/image.jpg"
```

**Python**：

```python
import requests

url = "http://127.0.0.1:5000/api/detect/image"
with open("test.jpg", "rb") as f:
    resp = requests.post(url, files={"file": f})

result = resp.json()
if result["success"]:
    data = result["data"]
    if data["is_fake"]:
        print(f"检测到换脸！伪造概率：{data['fake_probability']:.1%}")
    else:
        print(f"人脸真实，真实概率：{data['real_probability']:.1%}")
else:
    print(f"检测失败：{result['error']}")
```

---

### 5.3 视频换脸检测

```
POST /api/detect/video
Content-Type: multipart/form-data
```

#### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| `file` | File | 是 | 待检测的视频文件（MP4 / AVI 等） |

> 默认提取前 30 帧进行检测

#### 成功响应（200）

```json
{
    "success": true,
    "data": {
        "is_fake": true,
        "average_fake_probability": 0.8523,
        "max_fake_probability": 0.9801,
        "total_frames": 30,
        "total_faces": 45,
        "frame_results": [
            {
                "frame_name": "frame_0000.jpg",
                "faces": [
                    {
                        "is_fake": true,
                        "fake_probability": 0.9521,
                        "real_probability": 0.0479,
                        "face_location": [52, 178, 230, 0]
                    }
                ]
            },
            {
                "frame_name": "frame_0001.jpg",
                "faces": [
                    {
                        "is_fake": true,
                        "fake_probability": 0.9801,
                        "real_probability": 0.0199,
                        "face_location": [55, 182, 233, 3]
                    }
                ]
            }
        ]
    }
}
```

#### 响应字段说明

**data 层**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `is_fake` | bool | 整体判定（平均伪造概率 > 0.5） |
| `average_fake_probability` | float | 所有检测到人脸的平均伪造概率 |
| `max_fake_probability` | float | 所有帧中最高伪造概率 |
| `total_frames` | int | 实际处理的帧数 |
| `total_faces` | int | 所有帧中检测到的总人脸数 |
| `frame_results` | array | 逐帧检测详情 |

**frame_results[i] 层**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `frame_name` | string | 帧文件名 |
| `faces` | array | 该帧中所有人脸的检测结果（格式同图片接口 `faces`） |

#### 失败响应（400）

同图片检测失败响应格式。

#### 调用示例

**curl**：

```bash
curl -X POST http://127.0.0.1:5000/api/detect/video \
     -F "file=@/path/to/video.mp4"
```

**Python**：

```python
import requests

url = "http://127.0.0.1:5000/api/detect/video"
with open("test.mp4", "rb") as f:
    resp = requests.post(url, files={"file": f})

result = resp.json()
if result["success"]:
    data = result["data"]
    print(f"检测帧数：{data['total_frames']}")
    print(f"检测人脸数：{data['total_faces']}")
    print(f"平均伪造概率：{data['average_fake_probability']:.1%}")
    print(f"最高伪造概率：{data['max_fake_probability']:.1%}")
    print(f"判定结果：{'伪造' if data['is_fake'] else '真实'}")
else:
    print(f"检测失败：{result['error']}")
```

---

### 5.4 文本检测（预留接口）

```
POST /api/detect/text
Content-Type: application/json
```

> 此接口为预留接口，暂不提供实际检测能力，由大模型直接处理。

**请求参数**：

```json
{
    "text": "要检测的文本内容"
}
```

**响应**：

```json
{
    "success": true,
    "data": {
        "text": "要检测的文本内容",
        "message": "文本检测由大模型处理，请调用大模型接口"
    }
}
```

---

## 6. 大模型集成建议

### 6.1 推荐调用流程

```
用户上传可疑音视频
    │
    ▼
┌──────────────┐
│   大模型入口   │  ← DeepSeek / 千问
└──────┬───────┘
       │
       ├──→ 检测类型=图片 → POST /api/detect/image
       ├──→ 检测类型=视频 → POST /api/detect/video
       └──→ 检测类型=文本 → 大模型自行处理
       │
       ▼
┌──────────────┐
│  获取检测结果  │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ 大模型综合分析 │
│ 生成风险报告   │
└──────────────┘
```

### 6.2 结果解读建议

大模型可根据 `fake_probability` 生成通俗易懂的报告：

| 伪造概率 | 风险等级 | 建议话术 |
|:--------:|:------:|------|
| < 0.1 | 低风险 | "该内容真实性较高，未发现明显换脸痕迹" |
| 0.1 ~ 0.5 | 中风险 | "该内容存在可疑特征，建议进一步核实" |
| 0.5 ~ 0.8 | 高风险 | "该内容存在明显换脸痕迹，很可能为伪造" |
| > 0.8 | 极高风险 | "该内容高度疑似 AI 换脸伪造，请勿轻信" |

### 6.3 大模型调用示例

```python
import requests

def detect_deepfake(file_path, file_type="image"):
    """大模型调用换脸检测服务"""
    
    if file_type == "image":
        url = "http://127.0.0.1:5000/api/detect/image"
        timeout = 30
    elif file_type == "video":
        url = "http://127.0.0.1:5000/api/detect/video"
        timeout = 120
    else:
        return None
    
    with open(file_path, "rb") as f:
        resp = requests.post(url, files={"file": f}, timeout=timeout)
    
    if resp.status_code != 200:
        return {"error": f"检测服务异常：HTTP {resp.status_code}"}
    
    result = resp.json()
    
    if not result["success"]:
        return {"error": result.get("error", "未知错误")}
    
    data = result["data"]
    
    # 生成面向用户的风险报告
    risk_level = "低"
    if data["fake_probability"] > 0.8:
        risk_level = "极高"
    elif data["fake_probability"] > 0.5:
        risk_level = "高"
    elif data["fake_probability"] > 0.1:
        risk_level = "中"
    
    return {
        "is_fake": data["is_fake"],
        "fake_probability": data["fake_probability"],
        "risk_level": risk_level,
        "raw_data": data
    }
```

---

## 7. 性能参考

| 指标 | 数值 |
|------|------|
| 图片检测速度 | ~2.1 秒/张（含人脸检测） |
| 视频检测速度 | ~30 秒/段（30 帧） |
| 模型大小 | ~90 MB |
| 最大并发 | 单线程（Flask 开发模式） |

---

## 8. 错误处理建议

```python
import requests
from requests.exceptions import ConnectionError, Timeout

def safe_detect(file_path, file_type="image", retries=3):
    """带重试和异常处理的检测调用"""
    
    for attempt in range(retries):
        try:
            url = f"http://127.0.0.1:5000/api/detect/{file_type}"
            with open(file_path, "rb") as f:
                resp = requests.post(url, files={"file": f}, timeout=60)
            
            if resp.status_code == 200:
                return resp.json()
            elif resp.status_code == 400:
                return resp.json()  # 业务错误，不重试
            else:
                print(f"服务器错误，重试 {attempt+1}/{retries}")
                
        except ConnectionError:
            print(f"无法连接服务，重试 {attempt+1}/{retries}")
        except Timeout:
            print(f"请求超时，重试 {attempt+1}/{retries}")
        except Exception as e:
            print(f"未知错误：{e}")
    
    return {"success": False, "error": "服务不可用，请稍后重试"}
```

---

## 9. 更新日志

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-05 | 初始版本，支持图片/视频换脸检测 |