package com.aia.answer;

import com.aia.answer.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AI 智能答题系统 · Java 业务服务入口。
 *
 * <p>本服务是 SQLite 的唯一写入方与正式业务规则权威（开发文档 §2、附录 B）。
 * 数据源由 {@code com.aia.answer.db.DataSourceConfig} 手工装配，因为数据库绝对路径
 * 必须先经过「用户数据目录解析 → 待恢复请求校验 → 迁移前备份」流程后才能确定。
 *
 * <p>日志目录必须与数据库同在用户数据目录，而 logback 在 Spring 容器启动前就已初始化，
 * 因此这里先从命令行参数里解出 {@code --aia.data-dir}，再以系统属性形式交给 logback。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(AppProperties.class)
public class AnswerApplication {

    private static final String DATA_DIR_ARG = "--aia.data-dir=";

    public static void main(String[] args) {
        System.setProperty("aia.log.dir", resolveLogDir(args).toString());
        SpringApplication.run(AnswerApplication.class, args);
    }

    private static Path resolveLogDir(String[] args) {
        Path dataDir = null;
        for (String arg : args) {
            if (arg != null && arg.startsWith(DATA_DIR_ARG)) {
                String value = arg.substring(DATA_DIR_ARG.length());
                if (!value.isBlank()) {
                    dataDir = Paths.get(value);
                }
            }
        }
        if (dataDir == null) {
            String env = System.getenv("AIA_DATA_DIR");
            if (env != null && !env.isBlank()) {
                dataDir = Paths.get(env);
            }
        }
        if (dataDir == null) {
            String localAppData = System.getenv("LOCALAPPDATA");
            dataDir = (localAppData != null && !localAppData.isBlank())
                    ? Paths.get(localAppData, "ai-answer-desktop")
                    : Paths.get(System.getProperty("user.home"), ".ai-answer-desktop");
        }
        return dataDir.toAbsolutePath().normalize().resolve("logs");
    }
}
