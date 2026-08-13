package com.enterprise.datacache.validation;

import com.enterprise.datacache.config.ValidationProperties;
import com.enterprise.datacache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.exception.DuckDbWriteException;
import com.enterprise.datacache.util.SqlResourceLoader;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the three configured validation rules - minimum row count, required columns, and an
 * optional custom read-only SQL check - against a just-built (BUILDING) dataset version before it
 * is allowed to become ACTIVE. All configured checks must pass.
 */
public class DatasetValidationService {

    private static final Logger log = LoggerFactory.getLogger(DatasetValidationService.class);

    private final SqlResourceLoader sqlResourceLoader;

    public DatasetValidationService(SqlResourceLoader sqlResourceLoader) {
        this.sqlResourceLoader = sqlResourceLoader;
    }

    public ValidationOutcome validate(String datasetName, Path filePath, String tableName, long rowCount,
            List<String> columnNames, ValidationProperties properties) {
        List<String> failures = new ArrayList<>();

        if (properties.getMinimumRowCount() > 0 && rowCount < properties.getMinimumRowCount()) {
            failures.add("row count " + rowCount + " is below configured minimum " + properties.getMinimumRowCount());
        }

        if (properties.getRequiredColumns() != null && !properties.getRequiredColumns().isEmpty()) {
            Set<String> actual = columnNames.stream().map(c -> c.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            for (String required : properties.getRequiredColumns()) {
                if (!actual.contains(required.toLowerCase(Locale.ROOT))) {
                    failures.add("required column '" + required + "' is missing from the loaded schema");
                }
            }
        }

        if (properties.getSql() != null && !properties.getSql().isBlank()) {
            String sql = sqlResourceLoader.load(properties.getSql());
            failures.addAll(runCustomSql(datasetName, filePath, sql));
        }

        if (!failures.isEmpty()) {
            log.warn("event=dataset-validation-failed dataset={} table={} reasons={}", datasetName, tableName, failures);
            return ValidationOutcome.fail(failures);
        }
        return ValidationOutcome.pass();
    }

    private List<String> runCustomSql(String datasetName, Path filePath, String sql) {
        try (DuckDBConnection connection = DuckDbConnectionFactory.openReadOnly(filePath);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return List.of("custom validation SQL returned zero rows (expected exactly one truthy result)");
            }
            Object value = rs.getObject(1);
            boolean truthy = isTruthy(value);
            if (!truthy) {
                return List.of("custom validation SQL returned a falsy result: " + value);
            }
            return List.of();
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to execute custom validation SQL for dataset " + datasetName, e);
        }
    }

    private boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return true;
    }
}
