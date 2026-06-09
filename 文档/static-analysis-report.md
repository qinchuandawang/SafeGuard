# SafeGuard 全量静态扫描报告

- 扫描时间：2026-06-09
- 扫描器：自研 py_static_analyzer（规则集见末尾）
- 扫描文件：139 个（Java 103 + Python 24 + JS/Vue 12）
- 总问题数：63

## 严重程度规则

- 严重：FULL-TABLE（无 LIMIT 全表）、POSSIBLE-LEAK（无 finally）、SQL-INJECT-RISK、THREADLOCAL-LEAK、EMPTY-EXCEPT、EVAL、EVAL、EMPTY-CATCH（Java）、XSS、INTERRUPT、LOOSE-EQ
- 中等：PRINT-STACK、PRINT（Java）、CONSOLE、VAR、UNUSED-IMPORT、EMPTY-EXCEPT
- 轻量：HARDCODED-URL（开发期可接受）

## 分类统计

| 规则 | 数量 | 严重程度 |
|:-----|:----:|:---------|
| FULL-TABLE | 17 | 严重 |
| UNUSED-IMPORT | 15 | 轻量 |
| EMPTY-CATCH | 8 | 严重 |
| CONSOLE | 5 | 轻量 |
| PRINT | 3 | 轻量 |
| POSSIBLE-LEAK | 3 | 严重 |
| EMPTY-EXCEPT | 3 | 严重 |
| EVAL | 3 | 严重 |
| INTERRUPT | 2 | 中等 |
| SQL-INJECT-RISK | 2 | 严重 |
| HARDCODED-URL | 2 | 轻量 |

## 详细问题列表


### FULL-TABLE (17 项)

| 文件 | 行号 | 描述 |
|:-----|:----:|:-----|
| `backend/src/main/java/com/sdu/safeguard/controller/AdminApiController.java` | 84 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/controller/AdminApiController.java` | 85 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/controller/AdminApiController.java` | 86 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/controller/AdminApiController.java` | 87 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/controller/AdminController.java` | 92 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/controller/AdminController.java` | 93 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/controller/AdminController.java` | 94 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/controller/AdminController.java` | 95 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/controller/AdminController.java` | 221 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/controller/AdminController.java` | 222 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/controller/AdminController.java` | 223 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/controller/AdminController.java` | 224 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/controller/DetectionRecordController.java` | 67 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/service/AudioTrainingService.java` | 193 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/service/AudioTrainingService.java` | 201 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/service/AudioTrainingService.java` | 240 | 无 WHERE 全表查询/统计 |
| `backend/src/main/java/com/sdu/safeguard/service/KnowledgeService.java` | 30 | 无 WHERE 全表查询/统计 |

### UNUSED-IMPORT (15 项)

| 文件 | 行号 | 描述 |
|:-----|:----:|:-----|
| `ai-services/audio/src/app.py` | 0 | 未使用 import: load_audio |
| `ai-services/audio/src/infer_audio_batch.py` | 0 | 未使用 import: Wav2Vec2ForSequenceClassification |
| `ai-services/audio/src/infer_audio_batch.py` | 0 | 未使用 import: Wav2Vec2Processor |
| `ai-services/audio/src/infer_audio_batch.py` | 0 | 未使用 import: json |
| `ai-services/video/train.py` | 0 | 未使用 import: Path |
| `ai-services/video/train.py` | 0 | 未使用 import: time |
| `ai-services/video/api/app.py` | 0 | 未使用 import: datetime |
| `ai-services/video/api/app.py` | 0 | 未使用 import: json |
| `ai-services/video/models/__init__.py` | 0 | 未使用 import: Xception |
| `ai-services/video/models/__init__.py` | 0 | 未使用 import: xception |
| `ai-services/video/scripts/download_dataset.py` | 0 | 未使用 import: Path |
| `ai-services/video/scripts/download_dataset.py` | 0 | 未使用 import: shutil |
| `ai-services/video/utils/data_loader.py` | 0 | 未使用 import: np |
| `ai-services/video/utils/data_loader.py` | 0 | 未使用 import: pd |
| `ai-services/video/utils/video_processor.py` | 0 | 未使用 import: np |

### EMPTY-CATCH (8 项)

| 文件 | 行号 | 描述 |
|:-----|:----:|:-----|
| `backend/src/main/java/com/sdu/safeguard/controller/LLMController.java` | 39 | 空 catch 块 |
| `backend/src/main/java/com/sdu/safeguard/controller/LLMController.java` | 69 | 空 catch 块 |
| `backend/src/main/java/com/sdu/safeguard/reasoning/CoTService.java` | 104 | 空 catch 块 |
| `backend/src/main/java/com/sdu/safeguard/service/AudioTrainingService.java` | 120 | 空 catch 块 |
| `backend/src/main/java/com/sdu/safeguard/service/DetectionTaskManager.java` | 150 | 空 catch 块 |
| `backend/src/main/java/com/sdu/safeguard/service/DetectionTaskManager.java` | 153 | 空 catch 块 |
| `backend/src/main/java/com/sdu/safeguard/service/DetectionTaskManager.java` | 184 | 空 catch 块 |
| `backend/src/main/java/com/sdu/safeguard/service/LLMService.java` | 247 | 空 catch 块 |

### CONSOLE (5 项)

| 文件 | 行号 | 描述 |
|:-----|:----:|:-----|
| `wechat-app/frontend/app.js` | 24 | console.log 残留 |
| `wechat-app/frontend/app.js` | 89 | console.log 残留 |
| `wechat-app/frontend/app.js` | 92 | console.log 残留 |
| `wechat-app/frontend/app.js` | 166 | console.log 残留 |
| `wechat-app/frontend/utils/request.js` | 88 | console.log 残留 |

### PRINT (3 项)

| 文件 | 行号 | 描述 |
|:-----|:----:|:-----|
| `backend/src/main/java/com/sdu/safeguard/SafeGuardApplication.java` | 53 | System.out 残留 |
| `backend/src/main/java/com/sdu/safeguard/SafeGuardApplication.java` | 55 | System.out 残留 |
| `backend/src/main/java/com/sdu/safeguard/SafeGuardApplication.java` | 57 | System.out 残留 |

### POSSIBLE-LEAK (3 项)

| 文件 | 行号 | 描述 |
|:-----|:----:|:-----|
| `backend/src/main/java/com/sdu/safeguard/agent/tool/AudioDetectionTool.java` | 0 | 疑似上传/检测路径无 finally 清理 |
| `backend/src/main/java/com/sdu/safeguard/controller/AudioTrainingController.java` | 0 | 疑似上传/检测路径无 finally 清理 |
| `backend/src/main/java/com/sdu/safeguard/service/DetectionService.java` | 0 | 疑似上传/检测路径无 finally 清理 |

### EMPTY-EXCEPT (3 项)

| 文件 | 行号 | 描述 |
|:-----|:----:|:-----|
| `ai-services/run.py` | 51 | except Exception: pass |
| `ai-services/audio/src/app.py` | 141 | except Exception: pass |
| `ai-services/video/tests/test_api.py` | 371 | except Exception: pass |

### EVAL (3 项)

| 文件 | 行号 | 描述 |
|:-----|:----:|:-----|
| `ai-services/audio/src/utils.py` | 134 | eval/exec 使用 |
| `ai-services/video/train.py` | 108 | eval/exec 使用 |
| `ai-services/video/api/app.py` | 77 | eval/exec 使用 |

### INTERRUPT (2 项)

| 文件 | 行号 | 描述 |
|:-----|:----:|:-----|
| `backend/src/main/java/com/sdu/safeguard/config/QdrantContainerManager.java` | 177 | Thread.sleep 未处理 InterruptedException |
| `backend/src/main/java/com/sdu/safeguard/config/QdrantContainerManager.java` | 268 | Thread.sleep 未处理 InterruptedException |

### SQL-INJECT-RISK (2 项)

| 文件 | 行号 | 描述 |
|:-----|:----:|:-----|
| `backend/src/main/java/com/sdu/safeguard/mapper/KnowledgeItemMapper.java` | 26 | SQL 字符串拼接 LIKE |
| `backend/src/main/java/com/sdu/safeguard/mapper/KnowledgeItemMapper.java` | 26 | SQL 字符串拼接 LIKE |

### HARDCODED-URL (2 项)

| 文件 | 行号 | 描述 |
|:-----|:----:|:-----|
| `wechat-app/frontend/utils/config.js` | 21 | 硬编码 URL: http://localhost:8080 |
| `wechat-app/frontend/utils/config.js` | 24 | 硬编码 URL: http://localhost:8080 |
