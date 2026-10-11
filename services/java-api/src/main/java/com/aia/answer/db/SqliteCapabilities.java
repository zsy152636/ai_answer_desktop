package com.aia.answer.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * SQLite 能力探测（开发文档 §10.1）。
 *
 * <p>FTS5 是否存在只能运行期判定：随应用打包的 SQLite 构建不一定带 FTS5。探测结果有两个用途：
 * <ul>
 *   <li>「能力化迁移」：声明了 {@code -- @requires fts5-trigram} 的迁移脚本只在能力可用时执行
 *       （见 {@link MigrationRunner}）；</li>
 *   <li>检索降级：能力不可用时切换到关键词实现，接口形状不变。</li>
 * </ul>
 *
 * <p>检索索引用的是 trigram 分词器（中文按三元组子串匹配，无需外部分词器），因此除了 FTS5 本身，
 * 还单独探测 {@code tokenize='trigram'} 是否被支持：能力名 {@code fts5-trigram} 才是「检索可用」的判据。
 * 探测一律「建临时表就删」，不触碰业务数据；任何异常都按「不可用」处理，绝不因此阻断启动。
 */
public final class SqliteCapabilities {

    /** FTS5 模块可用。 */
    public static final String FTS5 = "fts5";
    /** FTS5 + trigram 分词器可用；这是检索索引真正依赖的能力。 */
    public static final String FTS5_TRIGRAM = "fts5-trigram";

    private SqliteCapabilities() {
    }

    /** 探测当前数据库连接可用的能力集合。 */
    public static Set<String> probe(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return probe(connection);
        } catch (SQLException e) {
            return Set.of();
        }
    }

    public static Set<String> probe(Connection connection) {
        Set<String> found = new LinkedHashSet<>();
        if (fts5(connection)) {
            found.add(FTS5);
        }
        if (fts5Trigram(connection)) {
            found.add(FTS5_TRIGRAM);
        }
        return Set.copyOf(found);
    }

    /** 检索索引是否可用（FTS5 + trigram）。 */
    public static boolean fts5Trigram(DataSource dataSource) {
        return probe(dataSource).contains(FTS5_TRIGRAM);
    }

    private static boolean fts5(Connection connection) {
        return probeVirtualTable(connection, "USING fts5(content)");
    }

    private static boolean fts5Trigram(Connection connection) {
        return probeVirtualTable(connection, "USING fts5(content, tokenize='trigram')");
    }

    private static boolean probeVirtualTable(Connection connection, String definition) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE VIRTUAL TABLE IF NOT EXISTS temp.aia_capability_probe " + definition);
            statement.execute("DROP TABLE IF EXISTS temp.aia_capability_probe");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
