package com.aia.answer.service.search;

import com.aia.answer.domain.DocumentDtos;

import java.util.List;

/**
 * 检索端口（开发文档 §10.1、§546）。
 *
 * <p>业务层只依赖本接口：FTS5 可用时用 {@link Fts5DocumentSearchPort}，不可用时用
 * {@link KeywordDocumentSearchPort}，接口形状与返回字段完全一致，降级对上层透明。
 * 这样「FTS5 不可用时关键词检索降级路径工作，检索层接口不变」（开发文档 §12.2）。
 */
public interface DocumentSearchPort {

    /** 检索模式标识：{@code fts5} 或 {@code keyword}。 */
    String mode();

    /**
     * 执行检索。
     *
     * @param query        用户原始查询（实现内部负责转义，绝不拼接原始 SQL/FTS 语法）
     * @param limit        返回上限
     * @param documentId   可选的资料过滤
     * @param indexVersion 只检索该索引版本的分块（一般为 activeVersion）
     */
    List<DocumentDtos.SearchHit> search(String query, int limit, String documentId, long indexVersion);
}
