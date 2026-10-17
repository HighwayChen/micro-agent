package com.govagent.tool.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.govagent.rag.RagClient;
import com.govagent.tool.anno.AiTool;
import com.govagent.tool.anno.AiToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * FAQ 检索 tool：包装 RAG 客户端（mock 实现）。
 */
@Component
public class RagTools extends AbstractMockBinding {

    @Autowired
    private RagClient ragClient;

    @AiTool(name = "search_faq", description = "检索运维知识库 FAQ（服务接入、健康检查、Apollo 配置、权限申请、灰度发布等平台使用问题）。当用户询问『怎么操作/为什么/如何』类平台使用问题时使用。")
    public JsonNode searchFaq(
            @AiToolParam(name = "question", description = "用户问题（保留原意，去掉口语化前缀）") String question) {
        RagClient.RagAnswer answer = ragClient.answer(question, 3);
        ObjectNode result = ok();
        result.put("matched", answer.matched());
        result.put("answer", answer.answer());
        ArrayNode sources = result.putArray("sources");
        answer.sources().forEach(s -> {
            ObjectNode source = ok();
            source.put("title", s.title());
            source.put("snippet", s.snippet());
            source.put("score", s.score());
            sources.add(source);
        });
        return result;
    }
}
