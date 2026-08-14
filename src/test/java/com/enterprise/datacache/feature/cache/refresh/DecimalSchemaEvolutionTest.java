package com.enterprise.datacache.feature.cache.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.datacache.feature.cache.config.DataCacheProperties;
import com.enterprise.datacache.feature.cache.config.DatasetProperties;
import com.enterprise.datacache.feature.cache.config.DuckDbProperties;
import com.enterprise.datacache.feature.cache.config.RetryProperties;
import com.enterprise.datacache.feature.cache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.feature.cache.metadata.MetadataStore;
import com.enterprise.datacache.feature.cache.metrics.DataCacheMetrics;
import com.enterprise.datacache.feature.cache.model.DatasetRefreshResult;
import com.enterprise.datacache.feature.cache.model.DatasetVersion;
import com.enterprise.datacache.feature.cache.model.RefreshOutcome;
import com.enterprise.datacache.feature.cache.model.VersionState;
import com.enterprise.datacache.feature.cache.resume.ResumableRefreshExecutor;
import com.enterprise.datacache.feature.cache.testsupport.DecimalSchemaDremioSource;
import com.enterprise.datacache.feature.cache.util.SqlResourceLoader;
import com.enterprise.datacache.feature.cache.validation.DatasetValidationService;
import com.enterprise.datacache.feature.cache.version.VersionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the decimal schema evolution policy from end to end, through the real refresh/version
 * pipeline (not just the type mapper in isolation): the source Arrow decimal type is always
 * authoritative for a new BUILDING version, regardless of what precision/scale a previous ACTIVE
 * version used, values round-trip exactly via {@link BigDecimal} (never {@code float}/
 * {@code double}), and a source decimal DuckDB cannot represent fails only the new version while
 * leaving the current ACTIVE version completely untouched.
 */
class DecimalSchemaEvolutionTest {

    @TempDir
    Path tempDir;

    private final RetrySleeper noOpSleeper = duration -> { /* no real sleeping in tests */ };

    private DataCacheProperties baseProperties() {
        DataCacheProperties properties = new DataCacheProperties();
        properties.getDuckdb().setBaseDirectory(tempDir.resolve("cache").toString());
        properties.getDuckdb().setTempDirectory(tempDir.resolve("temp").toString());

        DatasetProperties dataset = new DatasetProperties();
        dataset.setTableName("financial");
        dataset.setSourceSql("classpath:testdata/test-dataset.sql");
        RetryProperties retry = new RetryProperties();
        retry.setMaxAttempts(3);
        retry.setInitialDelay(Duration.ofMillis(1));
        retry.setMultiplier(2.0);
        retry.setMaxDelay(Duration.ofMillis(5));
        dataset.setRetry(retry);
        properties.getDatasets().put("financial", dataset);
        return properties;
    }

    private RefreshCoordinator newCoordinator(DataCacheProperties properties, DecimalSchemaDremioSource source,
            MetadataStore metadataStore, VersionManager versionManager) {
        DataCacheMetrics metrics = new DataCacheMetrics(new SimpleMeterRegistry());
        return new RefreshCoordinator(properties, source, new SqlResourceLoader(), metadataStore, versionManager,
                new DatasetValidationService(new SqlResourceLoader()), new RefreshLock(),
                new RetryExecutor(noOpSleeper), metrics, new RefreshProgressRegistry(metrics),
                new ResumableRefreshExecutor(properties, source, new SqlResourceLoader(), metadataStore));
    }

    private MetadataStore newMetadataStore(DuckDbProperties duckDbProps) {
        return new MetadataStore(DuckDbConnectionFactory.open(
                MetadataStore.defaultMetadataFile(duckDbProps.getBaseDirectory()), duckDbProps));
    }

    private String columnType(Path duckDbFile, String table, String column) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + duckDbFile.toAbsolutePath());
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = '" + table + "' AND column_name = '" + column + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private BigDecimal readAmount(Path duckDbFile, String table) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + duckDbFile.toAbsolutePath());
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT amount FROM " + table)) {
            assertThat(rs.next()).isTrue();
            return rs.getBigDecimal("amount");
        }
    }

    @Test
    void wideningSourceDecimalPromotesNewVersionAndLeavesPreviousVersionFileUntouched() throws Exception {
        DataCacheProperties properties = baseProperties();
        BigDecimal narrowValue = new BigDecimal("123456789012345.678"); // DECIMAL(18,3)
        assertThat(narrowValue.precision()).isEqualTo(18);
        assertThat(narrowValue.scale()).isEqualTo(3);
        BigDecimal wideValue = new BigDecimal("12345678901234567890123456789.123456789"); // DECIMAL(38,9)
        assertThat(wideValue.precision()).isEqualTo(38);
        assertThat(wideValue.scale()).isEqualTo(9);

        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb())) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());

            // V1: source schema is DECIMAL(18,3).
            DatasetRefreshResult v1Result;
            try (DecimalSchemaDremioSource narrowSource = new DecimalSchemaDremioSource(18, 3, narrowValue)) {
                RefreshCoordinator coordinator = newCoordinator(properties, narrowSource, metadataStore, versionManager);
                v1Result = coordinator.refresh("financial");
            }
            assertThat(v1Result.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
            assertThat(v1Result.newVersion()).isEqualTo(1L);
            DatasetVersion v1 = versionManager.findActive("financial").orElseThrow();
            assertThat(v1.version()).isEqualTo(1L);
            assertThat(v1.state()).isEqualTo(VersionState.ACTIVE);
            assertThat(columnType(v1.filePath(), "financial", "amount")).isEqualToIgnoringCase("DECIMAL(18,3)");
            assertThat(readAmount(v1.filePath(), "financial")).isEqualByComparingTo(narrowValue);
            Path v1FilePath = v1.filePath();

            // V2: source schema on Dremio has since widened to DECIMAL(38,9). Nothing here ever
            // reads V1's column types - the new version is derived solely from this refresh's
            // Arrow schema, per the decimal schema evolution policy.
            DatasetRefreshResult v2Result;
            try (DecimalSchemaDremioSource wideSource = new DecimalSchemaDremioSource(38, 9, wideValue)) {
                RefreshCoordinator coordinator = newCoordinator(properties, wideSource, metadataStore, versionManager);
                v2Result = coordinator.refresh("financial");
            }
            assertThat(v2Result.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
            assertThat(v2Result.newVersion()).isEqualTo(2L);

            // V2 becomes ACTIVE, V1 becomes PREVIOUS.
            DatasetVersion active = versionManager.findActive("financial").orElseThrow();
            assertThat(active.version()).isEqualTo(2L);
            assertThat(active.state()).isEqualTo(VersionState.ACTIVE);
            DatasetVersion v1AfterActivation = metadataStore.findVersion("financial", 1).orElseThrow();
            assertThat(v1AfterActivation.state()).isEqualTo(VersionState.PREVIOUS);

            // V2's column is exactly DECIMAL(38,9) - never narrowed toward V1's DECIMAL(18,3) -
            // and the value round-trips exactly (same unscaled BigInteger, same scale: no
            // rounding, no truncation, no float/double conversion).
            assertThat(columnType(active.filePath(), "financial", "amount")).isEqualToIgnoringCase("DECIMAL(38,9)");
            BigDecimal roundTripped = readAmount(active.filePath(), "financial");
            assertThat(roundTripped).isEqualByComparingTo(wideValue);
            assertThat(roundTripped.unscaledValue()).isEqualTo(wideValue.unscaledValue());

            // The ACTIVE database is never altered in place: V1's physical file is untouched -
            // same path, still DECIMAL(18,3), still the original exact value.
            assertThat(v1AfterActivation.filePath()).isEqualTo(v1FilePath);
            assertThat(columnType(v1FilePath, "financial", "amount")).isEqualToIgnoringCase("DECIMAL(18,3)");
            assertThat(readAmount(v1FilePath, "financial")).isEqualByComparingTo(narrowValue);
        }
    }

    @Test
    void sourceDecimalDuckDbCannotRepresentFailsOnlyTheNewVersionAndLeavesActiveUntouched() throws Exception {
        DataCacheProperties properties = baseProperties();
        BigDecimal narrowValue = new BigDecimal("123456789012345.678"); // DECIMAL(18,3)

        try (MetadataStore metadataStore = newMetadataStore(properties.getDuckdb())) {
            VersionManager versionManager = new VersionManager(metadataStore, properties.getDuckdb());

            try (DecimalSchemaDremioSource narrowSource = new DecimalSchemaDremioSource(18, 3, narrowValue)) {
                RefreshCoordinator coordinator = newCoordinator(properties, narrowSource, metadataStore, versionManager);
                DatasetRefreshResult v1Result = coordinator.refresh("financial");
                assertThat(v1Result.outcome()).isEqualTo(RefreshOutcome.SUCCESS);
            }
            DatasetVersion v1 = versionManager.findActive("financial").orElseThrow();
            Path v1FilePath = v1.filePath();

            // Source now reports DECIMAL(40,10) - precision above DuckDB's maximum of 38.
            DatasetRefreshResult v2Result;
            try (DecimalSchemaDremioSource oversizedSource =
                    new DecimalSchemaDremioSource(40, 10, new BigDecimal("1.0"))) {
                RefreshCoordinator coordinator = newCoordinator(properties, oversizedSource, metadataStore, versionManager);
                v2Result = coordinator.refresh("financial");
            }

            assertThat(v2Result.outcome()).isEqualTo(RefreshOutcome.FAILED);
            assertThat(v2Result.errorCode()).isEqualTo("DECIMAL_TYPE_NOT_SUPPORTED");
            assertThat(v2Result.attemptsMade()).isEqualTo(1); // not retryable - one attempt only
            // ACTIVE is still V1: the failed V2 attempt was never activated.
            assertThat(v2Result.activeVersion()).isEqualTo(1L);
            DatasetVersion activeAfterFailure = versionManager.findActive("financial").orElseThrow();
            assertThat(activeAfterFailure.version()).isEqualTo(1L);
            assertThat(activeAfterFailure.state()).isEqualTo(VersionState.ACTIVE);

            DatasetVersion failedVersion = metadataStore.findVersion("financial", 2).orElseThrow();
            assertThat(failedVersion.state()).isEqualTo(VersionState.FAILED);
            assertThat(failedVersion.errorCode()).isEqualTo("DECIMAL_TYPE_NOT_SUPPORTED");

            // V1's physical file is completely untouched by the failed attempt.
            assertThat(v1FilePath).exists();
            assertThat(columnType(v1FilePath, "financial", "amount")).isEqualToIgnoringCase("DECIMAL(18,3)");
            assertThat(readAmount(v1FilePath, "financial")).isEqualByComparingTo(narrowValue);
        }
    }
}
