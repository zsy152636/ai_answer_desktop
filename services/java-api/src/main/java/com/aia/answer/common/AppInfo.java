package com.aia.answer.common;

/**
 * 服务标识与版本。契约契约 api-contract.md §1 与 /health 响应使用。
 */
public final class AppInfo {

    public static final String VERSION = "1.0.0";
    public static final String JAVA_SERVICE = "java-api";
    public static final String PYTHON_SERVICE = "python-ai";
    public static final String GRADING_RULE_VERSION = "1.0";
    public static final String CONTRACT_VERSION = "1.0";
    public static final String LLM_CONFIG_SCHEMA_VERSION = "1";

    private AppInfo() {
    }
}
