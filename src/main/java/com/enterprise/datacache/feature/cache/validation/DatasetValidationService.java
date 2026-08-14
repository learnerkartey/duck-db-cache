package com.enterprise.datacache.feature.cache.validation;

import com.enterprise.datacache.feature.cache.config.ValidationProperties;
import com.enterprise.datacache.feature.cache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.feature.cache.exception.DuckDbWriteException;
import com.enterprise.datacache.feature.cache.util.SqlResourceLoader;
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
        List<ValidationOutcome.CheckResult> checks = new ArrayList<>();

        if (properties.getMinimumRowCount() > 0) {
            if (rowCount < properties.getMinimumRowCount()) {
                String reason = "row count " + rowCount + " is below configured minimum " + properties.getMinimumRowCount();
                failures.add(reason);
                checks.add(new ValidationOutcome.CheckResult("minimum-row-count", false, reason));
            } else {
                checks.add(new ValidationOutcome.CheckResult("minimum-row-count", true, "rowCount=" + rowCount));
            }
        }

        if (properties.getRequiredColumns() != null && !properties.getRequiredColumns().isEmpty()) {
            Set<String> actual = columnNames.stream().map(c -> c.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            List<String> missing = new ArrayList<>();
            for (String required : properties.getRequiredColumns()) {
                if (!actual.contains(required.toLowerCase(Locale.ROOT))) {
                    String reason = "required column '" + required + "' is missing from the loaded schema";
                    failures.add(reason);
                    missing.add(required);
                }
            }
            checks.add(new ValidationOutcome.CheckResult("required-columns", missing.isEmpty(),
                    missing.isEmpty() ? "all present" : "missing=" + missing));
        }

        if (properties.getSql() != null && !properties.getSql().isBlank()) {
            String sql = sqlResourceLoader.load(properties.getSql());
            List<String> customSqlFailures = runCustomSql(datasetName, filePath, sql);
            failures.addAll(customSqlFailures);
            checks.add(new ValidationOutcome.CheckResult("custom-sql", customSqlFailures.isEmpty(),
                    customSqlFailures.isEmpty() ? "passed" : customSqlFailures.get(0)));
        }

        if (!failures.isEmpty()) {
            log.warn("event=dataset-validation-failed dataset={} table={} reasons={}", datasetName, tableName, failures);
            return ValidationOutcome.fail(failures, checks);
        }
        return ValidationOutcome.pass(checks);
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
