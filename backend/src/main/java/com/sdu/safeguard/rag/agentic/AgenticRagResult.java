package com.sdu.safeguard.rag.agentic;

import com.sdu.safeguard.dto.RagQueryResult;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Agentic RAG 一次完整执行的输出：最终检索结果 + 可审计的执行痕迹。
 * 痕迹字段用于测评、调试和面试叙事（检索 → 反思 → 定向补检 的完整闭环）。
 */
@Data
@Builder
public class AgenticRagResult {
    /** 最终聚合后的 Top-K 结果 */
    private List<RagQueryResult> results;
    /** 原始查询 */
    private String query;
    /** 本轮执行实际检索过的子查询列表（含原始查询与补检查询） */
    private List<String> subQueries;
    /** 执行痕迹：analyze / retrieve[n] / reflect[n] / merge 等步骤 */
    private List<String> trace;
    /** 实际迭代轮数 */
    private int iterations;
    /** 最终反思覆盖度（0~1） */
    private double coverageScore;
    /** 最终反思是否判定为充分 */
    private boolean sufficient;
    /** 是否发生过定向补检 */
    private boolean refined;
    /** 整轮耗时 ms */
    private long costMs;
    /** 反思后判定为缺失的知识面（关键词） */
    private List<String> missingFacets;
}
