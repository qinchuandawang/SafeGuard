package com.sdu.safeguard.service;

import com.sdu.safeguard.entity.KnowledgeItem;
import com.sdu.safeguard.mapper.KnowledgeItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeItemMapper mapper;

    public List<KnowledgeItem> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return mapper.searchByKeyword(keyword);
    }

    public List<KnowledgeItem> getByCategory(String category) {
        return mapper.findByCategory(category);
    }

    public List<KnowledgeItem> getAll() {
        // 全表查询 LIMIT 兜底（最近 1000 条）
        return mapper.findRecent(1000);
    }

    @Transactional
    public KnowledgeItem save(KnowledgeItem item) {
        mapper.insert(item);
        return item;
    }

    @Transactional
    public KnowledgeItem update(Long id, KnowledgeItem updated) {
        KnowledgeItem existing = mapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("知识条目不存在: " + id);
        }
        if (updated.getCategory() != null) {
            existing.setCategory(updated.getCategory());
        }
        if (updated.getQuestion() != null) {
            existing.setQuestion(updated.getQuestion());
        }
        if (updated.getAnswer() != null) {
            existing.setAnswer(updated.getAnswer());
        }
        if (updated.getTags() != null) {
            existing.setTags(updated.getTags());
        }
        if (updated.getPriority() != null) {
            existing.setPriority(updated.getPriority());
        }
        if (updated.getEnabled() != null) {
            existing.setEnabled(updated.getEnabled());
        }
        mapper.updateById(existing);
        return existing;
    }

    @Transactional
    public void delete(Long id) {
        if (mapper.selectById(id) == null) {
            throw new IllegalArgumentException("知识条目不存在: " + id);
        }
        mapper.deleteById(id);
    }
}