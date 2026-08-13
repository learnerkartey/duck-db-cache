package com.enterprise.datacache.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.datacache.config.DataCacheProperties;
import com.enterprise.datacache.config.DatasetProperties;
import com.enterprise.datacache.config.DuckDbProperties;
import com.enterprise.datacache.config.QueryProperties;
import com.enterprise.datacache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.duckdb.DuckDbDatasetWriter;
import com.enterprise.datacache.metadata.MetadataStore;
import com.enterprise.datacache.model.PagedQueryResult;
import com.enterprise.datacache.util.SqlResourceLoader;
import com.enterprise.datacache.version.VersionManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaginationAndParameterSafetyTest {

    @TempDir
    Path tempDir;

    private DuckDbQueryEngine buildEngineWithWidgets(int rowCount) {
        DataCacheProperties properties = new DataCacheProperties();
        DuckDbProperties duckDbProps = properties.getDuckdb();
        duckDbProps.setBaseDirectory(tempDir.resolve("cache").toString());
        duckDbProps.setTempDirectory(tempDir.resolve("temp").toString());
        properties.getDatasets().put("widgets", new DatasetProperties());
        properties.getPagination().setDefaultPageSize(10);
        properties.getPagination().setMaxPageSize(50);

        QueryProperties query = new QueryProperties();
        query.setSql("classpath:testdata/single-dataset-query.sql");
        query.setDatasets(List.of("widgets"));
        properties.getQueries().put("list-widgets", query);

        Schema schema = new Schema(List.of(
                new Field("id", FieldType.notNullable(new ArrowType.Int(32, true)), null),
                new Field("name", FieldType.notNullable(new ArrowType.Utf8()), null)));

        Path file = tempDir.resolve("cache/widgets/widgets_v1.duckdb");
        try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                DuckDbDatasetWriter writer = new DuckDbDatasetWriter(file, "widgets", duckDbProps)) {
            writer.begin(schema);
            try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
                root.allocateNew();
                for (int i = 0; i < rowCount; i++) {
                    ((IntVector) root.getVector("id")).setSafe(i, i);
                    ((VarCharVector) root.getVector("name")).setSafe(i, ("widget-" + i).getBytes());
                }
                root.setRowCount(rowCount);
                writer.writeBatch(root);
            }
            writer.finish();
        }

        MetadataStore metadataStore = new MetadataStore(
                DuckDbConnectionFactory.open(MetadataStore.defaultMetadataFile(duckDbProps.getBaseDirectory()), duckDbProps));
        VersionManager versionManager = new VersionManager(metadataStore, duckDbProps);
        metadataStore.createBuildingVersion("widgets", 1, file, "widgets", "h1");
        versionManager.activate("widgets", 1);

        QueryRegistry registry = new QueryRegistry(properties, new SqlResourceLoader());
        return new DuckDbQueryEngine(registry, versionManager, duckDbProps, properties.getPagination());
    }

    @Test
    void paginatesAcrossMultiplePagesAndReportsHasNextCorrectly() {
        DuckDbQueryEngine engine = buildEngineWithWidgets(25);
        Map<String, Object> params = Map.of("excluded", "no-such-widget");

        PagedQueryResult page0 = engine.execute("list-widgets", params, 0, 10);
        assertThat(page0.rows()).hasSize(10);
        assertThat(page0.totalRows()).isEqualTo(25);
        assertThat(page0.hasNext()).isTrue();
        assertThat(page0.rows().get(0).get("name")).isEqualTo("widget-0");

        PagedQueryResult page1 = engine.execute("list-widgets", params, 1, 10);
        assertThat(page1.rows()).hasSize(10);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.rows().get(0).get("name")).isEqualTo("widget-10");

        PagedQueryResult page2 = engine.execute("list-widgets", params, 2, 10);
        assertThat(page2.rows()).hasSize(5); // last, partial page
        assertThat(page2.hasNext()).isFalse();

        PagedQueryResult emptyPage = engine.execute("list-widgets", params, 5, 10);
        assertThat(emptyPage.rows()).isEmpty();
        assertThat(emptyPage.totalRows()).isEqualTo(25);
        assertThat(emptyPage.hasNext()).isFalse();
    }

    @Test
    void pageSizeIsCappedAtConfiguredMaximum() {
        DuckDbQueryEngine engine = buildEngineWithWidgets(200);
        PagedQueryResult result = engine.execute("list-widgets", Map.of("excluded", "none"), 0, 10_000);
        assertThat(result.rows()).hasSizeLessThanOrEqualTo(50); // configured max-page-size
    }

    @Test
    void namedParametersContainingSqlSyntaxAreTreatedAsLiteralDataNotSql() {
        DuckDbQueryEngine engine = buildEngineWithWidgets(5);

        // Values crafted to look like injection attempts must be bound as plain string literals.
        String[] maliciousValues = {
                "widget-0'; DROP TABLE widgets; --",
                "widget-0' OR '1'='1",
                "widget-0\" OR \"1\"=\"1",
                "widget-0/*",
        };
        for (String malicious : maliciousValues) {
            PagedQueryResult result = engine.execute("list-widgets", Map.of("excluded", malicious), 0, 50);
            // None of these values match any actual row, so every row is still returned untouched -
            // proving the value was bound as data, not interpreted as SQL.
            assertThat(result.rows()).hasSize(5);
        }

        // A value that legitimately matches and excludes exactly one row.
        PagedQueryResult filtered = engine.execute("list-widgets", Map.of("excluded", "widget-2"), 0, 50);
        assertThat(filtered.rows()).hasSize(4);
        assertThat(filtered.rows()).noneMatch(row -> "widget-2".equals(row.get("name")));
    }
}
