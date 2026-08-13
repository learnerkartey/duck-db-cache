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
import com.enterprise.datacache.refresh.RefreshLock;
import com.enterprise.datacache.util.SqlResourceLoader;
import com.enterprise.datacache.version.VersionManager;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuckDbQueryEngineTest {

    @TempDir
    Path tempDir;

    private DataCacheProperties properties() {
        DataCacheProperties properties = new DataCacheProperties();
        properties.getDuckdb().setBaseDirectory(tempDir.resolve("cache").toString());
        properties.getDuckdb().setTempDirectory(tempDir.resolve("temp").toString());
        properties.getDatasets().put("financial", new DatasetProperties());
        properties.getDatasets().put("organization", new DatasetProperties());

        QueryProperties join = new QueryProperties();
        join.setSql("classpath:testdata/join-query.sql");
        join.setDatasets(List.of("financial", "organization"));
        properties.getQueries().put("cfo-summary", join);
        return properties;
    }

    private void writeFinancial(DuckDbProperties duckDbProps, Path file, int fiscalYear) {
        Schema schema = new Schema(List.of(
                new Field("cost_center", FieldType.notNullable(new ArrowType.Utf8()), null),
                new Field("fiscal_year", FieldType.notNullable(new ArrowType.Int(32, true)), null),
                new Field("amount", FieldType.notNullable(new ArrowType.Decimal(12, 2, 128)), null)));
        try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                DuckDbDatasetWriter writer = new DuckDbDatasetWriter(file, "financial", duckDbProps)) {
            writer.begin(schema);
            try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
                root.allocateNew();
                String[] centers = {"CC1", "CC1", "CC2"};
                int[] years = {fiscalYear, fiscalYear, fiscalYear};
                String[] amounts = {"100.00", "50.00", "200.00"};
                for (int i = 0; i < centers.length; i++) {
                    ((VarCharVector) root.getVector("cost_center")).setSafe(i, centers[i].getBytes());
                    ((IntVector) root.getVector("fiscal_year")).setSafe(i, years[i]);
                    ((DecimalVector) root.getVector("amount")).setSafe(i, new BigDecimal(amounts[i]));
                }
                root.setRowCount(centers.length);
                writer.writeBatch(root);
            }
            writer.finish();
        }
    }

    private void writeOrganization(DuckDbProperties duckDbProps, Path file) {
        Schema schema = new Schema(List.of(
                new Field("cost_center", FieldType.notNullable(new ArrowType.Utf8()), null),
                new Field("department", FieldType.notNullable(new ArrowType.Utf8()), null)));
        try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                DuckDbDatasetWriter writer = new DuckDbDatasetWriter(file, "organization", duckDbProps)) {
            writer.begin(schema);
            try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
                root.allocateNew();
                String[] centers = {"CC1", "CC2"};
                String[] departments = {"Engineering", "Sales"};
                for (int i = 0; i < centers.length; i++) {
                    ((VarCharVector) root.getVector("cost_center")).setSafe(i, centers[i].getBytes());
                    ((VarCharVector) root.getVector("department")).setSafe(i, departments[i].getBytes());
                }
                root.setRowCount(centers.length);
                writer.writeBatch(root);
            }
            writer.finish();
        }
    }

    @Test
    void executesCrossDatasetJoinWithGroupByAndParameterBinding() {
        DataCacheProperties properties = properties();
        DuckDbProperties duckDbProps = properties.getDuckdb();

        try (MetadataStore metadataStore = new MetadataStore(
                DuckDbConnectionFactory.open(MetadataStore.defaultMetadataFile(duckDbProps.getBaseDirectory()), duckDbProps))) {
            VersionManager versionManager = new VersionManager(metadataStore, duckDbProps);

            Path financialFile = tempDir.resolve("cache/financial/financial_v1.duckdb");
            writeFinancial(duckDbProps, financialFile, 2026);
            metadataStore.createBuildingVersion("financial", 1, financialFile, "financial", "h1");
            versionManager.activate("financial", 1);

            Path orgFile = tempDir.resolve("cache/organization/organization_v1.duckdb");
            writeOrganization(duckDbProps, orgFile);
            metadataStore.createBuildingVersion("organization", 1, orgFile, "organization", "h1");
            versionManager.activate("organization", 1);

            QueryRegistry registry = new QueryRegistry(properties, new SqlResourceLoader());
            DuckDbQueryEngine engine = new DuckDbQueryEngine(registry, versionManager,
                    new RefreshLock(), duckDbProps, properties.getPagination());

            PagedQueryResult result = engine.execute("cfo-summary", Map.of("fiscalYear", 2026), 0, 10);

            assertThat(result.rows()).hasSize(2);
            assertThat(result.datasetVersionsUsed()).containsEntry("financial", 1L).containsEntry("organization", 1L);
            Map<String, Object> engineering = result.rows().stream()
                    .filter(r -> "Engineering".equals(r.get("department"))).findFirst().orElseThrow();
            assertThat(((Number) engineering.get("total_amount")).doubleValue()).isEqualTo(150.00);
            Map<String, Object> sales = result.rows().stream()
                    .filter(r -> "Sales".equals(r.get("department"))).findFirst().orElseThrow();
            assertThat(((Number) sales.get("total_amount")).doubleValue()).isEqualTo(200.00);

            // No readers should remain pinned once the query has completed.
            assertThat(versionManager.activeReaderCount("financial", 1)).isEqualTo(0);
            assertThat(versionManager.activeReaderCount("organization", 1)).isEqualTo(0);
        }
    }

    @Test
    void independentDatasetRefreshDoesNotAffectOtherDatasetVersionPinnedByQuery() {
        DataCacheProperties properties = properties();
        DuckDbProperties duckDbProps = properties.getDuckdb();

        try (MetadataStore metadataStore = new MetadataStore(
                DuckDbConnectionFactory.open(MetadataStore.defaultMetadataFile(duckDbProps.getBaseDirectory()), duckDbProps))) {
            VersionManager versionManager = new VersionManager(metadataStore, duckDbProps);

            Path financialFileV1 = tempDir.resolve("cache/financial/financial_v1.duckdb");
            writeFinancial(duckDbProps, financialFileV1, 2026);
            metadataStore.createBuildingVersion("financial", 1, financialFileV1, "financial", "h1");
            versionManager.activate("financial", 1);

            Path orgFile = tempDir.resolve("cache/organization/organization_v1.duckdb");
            writeOrganization(duckDbProps, orgFile);
            metadataStore.createBuildingVersion("organization", 1, orgFile, "organization", "h1");
            versionManager.activate("organization", 1);

            // Refresh only financial to v2 - organization must remain at v1.
            Path financialFileV2 = tempDir.resolve("cache/financial/financial_v2.duckdb");
            writeFinancial(duckDbProps, financialFileV2, 2026);
            metadataStore.createBuildingVersion("financial", 2, financialFileV2, "financial", "h2");
            versionManager.activate("financial", 2);

            QueryRegistry registry = new QueryRegistry(properties, new SqlResourceLoader());
            DuckDbQueryEngine engine = new DuckDbQueryEngine(registry, versionManager,
                    new RefreshLock(), duckDbProps, properties.getPagination());

            PagedQueryResult result = engine.execute("cfo-summary", Map.of("fiscalYear", 2026), 0, 10);
            assertThat(result.datasetVersionsUsed()).containsEntry("financial", 2L).containsEntry("organization", 1L);
        }
    }
}
