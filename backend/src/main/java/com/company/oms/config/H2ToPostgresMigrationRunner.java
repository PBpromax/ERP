package com.company.oms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class H2ToPostgresMigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(H2ToPostgresMigrationRunner.class);

    private final DataSource targetDataSource;
    private final Environment environment;

    public H2ToPostgresMigrationRunner(DataSource targetDataSource, Environment environment) {
        this.targetDataSource = targetDataSource;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!environment.getProperty("oms.migration.h2-to-postgres.enabled", Boolean.class, false)) {
            return;
        }

        String sourceUrl = environment.getRequiredProperty("oms.migration.h2-to-postgres.source-url");
        String sourceUsername = environment.getProperty("oms.migration.h2-to-postgres.source-username", "sa");
        String sourcePassword = environment.getProperty("oms.migration.h2-to-postgres.source-password", "");

        try (Connection source = DriverManager.getConnection(sourceUrl, sourceUsername, sourcePassword);
             Connection target = targetDataSource.getConnection()) {
            assertDatabase(source, "H2");
            assertDatabase(target, "PostgreSQL");
            migrate(source, target);
        }
    }

    private void migrate(Connection source, Connection target) throws SQLException {
        List<String> targetTables = userTables(target, "public");
        Map<String, Long> sourceCounts = new LinkedHashMap<>();
        Map<String, Long> targetCounts = new LinkedHashMap<>();

        for (String table : targetTables) {
            long count = count(target, table);
            if (count != 0) {
                throw new IllegalStateException("PostgreSQL target is not empty: " + table + " has " + count + " rows");
            }
        }

        target.setAutoCommit(false);
        try {
            for (String table : targetTables) {
                if (!tableExists(source, table)) {
                    log.info("Migration skip: source table {} does not exist", table);
                    continue;
                }
                long copied = copyTable(source, target, table);
                sourceCounts.put(table, count(source, table));
                targetCounts.put(table, copied);
                log.info("Migration copied {} rows from {}", copied, table);
            }

            resetIdentitySequences(target, targetTables);
            target.commit();
        } catch (Exception ex) {
            target.rollback();
            throw ex;
        } finally {
            target.setAutoCommit(true);
        }

        for (Map.Entry<String, Long> entry : sourceCounts.entrySet()) {
            long actual = count(target, entry.getKey());
            if (actual != entry.getValue() || !Long.valueOf(actual).equals(targetCounts.get(entry.getKey()))) {
                throw new IllegalStateException("Row count mismatch for " + entry.getKey()
                        + ": source=" + entry.getValue() + ", target=" + actual);
            }
        }
        log.info("H2 to PostgreSQL migration completed: {} tables, {} rows",
                sourceCounts.size(), sourceCounts.values().stream().mapToLong(Long::longValue).sum());
    }

    private long copyTable(Connection source, Connection target, String table) throws SQLException {
        List<String> sourceColumns = columns(source, table);
        List<String> targetColumns = columns(target, table);
        List<String> sharedColumns = targetColumns.stream().filter(sourceColumns::contains).toList();
        if (sharedColumns.isEmpty()) {
            return 0;
        }

        String columnSql = sharedColumns.stream().map(this::quote).reduce((a, b) -> a + ", " + b).orElseThrow();
        String placeholders = String.join(", ", sharedColumns.stream().map(column -> "?").toList());
        String selectSql = "select " + columnSql + " from " + quote(table) + " order by " + quote("id");
        String insertSql = "insert into " + quote(table) + " (" + columnSql + ") values (" + placeholders + ")";

        long copied = 0;
        try (Statement select = source.createStatement();
             ResultSet rows = select.executeQuery(selectSql);
             PreparedStatement insert = target.prepareStatement(insertSql)) {
            ResultSetMetaData metadata = rows.getMetaData();
            while (rows.next()) {
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    setValue(insert, i, rows.getObject(i));
                }
                insert.addBatch();
                copied++;
                if (copied % 500 == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
        }
        return copied;
    }

    private void setValue(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value instanceof Clob clob) {
            statement.setString(index, clob.getSubString(1, Math.toIntExact(clob.length())));
        } else if (value instanceof Blob blob) {
            statement.setBytes(index, blob.getBytes(1, Math.toIntExact(blob.length())));
        } else {
            statement.setObject(index, value);
        }
    }

    private void resetIdentitySequences(Connection target, List<String> tables) throws SQLException {
        for (String table : tables) {
            if (!columns(target, table).contains("id")) {
                continue;
            }
            String sequence;
            try (PreparedStatement query = target.prepareStatement("select pg_get_serial_sequence(?, 'id')")) {
                query.setString(1, "public." + table);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    sequence = result.getString(1);
                }
            }
            if (sequence == null) {
                continue;
            }
            long maxId;
            try (Statement query = target.createStatement();
                 ResultSet result = query.executeQuery("select coalesce(max(" + quote("id") + "), 0) from " + quote(table))) {
                result.next();
                maxId = result.getLong(1);
            }
            try (PreparedStatement update = target.prepareStatement("select setval(?::regclass, ?, ?)")) {
                update.setString(1, sequence);
                update.setLong(2, Math.max(maxId, 1));
                update.setBoolean(3, maxId > 0);
                update.execute();
            }
        }
    }

    private List<String> userTables(Connection connection, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        tables.sort(String::compareTo);
        return tables;
    }

    private List<String> columns(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        DatabaseMetaData metadata = connection.getMetaData();
        String schema = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres") ? "public" : "public";
        try (ResultSet result = metadata.getColumns(null, schema, table, "%")) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        return !columns(connection, table).isEmpty();
    }

    private long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select count(*) from " + quote(table))) {
            result.next();
            return result.getLong(1);
        }
    }

    private void assertDatabase(Connection connection, String expectedProduct) throws SQLException {
        String actual = connection.getMetaData().getDatabaseProductName();
        if (!actual.toLowerCase(Locale.ROOT).contains(expectedProduct.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Expected " + expectedProduct + " but connected to " + actual);
        }
    }

    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
