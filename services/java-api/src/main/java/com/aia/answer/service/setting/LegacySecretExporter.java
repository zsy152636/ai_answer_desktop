package com.aia.answer.service.setting;

import java.util.Optional;

/**
 * 旧凭据存储的只读导出点（迁移专用，后续切片删除凭据包时只需替换实现）。
 *
 * <p>存在的唯一理由：首次启动迁移要把 {@code llm_profile.api_key_ref} 指向的密钥
 * 「一次性」搬进 setting.json 的 {@code apiKey} 字段。把这个读取点收敛成一个接口，
 * 后续切片删除 {@code service/credential/**} 时，改动面只有本接口的实现类。
 *
 * <p>安全边界：返回的明文只允许写进 setting.json，<b>绝不</b>进入日志、异常消息或审计事件；
 * 实现方同样不得记录引用或密钥。
 */
@FunctionalInterface
public interface LegacySecretExporter {

    /**
     * 按旧凭据引用读取明文密钥。
     *
     * @param reference {@code credential://llm/<profileId>} 形式的引用；读不到时返回空
     */
    Optional<String> exportSecret(String reference);
}
