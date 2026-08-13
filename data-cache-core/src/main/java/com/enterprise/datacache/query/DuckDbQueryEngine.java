package com.enterprise.datacache.query;

import com.enterprise.datacache.config.DuckDbProperties;
import com.enterprise.datacache.config.PaginationProperties;
import com.enterprise.datacache.exception.DatasetNotAvailableException;
import com.enterprise.datacache.exception.DatasetNotFoundException;
import com.enterprise.datacache.exception.DuckDbWriteException;
import com.enterprise.datacache.exception.MissingQueryParameterException;
import com.enterprise.datacache.model.DatasetAvailability;
import com.enterprise.datacache.model.PagedQueryResult;
import com.enterprise.datacache.model.QueryColumn;
import com.enterprise.datacache.refresh.RefreshLock;
import com.enterprise.datacache.version.VersionHandle;
import com.enterprise.datacache.version.VersionManager;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.duckdb.DuckDBConnection;

/**
 * Executes registered analytical queries entirely inside DuckDB, joining across one or more
 * dataset files transparently via {@code ATTACH} and stable logical view names, with each
 * dataset's ACTIVE version pinned for the lifetime of the query (see {@link VersionManager}).
 *
 * <p>Every execution opens a short-lived, isolated DuckDB connection scoped to that one query so
 * that concurrent queries pinning different versions of the same dataset never collide on
 * attachment aliases. Pagination (LIMIT/OFFSET) and the total row count (via a single
 * {@code COUNT(*) OVER()} window function, not a second full scan) are both pushed down into
 * DuckDB - result rows are only ever materialized into Java for the single requested page.
 */
public class DuckDbQueryEngine {

    private final QueryRegistry queryRegistry;
    private final VersionManager versionManager;
    private final RefreshLock refreshLock;
    private final DuckDbProperties duckDbProperties;
    private final PaginationProperties paginationProperties;

    public DuckDbQueryEngine(QueryRegistry queryRegistry, VersionManager versionManager, RefreshLock refreshLock,
            DuckDbProperties duckDbProperties, PaginationProperties paginationProperties) {
        this.queryRegistry = queryRegistry;
        this.versionManager = versionManager;
        this.refreshLock = refreshLock;
        this.duckDbProperties = duckDbProperties;
        this.paginationProperties = paginationProperties;
    }

    public PagedQueryResult execute(String queryName, Map<String, Object> parameters, int page, int size) {
        long start = System.nanoTime();
        QueryDefinition definition = queryRegistry.get(queryName);
        int effectiveSize = resolvePageSize(size, definition.maxPageSize());
        int effectivePage = Math.max(page, 0);

        List<VersionHandle> handles = new ArrayList<>(definition.datasets().size());
        try {
            for (String datasetName : definition.datasets()) {
                handles.add(pinActiveOrThrowNotAvailable(datasetName));
            }

            try (DuckDBConnection connection = openIsolatedConnection()) {
                attachAndCreateViews(connection, handles);
                PagedQueryResult.Builder resultBuilder = new PagedQueryResult.Builder(queryName, effectivePage, effectiveSize);
                runPagedQuery(connection, definition, parameters, effectivePage, effectiveSize, resultBuilder);
                Map<String, Long> versionsUsed = new LinkedHashMap<>();
                for (VersionHandle handle : handles) {
                    versionsUsed.put(handle.datasetName(), handle.version());
                }
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                return resultBuilder.build(versionsUsed, elapsedMs);
            } catch (SQLException e) {
                throw new DuckDbWriteException("Failed to execute query '" + queryName + "': " + e.getMessage(), e);
            }
        } finally {
            for (VersionHandle handle : handles) {
                handle.close();
            }
        }
    }

    private VersionHandle pinActiveOrThrowNotAvailable(String datasetName) {
        try {
            return versionManager.pinActive(datasetName);
        } catch (DatasetNotFoundException noActiveVersion) {
            // A dataset referenced by a registered query is always known/enabled (validated when
            // the query was registered), so pinActive() can only fail this way when it has no
            // ACTIVE version yet - never because the name itself is unrecognized.
            DatasetAvailability availability = refreshLock.isRunning(datasetName)
                    ? DatasetAvailability.STARTUP_LOADING
                    : DatasetAvailability.UNAVAILABLE;
            throw new DatasetNotAvailableException(datasetName, availability);
        }
    }

    private int resolvePageSize(int requested, int maxPageSize) {
        int size = requested <= 0 ? paginationProperties.getDefaultPageSize() : requested;
        return Math.min(size, maxPageSize);
    }

    private DuckDBConnection openIsolatedConnection() {
        try {
            DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA memory_limit='" + duckDbProperties.getMemoryLimit().toMegabytes() + "MB'");
                statement.execute("PRAGMA threads=" + duckDbProperties.getThreads());
                if (duckDbProperties.getTempDirectory() != null) {
                    statement.execute("SET temp_directory='" + duckDbProperties.getTempDirectory().replace("'", "''") + "'");
                }
            }
            return connection;
        } catch (SQLException e) {
            throw new DuckDbWriteException("Failed to open query execution connection", e);
        }
    }

    private void attachAndCreateViews(DuckDBConnection connection, List<VersionHandle> handles) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (VersionHandle handle : handles) {
                String alias = "ds_" + UUID.randomUUID().toString().replace("-", "");
                String path = handle.filePath().toAbsolutePath().toString().replace("'", "''");
                statement.execute("ATTACH '" + path + "' AS " + alias + " (READ_ONLY)");
                String logicalView = quoteIdentifier(handle.datasetName());
                String physicalTable = alias + "." + quoteIdentifier(handle.tableName());
                statement.execute("CREATE VIEW " + logicalView + " AS SELECT * FROM " + physicalTable);
            }
        }
    }

    private void runPagedQuery(DuckDBConnection connection, QueryDefinition definition, Map<String, Object> parameters,
            int page, int size, PagedQueryResult.Builder resultBuilder) throws SQLException {
        String innerSql = definition.boundSql().jdbcSql();
        String pagedSql = "SELECT *, COUNT(*) OVER() AS __data_cache_total_rows__ FROM (" + innerSql
                + ") AS __data_cache_query__ LIMIT ? OFFSET ?";

        try (PreparedStatement ps = connection.prepareStatement(pagedSql)) {
            int paramIndex = 1;
            for (String paramName : definition.boundSql().parameterOrder()) {
                if (!parameters.containsKey(paramName)) {
                    throw new MissingQueryParameterException(definition.name(), paramName);
                }
                ps.setObject(paramIndex++, parameters.get(paramName));
            }
            ps.setInt(paramIndex++, size);
            ps.setInt(paramIndex, (long) page * size > Integer.MAX_VALUE ? Integer.MAX_VALUE : page * size);

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount() - 1; // exclude the injected total-rows column
                List<QueryColumn> columns = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(new QueryColumn(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
                }
                resultBuilder.columns(columns);

                long totalRows = 0;
                int rowsInPage = 0;
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    resultBuilder.addRow(row);
                    totalRows = rs.getLong(columnCount + 1);
                    rowsInPage++;
                }
                if (rowsInPage == 0) {
                    totalRows = countTotalRows(connection, innerSql, definition, parameters);
                }
                resultBuilder.totalRows(totalRows);
                resultBuilder.hasNext(((long) page * size) + rowsInPage < totalRows);
            }
        }
    }

    private long countTotalRows(DuckDBConnection connection, String innerSql, QueryDefinition definition,
            Map<String, Object> parameters) throws SQLException {
        String countSql = "SELECT COUNT(*) FROM (" + innerSql + ") AS __data_cache_count__";
        try (PreparedStatement ps = connection.prepareStatement(countSql)) {
            int paramIndex = 1;
            for (String paramName : definition.boundSql().parameterOrder()) {
                ps.setObject(paramIndex++, parameters.get(paramName));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
