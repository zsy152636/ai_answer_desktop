package com.aia.answer.service.setting;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * setting.json 的不可变内存快照。
 *
 * <p>安全边界：{@code parsed} 可能包含 profile 的明文 {@code apiKey}，因此
 * <b>禁止把本对象、其 parsed 内容或修订号以外的任何字段打进日志</b>；日志只允许出现
 * profile 的 id / provider / model。
 *
 * @param parsed  解析后的 JSON 根节点；配置为空时按约定为 {@code null}
 * @param revision 文件字节的 SHA-256（小写 hex）；配置为空时为 {@code null}
 * @param loadedAt 加载时刻（UTC）
 * @param warnings 加载/校验过程中的可读提示（不含任何密钥内容）
 */
public record SettingSnapshot(JsonNode parsed, String revision, Instant loadedAt, List<String> warnings) {

    public SettingSnapshot {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 空快照：setting.json 不存在或已被删除。 */
    public static SettingSnapshot empty(Instant loadedAt) {
        return new SettingSnapshot(null, null, loadedAt, List.of());
    }

    /** 是否存在可用内容。 */
    public boolean present() {
        return parsed != null && parsed.isObject();
    }
}
