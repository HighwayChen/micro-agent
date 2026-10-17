package com.govagent.rag;

import java.util.List;

/**
 * RAG 检索客户端。占位契约：接入真实 LangChain RAG 服务时替换实现即可。
 */
public interface RagClient {

    RagAnswer answer(String question, int topK);

    record RagSource(String title, String snippet, double score) {
    }

    record RagAnswer(String answer, List<RagSource> sources, boolean matched) {

        public static RagAnswer notMatched(String question) {
            return new RagAnswer("知识库中未找到相关问题。可以换个问法，或尝试咨询对应的平台负责人。",
                    List.of(), false);
        }
    }
}
