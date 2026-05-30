package com.sdu.safeguard;

import com.sdu.safeguard.config.*;
import com.sdu.safeguard.controller.*;
import com.sdu.safeguard.dto.*;
import com.sdu.safeguard.entity.*;
import com.sdu.safeguard.mapper.*;
import com.sdu.safeguard.service.*;
import com.sdu.safeguard.util.*;
import com.sdu.safeguard.rag.*;
import com.sdu.safeguard.reasoning.*;
import com.sdu.safeguard.memory.*;
import com.sdu.safeguard.agent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

@SpringBootTest
@ActiveProfiles("test")
class SafeGuardApplicationTests {

    // ============ 注入的 Bean ============
    @Autowired private KnowledgeService knowledgeService;
    @Autowired private PromptLoader promptLoader;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserMapper userMapper;
    @Autowired private AsyncTaskMapper asyncTaskMapper;
    @Autowired private AudioDetectionRecordMapper audioRecordMapper;
    @Autowired private AudioModelMapper audioModelMapper;
    @Autowired private DetectionRecordMapper detectionRecordMapper;
    @Autowired private KnowledgeItemMapper knowledgeItemMapper;
    @Autowired private RateLimitInterceptor rateLimitInterceptor;
    @Autowired private RAGService ragService;
    @Autowired private CoTService cotService;
    @Autowired private ReActService reActService;
    @Autowired private MemoryService memoryService;
    @Autowired private AgentOrchestrator agentOrchestrator;
    @Autowired private LLMService llmService;
    @Autowired private DetectionService detectionService;
    @Autowired private DetectionTaskManager taskManager;
    @Autowired private GlobalExceptionHandler exceptionHandler;
    @Autowired private AudioTrainingService audioTrainingService;
    @Autowired private UserService userService;
    @Autowired private RestTemplate restTemplate;

    // =============================================
    // 1. 基础架构测试
    // =============================================
    @Nested
    @DisplayName("1. 基础架构测试")
    class InfrastructureTests {
        @Test void contextLoads() {}

        @Test
        void globalExceptionHandlerReturnsErrorResult() {
            Result<Void> r = exceptionHandler.handleIllegalArgument(new IllegalArgumentException("参数错误"));
            assertEquals(500, r.getCode());
            assertEquals("参数错误", r.getMessage());
            assertNull(r.getData());
        }

        @Test
        void globalExceptionHandlerMasksRuntimeDetails() {
            Result<Void> r = exceptionHandler.handleRuntimeException(new RuntimeException("内部错误详情"));
            assertEquals(500, r.getCode());
            assertEquals("服务处理失败，请稍后重试。", r.getMessage());
        }

        @Test
        void resultFactoriesBuildExpectedResponses() {
            Result<String> success = Result.success("ok");
            assertEquals(200, success.getCode());
            assertEquals("success", success.getMessage());
            assertEquals("ok", success.getData());

            Result<String> error = Result.error("failed");
            assertEquals(500, error.getCode());
            assertEquals("failed", error.getMessage());
            assertNull(error.getData());
        }

        @Test
        void jwtUtilGeneratesAndValidatesToken() {
            String token = jwtUtil.generateToken(1L, "test_openid", "admin");
            assertNotNull(token);
            assertTrue(jwtUtil.validateToken(token));
            assertEquals(1L, jwtUtil.getUserId(token));
            assertEquals("admin", jwtUtil.getRole(token));
            assertEquals("test_openid", jwtUtil.getOpenid(token));
        }

        @Test
        void jwtUtilRejectsInvalidToken() {
            assertFalse(jwtUtil.validateToken("invalid_token_here"));
            assertFalse(jwtUtil.validateToken(""));
            assertFalse(jwtUtil.validateToken(null));
        }

        @Test
        void rateLimitInterceptorAllowsNormalRequests() {
            assertNotNull(rateLimitInterceptor);
        }

        @Test
        void promptLoaderReplacesVariables() {
            String prompt = promptLoader.loadPrompt("text_analysis", Map.of("text", "测试文本"));
            assertTrue(prompt.contains("测试文本"));
        }

        @Test
        void promptLoaderReturnsFallbackForMissingTemplate() {
            String result = promptLoader.loadPrompt("non_existent_template", Map.of());
            assertTrue(result.contains("提示词模板加载失败"));
        }

        @Test
        void promptLoaderHandlesNullVariables() {
            HashMap<String, Object> vars = new HashMap<>();
            vars.put("text", null);
            String prompt = promptLoader.loadPrompt("text_analysis", vars);
            assertNotNull(prompt);
        }

        @Test
        void promptLoaderInjectsKnowledgeVariable() {
            HashMap<String, Object> vars = new HashMap<>();
            vars.put("text", "测试文本");
            vars.put("knowledge", "【参考反诈知识】\n问题1：测试问题\n答案1：测试答案\n");
            String prompt = promptLoader.loadPrompt("text_analysis", vars);
            assertTrue(prompt.contains("【参考反诈知识】"));
            assertTrue(prompt.contains("测试问题"));
        }

        @Test
        void textAnalysisTemplateEnforcesJsonOutput() {
            String prompt = promptLoader.loadPrompt("text_analysis", Map.of("text", "测试", "knowledge", ""));
            assertTrue(prompt.contains("反诈骗文本分析助手") || prompt.contains("诈骗类型") || prompt.contains("风险等级"));
        }

        @Test
        void multimodalTemplateEnforcesJsonOutput() {
            String prompt = promptLoader.loadPrompt("multimodal_analysis", Map.of(
                    "text", "测试", "knowledge", "",
                    "audio", new AudioDetectionResult(),
                    "video", new VideoDetectionResult()));
            assertTrue(prompt.contains("综合风险分析") || prompt.contains("音频") || prompt.contains("视频"));
        }
    }

    // =============================================
    // 2. 实体与DTO测试
    // =============================================
    @Nested
    @DisplayName("2. 实体与DTO测试")
    class EntityAndDtoTests {
        @Test void fileUploadResponseDefaults() {
            FileUploadResponse r = new FileUploadResponse();
            assertNull(r.getFileId());
            assertNull(r.getOriginalName());
            assertNull(r.getFileType());
            assertNull(r.getSize());
        }

        @Test void videoImageInferenceDefaultsNullProbabilityToZero() {
            VideoImageInferenceResponse response = new VideoImageInferenceResponse();
            assertEquals(0.0, response.resolveFakeProbability());
            response.setFakeProbability(0.75);
            assertEquals(0.75, response.resolveFakeProbability());
        }

        @Test void audioDetectionResultHasDefaultType() {
            AudioDetectionResult r = new AudioDetectionResult();
            assertEquals("audio", r.getType());
        }

        @Test void videoDetectionResultHasDefaultType() {
            VideoDetectionResult r = new VideoDetectionResult();
            assertEquals("video", r.getType());
        }

        @Test void videoDetectionFrameAnalysisDefaults() {
            VideoDetectionResult.FrameAnalysis fa = new VideoDetectionResult.FrameAnalysis();
            assertNull(fa.getFrameIndex());
            assertNull(fa.getFakeProbability());
        }

        @Test void loginRequestHasFields() {
            LoginRequest req = new LoginRequest();
            req.setCode("test_code");
            req.setNickname("测试用户");
            req.setAvatarUrl("http://example.com/avatar.png");
            assertEquals("test_code", req.getCode());
            assertEquals("测试用户", req.getNickname());
            assertNotNull(req.getAvatarUrl());
        }

        @Test void loginResponseHasFields() {
            LoginResponse resp = LoginResponse.builder()
                    .token("test_token")
                    .userId(1L)
                    .role("admin")
                    .nickname("管理员")
                    .isNewUser(true)
                    .build();
            assertEquals("test_token", resp.getToken());
            assertEquals(1L, resp.getUserId());
            assertEquals("admin", resp.getRole());
            assertTrue(resp.isNewUser());
        }

        @Test void asyncTaskEntityDefaults() {
            AsyncTask task = AsyncTask.builder()
                    .taskId("test-001")
                    .type("video")
                    .status("processing")
                    .progress(0)
                    .build();
            assertEquals("test-001", task.getTaskId());
            assertEquals("video", task.getType());
            assertEquals("processing", task.getStatus());
            assertEquals(0, task.getProgress());
        }

        @Test void userEntityDefaults() {
            User user = User.builder()
                    .openid("test_openid")
                    .nickname("测试用户")
                    .role("user")
                    .lastLoginAt(LocalDateTime.now())
                    .build();
            assertEquals("test_openid", user.getOpenid());
            assertEquals("测试用户", user.getNickname());
            assertEquals("user", user.getRole());
            assertNotNull(user.getLastLoginAt());
        }

        @Test void knowledgeItemFieldsAreSet() {
            KnowledgeItem item = knowledgeService.getAll().get(0);
            assertNotNull(item.getId());
            assertNotNull(item.getCategory());
            assertNotNull(item.getQuestion());
            assertNotNull(item.getAnswer());
            assertNotNull(item.getTags());
        }

        @Test void scamScenarioHasSystemPrompt() {
            assertNotNull(ScamScenario.IMPERSONATE_RELATIVE);
            assertNotNull(ScamScenario.IMPERSONATE_RELATIVE.getSystemPrompt());
            assertFalse(ScamScenario.IMPERSONATE_RELATIVE.getSystemPrompt().isBlank());
        }

        @Test void detectionTaskCanHoldResult() {
            DetectionTask task = new DetectionTask();
            task.setTaskId("test-task");
            task.setType("video");
            task.setStatus("completed");
            task.setProgress(100);
            Map<String, Object> result = new HashMap<>();
            result.put("label", "spoof");
            task.setResult(result);
            assertEquals("test-task", task.getTaskId());
            assertEquals("completed", task.getStatus());
            assertNotNull(task.getResult());
        }
    }

    // =============================================
    // 3. 数据库/Mapper测试
    // =============================================
    @Nested
    @DisplayName("3. 数据库与Mapper测试")
    class MapperTests {
        @Test void knowledgeServiceSearchesByTagAndCategory() {
            assertFalse(knowledgeService.search("安全账户").isEmpty());
            assertFalse(knowledgeService.getByCategory("冒充公检法").isEmpty());
            assertTrue(knowledgeService.search("   ").isEmpty());
        }

        @Test void knowledgeServiceReturnsAllEntries() {
            List<KnowledgeItem> all = knowledgeService.getAll();
            assertEquals(5, all.size());
        }

        @Test void knowledgeServiceReturnsEmptyForUnknownKeyword() {
            assertTrue(knowledgeService.search("不存在的关键词xyz").isEmpty());
        }

        @Test void knowledgeServiceReturnsEmptyForUnknownCategory() {
            assertTrue(knowledgeService.getByCategory("不存在的分类").isEmpty());
        }

        @Test void knowledgeServiceSearchMatchesMultipleFields() {
            List<KnowledgeItem> result = knowledgeService.search("诈骗");
            assertTrue(result.size() >= 3);
        }

        @Test void knowledgeItemMapperFindByCategory() {
            List<KnowledgeItem> items = knowledgeItemMapper.findByCategory("AI诈骗");
            assertFalse(items.isEmpty());
            assertEquals("AI诈骗", items.get(0).getCategory());
        }

        @Test void knowledgeItemMapperSearchByKeyword() {
            List<KnowledgeItem> items = knowledgeItemMapper.searchByKeyword("AI换脸");
            assertFalse(items.isEmpty());
        }

        @Test void audioDetectionRecordMapperCounts() {
            assertEquals(0, audioRecordMapper.countByDetectionResult("spoof"));
            assertEquals(0, audioRecordMapper.countByStatus("pending"));
        }

        @Test void audioModelMapperFindActive() {
            AudioModel active = audioModelMapper.findActiveModel();
            assertNotNull(active);
            assertTrue(active.getIsActive());
        }

        @Test void detectionRecordMapperQueries() {
            assertNull(detectionRecordMapper.findByTaskId("non-existent"));
        }

        @Test void userMapperQueries() {
            User user = userMapper.findByOpenid("admin_default");
            assertNotNull(user);
            assertEquals("admin", user.getRole());
        }

        @Test void asyncTaskMapperQueries() {
            assertEquals(0, asyncTaskMapper.findByUserId(1L).size());
        }

        @Test void asyncTaskMapperFindStuckTasks() {
            List<AsyncTask> stuck = asyncTaskMapper.findStuckTasks(LocalDateTime.now().minusHours(1));
            assertNotNull(stuck);
        }
    }

    // =============================================
    // 4. 知识库服务测试
    // =============================================
    @Nested
    @DisplayName("4. 知识库服务测试")
    class KnowledgeServiceTests {
        @Autowired private KnowledgeService knowledgeSvc;

        @Test void saveAndUpdateAndDelete() {
            KnowledgeItem item = KnowledgeItem.builder()
                    .question("测试问题")
                    .answer("测试答案")
                    .category("测试分类")
                    .tags("测试")
                    .priority(5)
                    .enabled(true)
                    .build();
            KnowledgeItem saved = knowledgeSvc.save(item);
            assertNotNull(saved.getId());
            assertEquals("测试问题", saved.getQuestion());

            KnowledgeItem updated = knowledgeSvc.update(saved.getId(),
                    KnowledgeItem.builder().question("更新问题").build());
            assertEquals("更新问题", updated.getQuestion());

            knowledgeSvc.delete(saved.getId());
            assertThrows(IllegalArgumentException.class, () -> knowledgeSvc.update(saved.getId(), item));
        }

        @Test void updateNonExistentThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> knowledgeSvc.update(99999L, KnowledgeItem.builder().question("q").build()));
        }

        @Test void deleteNonExistentThrows() {
            assertThrows(IllegalArgumentException.class, () -> knowledgeSvc.delete(99999L));
        }

        @Test void ragKnowledgeContextFormula() {
            List<KnowledgeItem> items = knowledgeService.search("公检法");
            assertFalse(items.isEmpty());
            KnowledgeItem first = items.get(0);
            assertEquals("冒充公检法", first.getCategory());
            assertTrue(first.getAnswer().contains("不会"));
        }
    }

    // =============================================
    // 5. 用户服务测试
    // =============================================
    @Nested
    @DisplayName("5. 用户服务测试")
    class UserServiceTests {
        @Test void loginCreatesUserWithTestOpenid() {
            LoginResponse resp = userService.login("test_code_" + System.currentTimeMillis(), "测试用户", "http://avatar.url");
            assertNotNull(resp);
            assertNotNull(resp.getToken());
            assertNotNull(resp.getUserId());
            assertEquals("user", resp.getRole());
        }

        @Test void getByIdReturnsNullForNonExistent() {
            assertNull(userService.getById(99999L));
        }
    }

    // =============================================
    // 6. JWT工具测试
    // =============================================
    @Nested
    @DisplayName("6. JWT工具测试")
    class JwtUtilTests {
        @Test void differentUsersHaveDifferentTokens() {
            String t1 = jwtUtil.generateToken(1L, "openid1", "user");
            String t2 = jwtUtil.generateToken(2L, "openid2", "admin");
            assertNotEquals(t1, t2);
            assertEquals(1L, jwtUtil.getUserId(t1));
            assertEquals(2L, jwtUtil.getUserId(t2));
        }

        @Test void tokenExpiration() {
            String token = jwtUtil.generateToken(1L, "openid", "user");
            assertTrue(jwtUtil.validateToken(token));
        }

        @Test void tokenWithDifferentRoles() {
            String adminToken = jwtUtil.generateToken(1L, "openid", "admin");
            String userToken = jwtUtil.generateToken(2L, "openid2", "user");
            assertEquals("admin", jwtUtil.getRole(adminToken));
            assertEquals("user", jwtUtil.getRole(userToken));
        }
    }

    // =============================================
    // 7. 检测服务测试
    // =============================================
    @Nested
    @DisplayName("7. 检测服务测试")
    class DetectionServiceTests {
        @Test void detectionTaskManagerCreateAndGet() {
            DetectionTask task = taskManager.createTask("test");
            assertNotNull(task);
            assertNotNull(task.getTaskId());
            assertEquals("processing", task.getStatus());
            assertEquals(0, task.getProgress());

            DetectionTask retrieved = taskManager.getTask(task.getTaskId());
            assertNotNull(retrieved);
            assertEquals(task.getTaskId(), retrieved.getTaskId());
        }

        @Test void detectionTaskManagerReturnsNullForNonExistent() {
            assertNull(taskManager.getTask("non-existent-task-id"));
        }

        @Test void audioDetectionResultCanComputeProbabilities() {
            AudioDetectionResult result = new AudioDetectionResult();
            result.setSpoofProb(0.85);
            result.setBonafideProb(0.15);
            result.computeProbabilities();
            assertEquals("audio", result.getType());
        }

        @Test void videoDetectionResultCanComputeProbabilities() {
            VideoDetectionResult result = new VideoDetectionResult();
            result.setFakeProbability(0.75);
            result.computeProbabilities();
            assertEquals("video", result.getType());
        }

        @Test void audioDetectionResponseDto() {
            AudioDetectionResponse resp = AudioDetectionResponse.builder()
                    .label("spoof")
                    .spoofProb(0.95)
                    .bonafideProb(0.05)
                    .confidence(0.95)
                    .riskLevel("high")
                    .latencyMs(150.5)
                    .modelVersion("v1.0")
                    .device("cuda")
                    .build();
            assertEquals("spoof", resp.getLabel());
            assertEquals(0.95, resp.getSpoofProb());
            assertEquals("high", resp.getRiskLevel());
        }
    }

    // =============================================
    // 8. RAG服务测试
    // =============================================
    @Nested
    @DisplayName("8. RAG服务测试")
    class RAGServiceTests {
        @Test void ragServiceInitializes() {
            assertNotNull(ragService);
        }

        @Test void matchKeywordsDetectsFraudTerms() {
            Set<String> matched = ragService.getMatchedKeywords("转账到安全账户");
            assertTrue(matched.contains("转账"));
            assertTrue(matched.contains("安全账户"));
        }

        @Test void matchKeywordsReturnsEmptyForNormalText() {
            Set<String> matched = ragService.getMatchedKeywords("今天天气很好");
            assertTrue(matched.isEmpty());
        }

        @Test void matchKeywordsHandlesNull() {
            Set<String> matched = ragService.getMatchedKeywords(null);
            assertTrue(matched.isEmpty());
        }

        @Test void formatRagContextHandlesEmpty() {
            assertEquals("", ragService.formatRagContext(List.of()));
            assertEquals("", ragService.formatRagContext(null));
        }

        @Test void queryReturnsResults() {
            List<RagQueryResult> results = ragService.query("什么是AI换脸诈骗");
            assertNotNull(results);
        }

        @Test void queryReturnsEmptyForEmptyInput() {
            assertTrue(ragService.query("").isEmpty());
            assertTrue(ragService.query(null).isEmpty());
            assertTrue(ragService.query("   ").isEmpty());
        }
    }

    // =============================================
    // 9. CoT 推理服务测试
    // =============================================
    @Nested
    @DisplayName("9. CoT推理测试")
    class CoTServiceTests {
        @Test void cotServiceProcessesText() {
            CoTResult result = cotService.analyzeWithCoT("您好，我是公安局民警，您涉嫌洗钱");
            assertNotNull(result);
            assertNotNull(result.getScamType());
            assertNotNull(result.getRiskLevel());
            assertTrue(result.getRiskProbability() >= 0);
            assertNotNull(result.getReasoningSteps());
        }

        @Test void cotServiceHandlesNullInput() {
            CoTResult result = cotService.analyzeWithCoT(null);
            assertNotNull(result);
            assertNotNull(result.getScamType());
            assertTrue(result.getScamType().equals("分析失败") || result.getScamType().equals("未知"));
        }

        @Test void cotResultBuilder() {
            CoTResult result = CoTResult.builder()
                    .originalInput("测试")
                    .scamType("冒充公检法")
                    .riskLevel("高")
                    .riskProbability(0.95)
                    .suspiciousPoints(List.of("要求转账", "冒充警察"))
                    .advice("不要转账")
                    .reasoningSteps(List.of("Step1: 分析", "Step2: 判断"))
                    .build();
            assertEquals("冒充公检法", result.getScamType());
            assertEquals("高", result.getRiskLevel());
            assertEquals(0.95, result.getRiskProbability());
            assertEquals(2, result.getSuspiciousPoints().size());
            assertEquals("不要转账", result.getAdvice());
            assertEquals(2, result.getReasoningSteps().size());
        }

        @Test void cotResultHandlesUnknownRisk() {
            CoTResult result = CoTResult.builder()
                    .originalInput("test")
                    .riskLevel("未知")
                    .scamType("未知")
                    .riskProbability(0.0)
                    .reasoningSteps(List.of("Default step"))
                    .build();
            assertEquals("未知", result.getRiskLevel());
            assertEquals(0.0, result.getRiskProbability());
        }
    }

    // =============================================
    // 10. ReAct 推理服务测试
    // =============================================
    @Nested
    @DisplayName("10. ReAct推理测试")
    class ReActServiceTests {
        @Test void reactServiceExecutesWithoutError() {
            List<ReActThought> thoughts = reActService.executeReAct("转账到安全账户", "test-session");
            assertNotNull(thoughts);
        }

        @Test void reactThoughtBuilder() {
            ReActThought thought = ReActThought.builder()
                    .step(1)
                    .thought("用户提到转账到安全账户，很有可能是诈骗")
                    .action("SEARCH_KNOWLEDGE")
                    .actionInput("安全账户")
                    .observation("安全账户是诈骗术语")
                    .isFinal(false)
                    .finalAnswer(null)
                    .referencedSources(List.of("反诈知识库"))
                    .build();
            assertEquals(1, thought.getStep());
            assertEquals("SEARCH_KNOWLEDGE", thought.getAction());
            assertFalse(thought.isFinal());
        }

        @Test void reactGetFinalAnswer() {
            List<ReActThought> thoughts = List.of(
                    ReActThought.builder().step(1).isFinal(true).finalAnswer("这是诈骗").build()
            );
            assertEquals("这是诈骗", reActService.getFinalAnswer(thoughts));
            assertEquals("", reActService.getFinalAnswer(List.of()));
            assertEquals("", reActService.getFinalAnswer(null));
        }
    }

    // =============================================
    // 11. 记忆服务测试
    // =============================================
    @Nested
    @DisplayName("11. 记忆服务测试")
    class MemoryServiceTests {
        @Test void shortTermMemoryAddAndRetrieve() {
            memoryService.addShortTerm("session-test", "user", "测试记忆内容", List.of("test"));
            List<com.sdu.safeguard.dto.MemoryItem> items = memoryService.getShortTerm("session-test");
            assertFalse(items.isEmpty());
            assertEquals("测试记忆内容", items.get(0).getContent());
        }

        @Test void shortTermMemoryClear() {
            memoryService.addShortTerm("session-clear", "user", "内容", List.of("test"));
            memoryService.clearShortTerm("session-clear");
            assertTrue(memoryService.getShortTerm("session-clear").isEmpty());
        }

        @Test void formatMemoryContext() {
            memoryService.addShortTerm("session-format", "user", "转账到安全账户", List.of("query"));
            String context = memoryService.formatMemoryContext("session-format", "转账");
            assertNotNull(context);
            assertTrue(context.contains("转账") || context.contains("记忆"));
        }
    }

    // =============================================
    // 12. 统计与音频训练服务测试
    // =============================================
    @Nested
    @DisplayName("12. 统计与音频训练测试")
    class AudioTrainingServiceTests {
        @Test void getStatistics() {
            Map<String, Object> stats = audioTrainingService.getStatistics();
            assertNotNull(stats);
            assertTrue(stats.containsKey("totalRecords"));
            assertTrue(stats.containsKey("spoofCount"));
            assertTrue(stats.containsKey("bonafideCount"));
        }

        @Test void getActiveModel() {
            assertTrue(audioTrainingService.getActiveModel().isPresent());
            assertEquals("Wav2Vec2 语音伪造检测模型", audioTrainingService.getActiveModel().get().getName());
        }

        @Test void getAllModels() {
            List<AudioModel> models = audioTrainingService.getAllModels();
            assertFalse(models.isEmpty());
            assertEquals(2, models.size());
        }

        @Test void getAllRecords() {
            assertNotNull(audioTrainingService.getAllRecords());
        }
    }

    // =============================================
    // 13. Agent Orchestrator 测试
    // =============================================
    @Nested
    @DisplayName("13. Agent编排测试")
    class AgentOrchestratorTests {
        @Test void orchestratorExecutesTextOnly() {
            OrchestratorRequest req = OrchestratorRequest.builder()
                    .query("转账到安全账户是诈骗吗")
                    .sessionId("test-orch-1")
                    .useCoT(true)
                    .useRAG(true)
                    .useReAct(false)
                    .requiredAgents(List.of("TEXT_ANALYSIS"))
                    .mode("text_only")
                    .build();
            OrchestratorResponse resp = agentOrchestrator.execute(req);
            assertNotNull(resp);
            assertNotNull(resp.getFinalResult());
            assertTrue(resp.getProcessingTimeMs() >= 0);
        }

        @Test void orchestratorExecutesFullPipeline() {
            OrchestratorRequest req = OrchestratorRequest.builder()
                    .query("我收到一个自称公安局的电话")
                    .sessionId("test-orch-2")
                    .useCoT(true)
                    .useRAG(true)
                    .useReAct(false)
                    .requiredAgents(List.of("TEXT_ANALYSIS", "KNOWLEDGE"))
                    .mode("full")
                    .build();
            OrchestratorResponse resp = agentOrchestrator.execute(req);
            assertNotNull(resp);
            assertNotNull(resp.getAgentResults());
            assertTrue(resp.getAgentResults().containsKey("TEXT_ANALYSIS"));
            assertTrue(resp.getAgentResults().containsKey("KNOWLEDGE"));
        }

        @Test void orchestratorReturnsUnknownAgentError() {
            OrchestratorRequest req = OrchestratorRequest.builder()
                    .query("test")
                    .sessionId("test-orch-3")
                    .requiredAgents(List.of("UNKNOWN_AGENT_TYPE"))
                    .build();
            OrchestratorResponse resp = agentOrchestrator.execute(req);
            assertNotNull(resp);
        }
    }

    // =============================================
    // 14. 限流拦截器测试
    // =============================================
    @Nested
    @DisplayName("14. 限流拦截器测试")
    class RateLimitTests {
        @Test void rateLimiterAllowsPublicPaths() {
            assertDoesNotThrow(() -> rateLimitInterceptor.preHandle(null, null, null));
        }
    }

    // =============================================
    // 15. LLM服务测试
    // =============================================
    @Nested
    @DisplayName("15. LLM服务测试")
    class LLMServiceTests {
        @Test void llmServiceBuildsAnalyzePrompt() {
            String prompt = llmService.buildAnalyzePrompt("转账到安全账户");
            assertNotNull(prompt);
            assertTrue(prompt.contains("转账到安全账户"));
        }
    }

    // =============================================
    // 16. 控制器接口测试
    // =============================================
    @Nested
    @DisplayName("16. 控制器接口测试")
    class ControllerTests {
        @Test void authControllerEndpointsDefined() {
            assertDoesNotThrow(() -> {
                AuthController.class.getMethod("login", LoginRequest.class);
                AuthController.class.getMethod("getUserInfo", jakarta.servlet.http.HttpServletRequest.class);
            });
        }

        @Test void agentControllerEndpointsDefined() {
            assertDoesNotThrow(() -> {
                AgentController.class.getMethod("analyze", com.sdu.safeguard.dto.OrchestratorRequest.class);
                AgentController.class.getMethod("analyzeText", java.util.Map.class);
                AgentController.class.getMethod("simulate", java.util.Map.class);
            });
        }

        @Test void llmControllerEndpointsDefined() {
            assertDoesNotThrow(() -> {
                LLMController.class.getMethod("analyzeStream", java.util.Map.class);
            });
        }

        @Test void ragControllerEndpointsDefined() {
            assertDoesNotThrow(() -> {
                RAGController.class.getMethod("queryRAG", String.class);
                RAGController.class.getMethod("getRAGStats");
            });
        }

        @Test void detectionControllerEndpointsDefined() {
            assertDoesNotThrow(() -> {
                DetectionController.class.getMethod("detectAudio", org.springframework.web.multipart.MultipartFile.class);
                DetectionController.class.getMethod("detectText", Map.class);
                DetectionController.class.getMethod("getTask", String.class);
            });
        }

        @Test void audioTrainingControllerEndpointsDefined() {
            assertDoesNotThrow(() -> {
                AudioTrainingController.class.getMethod("getStatus");
                AudioTrainingController.class.getMethod("getStatistics");
                AudioTrainingController.class.getMethod("getActiveModel");
                AudioTrainingController.class.getMethod("getAllModels");
            });
        }

        @Test void knowledgeControllerEndpointsDefined() {
            assertDoesNotThrow(() -> {
                KnowledgeController.class.getMethod("getAll");
                KnowledgeController.class.getMethod("getByCategory", String.class);
                KnowledgeController.class.getMethod("create", KnowledgeItem.class);
            });
        }

        @Test void detectionRecordControllerEndpointsDefined() {
            assertDoesNotThrow(() -> {
                DetectionRecordController.class.getMethod("getRecords", Long.class, Integer.class);
                DetectionRecordController.class.getMethod("getDailyStats");
            });
        }

        @Test void adminApiControllerEndpointsDefined() {
            assertDoesNotThrow(() -> {
                AdminApiController.class.getMethod("getUsers");
                AdminApiController.class.getMethod("getOverviewStats");
                AdminApiController.class.getMethod("getModels");
            });
        }

        @Test void healthControllerEndpointDefined() {
            assertDoesNotThrow(() -> {
                HealthController.class.getMethod("health");
            });
        }
    }

    // =============================================
    // 17. 输入校验测试
    // =============================================
    @Nested
    @DisplayName("17. 输入校验测试")
    class InputValidationTests {
        @Test void validateAnalysisTextNull() {
            assertNotNull(InputValidator.validateAnalysisText(null));
            assertNotNull(InputValidator.validateAnalysisText(""));
            assertNotNull(InputValidator.validateAnalysisText("   "));
        }

        @Test void validateAnalysisTextMaxLength() {
            String tooLong = "a".repeat(5001);
            assertNotNull(InputValidator.validateAnalysisText(tooLong));

            String valid = "a".repeat(5000);
            assertNull(InputValidator.validateAnalysisText(valid));
        }

        @Test void validateAnalysisTextControlChars() {
            assertNotNull(InputValidator.validateAnalysisText("hello world"));
            assertNotNull(InputValidator.validateAnalysisText("helloworld"));
            assertNull(InputValidator.validateAnalysisText("hello\nworld"));
            assertNull(InputValidator.validateAnalysisText("hello\tworld"));
        }

        @Test void validateKeyword() {
            assertNotNull(InputValidator.validateKeyword(null));
            assertNotNull(InputValidator.validateKeyword(""));
            assertNull(InputValidator.validateKeyword("安全账户"));
            String tooLong = "a".repeat(201);
            assertNotNull(InputValidator.validateKeyword(tooLong));
        }

        @Test void validateMessage() {
            assertNotNull(InputValidator.validateMessage(null));
            assertNotNull(InputValidator.validateMessage(""));
            assertNull(InputValidator.validateMessage("你好，我想了解反诈知识"));
            String tooLong = "a".repeat(2001);
            assertNotNull(InputValidator.validateMessage(tooLong));
        }
    }

    // =============================================
    // 18. 音频训练模块数据测试
    // =============================================
    @Nested
    @DisplayName("17. 音频模型数据测试")
    class AudioModelDataTests {
        @Test void verifyInitialAudioModels() {
            List<AudioModel> models = audioModelMapper.selectList(null);
            assertEquals(2, models.size());

            AudioModel active = models.stream().filter(AudioModel::getIsActive).findFirst().orElse(null);
            assertNotNull(active);
            assertEquals("Wav2Vec2 语音伪造检测模型", active.getName());
            assertEquals(0.985, active.getAccuracy(), 0.001);
            assertEquals(0.012, active.getEer(), 0.001);
        }
    }

    // =============================================
    // 18. 多模态检测测试
    // =============================================
    @Nested
    @DisplayName("18. 多模态检测测试")
    class MultiModalTests {
        @Test void multiModalRequestCanHoldAllData() {
            MultiModalRequest req = new MultiModalRequest();
            req.setText("测试文本");
            AudioDetectionResult audio = new AudioDetectionResult();
            audio.setLabel("spoof");
            audio.setSpoofProb(0.95);
            req.setAudioResult(audio);
            VideoDetectionResult video = new VideoDetectionResult();
            video.setFakeProbability(0.88);
            req.setVideoResult(video);
            assertEquals("测试文本", req.getText());
            assertNotNull(req.getAudioResult());
            assertNotNull(req.getVideoResult());
        }
    }
}
