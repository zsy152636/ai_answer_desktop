package com.aia.answer.web;

import com.aia.answer.common.ApiException;
import com.aia.answer.common.ApiResponse;
import com.aia.answer.common.Json;
import com.aia.answer.domain.DocumentDtos;
import com.aia.answer.domain.QuestionDtos;
import com.aia.answer.service.AiClient;
import com.aia.answer.service.DocumentService;
import com.aia.answer.service.LlmProfileService;
import com.aia.answer.service.QuestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 学习解释（契约 §7 的对外入口、§11.3 的检索增强）。
 *
 * <p>Java 侧只做提示词素材组装、检索依据注入与**引用回映射**，真正的模型调用在 Python 服务里完成；
 * 题目与检索片段一律作为不可信输入交给 Python 做防注入包裹（开发文档 §525）。
 *
 * <p>引用可信度：模型可能臆造 refId，因此这里的 `citations` 只保留「本次实际提供过的 refId」，
 * 并补上 `documentId`/`chunkId`/`pageNo`，保证每条引用都能回到
 * `GET /api/v1/documents/{id}/chunks` 逐字核对（阶段 6 完成定义）。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private static final int DEFAULT_KNOWLEDGE_LIMIT = 5;
    private static final int MAX_KNOWLEDGE_LIMIT = 10;
    private static final int MAX_QUERY_CHARS = 200;
    private static final int CITATION_SNIPPET_CHARS = 200;

    private final QuestionService questionService;
    private final LlmProfileService llmProfileService;
    private final AiClient aiClient;
    private final DocumentService documentService;
    private final Json json;

    public AiController(QuestionService questionService, LlmProfileService llmProfileService, AiClient aiClient,
                        DocumentService documentService, Json json) {
        this.questionService = questionService;
        this.llmProfileService = llmProfileService;
        this.aiClient = aiClient;
        this.documentService = documentService;
        this.json = json;
    }

    /**
     * 题目讲解：{@code {"userAnswer": {...}, "profileId": "...", "useKnowledgeBase": false,
     * "knowledgeLimit": 5, "query": "..."}} 均可选。
     */
    @PostMapping("/questions/{id}/explain")
    public ApiResponse<JsonNode> explain(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        QuestionDtos.QuestionView question = questionService.get(id, null);
        if (question.currentVersion() == null) {
            throw ApiException.notFound("题目当前版本");
        }
        ObjectNode payload = json.object();
        ObjectNode questionNode = payload.putObject("question");
        questionNode.put("stem", question.currentVersion().stem());
        questionNode.put("questionType", question.questionType());
        questionNode.set("options", json.mapper().valueToTree(question.currentVersion().options()));
        questionNode.set("answer", question.currentVersion().answer());
        if (question.currentVersion().explanation() != null) {
            questionNode.put("explanation", question.currentVersion().explanation());
        }
        if (body != null && body.hasNonNull("userAnswer")) {
            payload.set("userAnswer", body.get("userAnswer"));
        }
        List<DocumentDtos.AiReference> references = attachReferences(payload, question, body);
        String profileId = body == null ? null : body.path("profileId").asText(null);
        payload.set("profile", llmProfileService.buildCallProfile(profileId));
        payload.put("locale", "zh-CN");
        return ApiResponse.ok(withResolvedCitations(aiClient.explain(payload), references));
    }

    /** 按需注入本地检索依据（不可信输入，交 Python 包裹）；返回 Java 侧保留的完整引用信息。 */
    private List<DocumentDtos.AiReference> attachReferences(ObjectNode payload, QuestionDtos.QuestionView question,
                                                            JsonNode body) {
        boolean useKnowledgeBase = body != null && body.path("useKnowledgeBase").asBoolean(false);
        if (!useKnowledgeBase) {
            return List.of();
        }
        int limit = body.path("knowledgeLimit").isNumber()
                ? Math.max(1, Math.min(MAX_KNOWLEDGE_LIMIT, body.path("knowledgeLimit").asInt()))
                : DEFAULT_KNOWLEDGE_LIMIT;
        String query = body.path("query").asText("");
        if (query.isBlank()) {
            query = buildRetrievalQuery(question);
        }
        List<DocumentDtos.AiReference> references = documentService.referencesFor(query, limit);
        if (references.isEmpty()) {
            return references;
        }
        ArrayNode array = payload.putArray("references");
        for (DocumentDtos.AiReference reference : references) {
            ObjectNode node = array.addObject();
            node.put("refId", reference.refId());
            node.put("title", reference.title());
            if (reference.pageNo() == null) {
                node.putNull("pageNo");
            } else {
                node.put("pageNo", reference.pageNo());
            }
            node.put("content", reference.content());
        }
        return references;
    }

    /** 题干 + 选项拼成检索查询；选项内容有助召回知识点，但长度受限避免查询过长。 */
    private String buildRetrievalQuery(QuestionDtos.QuestionView question) {
        StringBuilder builder = new StringBuilder(question.currentVersion().stem() == null
                ? "" : question.currentVersion().stem());
        JsonNode options = json.mapper().valueToTree(question.currentVersion().options());
        if (options.isArray()) {
            List<String> contents = new ArrayList<>();
            for (JsonNode option : options) {
                String content = option.path("content").asText("");
                if (!content.isBlank()) {
                    contents.add(content);
                }
            }
            for (String content : contents) {
                if (builder.length() + content.length() + 1 > MAX_QUERY_CHARS) {
                    break;
                }
                builder.append(' ').append(content);
            }
        }
        String query = builder.toString().trim();
        return query.length() > MAX_QUERY_CHARS ? query.substring(0, MAX_QUERY_CHARS) : query;
    }

    /**
     * 把 Python 返回的 `citations: [{refId, used}]` 解析为带来源坐标的引用列表。
     *
     * <p>只接受本次实际提供过的 refId 且 `used=true` 的条目，重复与臆造的引用一律丢弃。
     */
    private JsonNode withResolvedCitations(JsonNode aiData, List<DocumentDtos.AiReference> references) {
        JsonNode modelCitations = aiData == null ? null : aiData.path("citations");
        ObjectNode result;
        if (aiData instanceof ObjectNode objectNode) {
            result = objectNode.deepCopy();
        } else {
            result = json.object();
        }
        Map<String, DocumentDtos.AiReference> byRefId = new LinkedHashMap<>();
        for (DocumentDtos.AiReference reference : references) {
            byRefId.put(reference.refId(), reference);
        }
        ArrayNode citations = result.putArray("citations");
        Set<String> seen = new LinkedHashSet<>();
        if (modelCitations != null && modelCitations.isArray()) {
            for (JsonNode item : modelCitations) {
                String refId = item.path("refId").asText("");
                DocumentDtos.AiReference reference = byRefId.get(refId);
                if (reference == null || !item.path("used").asBoolean(true) || !seen.add(refId)) {
                    continue;
                }
                ObjectNode node = citations.addObject();
                node.put("refId", refId);
                node.put("documentId", reference.documentId());
                node.put("chunkId", reference.chunkId());
                node.put("title", reference.title());
                if (reference.pageNo() == null) {
                    node.putNull("pageNo");
                } else {
                    node.put("pageNo", reference.pageNo());
                }
                node.put("snippet", reference.content().length() > CITATION_SNIPPET_CHARS
                        ? reference.content().substring(0, CITATION_SNIPPET_CHARS)
                        : reference.content());
            }
        }
        return result;
    }
}
