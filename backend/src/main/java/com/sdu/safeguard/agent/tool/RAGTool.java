package com.sdu.safeguard.agent.tool;

import com.sdu.safeguard.rag.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RAGTool implements Tool {

    private final RAGService ragService;

    @Override
    public String getName() {
        return "SEARCH_KNOWLEDGE";
    }

    @Override
    public String getDescription() {
        return "搜索反诈知识库，获取与用户问题相关的诈骗类型、防范建议等参考信息。当需要查询具体诈骗知识、法律法规、防范技巧时使用。";
    }

    @Override
    public ToolResult execute(ToolExecutionRequest request) {
        try {
            String query = request.getInput();
            if (query == null || query.isBlank()) {
                return ToolResult.fail("搜索关键词不能为空");
            }
            var results = ragService.query(query);
            String context = ragService.formatRagContext(results);
            if (context.isBlank()) {
                return ToolResult.ok("未找到相关知识", java.util.Map.of("count", 0));
            }
            return ToolResult.ok(context, java.util.Map.of("count", results.size()));
        } catch (Exception e) {
            log.error("RAG查询失败: {}", e.getMessage());
            return ToolResult.fail("知识检索失败: " + e.getMessage());
        }
    }
}