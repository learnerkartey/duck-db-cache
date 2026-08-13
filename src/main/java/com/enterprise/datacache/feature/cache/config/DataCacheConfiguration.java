package com.enterprise.datacache.feature.cache.config;

import com.enterprise.datacache.feature.cache.api.AdminController;
import com.enterprise.datacache.feature.cache.api.GlobalExceptionHandler;
import com.enterprise.datacache.feature.cache.api.QueryController;
import com.enterprise.datacache.feature.cache.dremio.DremioFlightSqlSource;
import com.enterprise.datacache.feature.cache.health.DataCacheHealthIndicator;
import com.enterprise.datacache.feature.cache.health.DataCacheReadinessIndicator;
import com.enterprise.datacache.feature.cache.health.DataCacheStatusService;
import com.enterprise.datacache.feature.cache.health.DataCacheStatusServiceImpl;
import com.enterprise.datacache.feature.cache.health.DremioSourceHealthIndicator;
import com.enterprise.datacache.feature.cache.metadata.MetadataStore;
import com.enterprise.datacache.feature.cache.metrics.DataCacheMetrics;
import com.enterprise.datacache.feature.cache.query.DataCacheQueryService;
import com.enterprise.datacache.feature.cache.query.DataCacheQueryServiceImpl;
import com.enterprise.datacache.feature.cache.query.DuckDbQueryEngine;
import com.enterprise.datacache.feature.cache.query.QueryRegistry;
import com.enterprise.datacache.feature.cache.refresh.DataCacheRefreshService;
import com.enterprise.datacache.feature.cache.refresh.DataCacheRefreshServiceImpl;
import com.enterprise.datacache.feature.cache.refresh.DataCacheStartupCoordinator;
import com.enterprise.datacache.feature.cache.refresh.DynamicRefreshScheduler;
import com.enterprise.datacache.feature.cache.refresh.RefreshCoordinator;
import com.enterprise.datacache.feature.cache.refresh.RefreshLock;
import com.enterprise.datacache.feature.cache.refresh.RetryExecutor;
import com.enterprise.datacache.feature.cache.refresh.RetrySleeper;
import com.enterprise.datacache.feature.cache.refresh.StartupRecoveryService;
import com.enterprise.datacache.feature.cache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.feature.cache.spi.DremioSource;
import com.enterprise.datacache.feature.cache.util.SqlResourceLoader;
import com.enterprise.datacache.feature.cache.validation.DatasetValidationService;
import com.enterprise.datacache.feature.cache.version.VersionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Wires the entire data cache - Dremio source, Arrow/DuckDB pipeline, metadata, versioning,
 * refresh orchestration, dynamic scheduling, the DuckDB query engine, and (unless
 * {@code data-cache.api.enabled=false}) the REST controllers in {@code feature.cache.api} - as
 * plain Spring beans. Imported by {@link com.enterprise.datacache.feature.cache.EnableDataCache};
 * never relies on component-scanning {@code feature.cache}, so this configuration works
 * identically whether that package is copied under a host application's own base package or left
 * as {@code com.enterprise.datacache.feature.cache}.
 *
 * <p>Set {@code data-cache.enabled=false} to disable every bean in this class.
 */
@Configuration
@EnableConfigurationProperties(DataCacheProperties.class)
@ConditionalOnProperty(prefix = "data-cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataCacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DataCacheConfiguration.class);

    @Bean
    public InitializingBean dataCacheConfigurationValidator(DataCacheProperties properties) {
        return () -> DataCacheConfigurationValidator.validate(properties);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "dataCacheRootAllocator")
    public BufferAllocator dataCacheRootAllocator(DataCacheProperties properties) {
        return new RootAllocator(properties.getArrow().getMaxMemory().toBytes());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DremioSource.class)
    public DremioSource dremioSource(DataCacheProperties properties, BufferAllocator dataCacheRootAllocator) {
        return new DremioFlightSqlSource(properties.getDremio(), dataCacheRootAllocator);
    }

    @Bean
    @ConditionalOnMissingBean
    public SqlResourceLoader sqlResourceLoader() {
        return new SqlResourceLoader();
    }

    @Bean(destroyMethod = "close")
    public MetadataStore metadataStore(DataCacheProperties properties) {
        return new MetadataStore(DuckDbConnectionFactory.open(
                MetadataStore.defaultMetadataFile(properties.getDuckdb().getBaseDirectory()), properties.getDuckdb()));
    }

    @Bean
    public VersionManager versionManager(MetadataStore metadataStore, DataCacheProperties properties) {
        return new VersionManager(metadataStore, properties.getDuckdb());
    }

    @Bean
    public DatasetValidationService datasetValidationService(SqlResourceLoader sqlResourceLoader) {
        return new DatasetValidationService(sqlResourceLoader);
    }

    @Bean
    public RefreshLock refreshLock() {
        return new RefreshLock();
    }

    @Bean
    @ConditionalOnMissingBean(RetrySleeper.class)
    public RetrySleeper retrySleeper() {
        return RetrySleeper.REAL;
    }

    @Bean
    public RetryExecutor retryExecutor(RetrySleeper retrySleeper) {
        return new RetryExecutor(retrySleeper);
    }

    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry dataCacheMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public DataCacheMetrics dataCacheMetrics(MeterRegistry meterRegistry) {
        return new DataCacheMetrics(meterRegistry);
    }

    @Bean
    public RefreshCoordinator refreshCoordinator(DataCacheProperties properties, DremioSource dremioSource,
            SqlResourceLoader sqlResourceLoader, MetadataStore metadataStore, VersionManager versionManager,
            DatasetValidationService datasetValidationService, RefreshLock refreshLock, RetryExecutor retryExecutor,
            DataCacheMetrics metrics) {
        return new RefreshCoordinator(properties, dremioSource, sqlResourceLoader, metadataStore, versionManager,
                datasetValidationService, refreshLock, retryExecutor, metrics);
    }

    @Bean(destroyMethod = "shutdown")
    public DataCacheRefreshService dataCacheRefreshService(RefreshCoordinator refreshCoordinator, DataCacheProperties properties) {
        return new DataCacheRefreshServiceImpl(refreshCoordinator, properties);
    }

    @Bean
    public QueryRegistry queryRegistry(DataCacheProperties properties, SqlResourceLoader sqlResourceLoader) {
        return new QueryRegistry(properties, sqlResourceLoader);
    }

    @Bean
    public DuckDbQueryEngine duckDbQueryEngine(QueryRegistry queryRegistry, VersionManager versionManager,
            RefreshLock refreshLock, DataCacheProperties properties) {
        return new DuckDbQueryEngine(queryRegistry, versionManager, refreshLock, properties.getDuckdb(),
                properties.getPagination());
    }

    @Bean
    public DataCacheQueryService dataCacheQueryService(DuckDbQueryEngine duckDbQueryEngine, DataCacheMetrics metrics) {
        return new DataCacheQueryServiceImpl(duckDbQueryEngine, metrics);
    }

    @Bean
    public DataCacheStatusService dataCacheStatusService(DataCacheProperties properties, MetadataStore metadataStore,
            RefreshLock refreshLock) {
        return new DataCacheStatusServiceImpl(properties, metadataStore, refreshLock);
    }

    @Bean
    public StartupRecoveryService startupRecoveryService(DataCacheProperties properties, MetadataStore metadataStore,
            VersionManager versionManager) {
        return new StartupRecoveryService(properties, metadataStore, versionManager);
    }

    @Bean
    @ConditionalOnMissingBean(name = "dataCacheTaskScheduler")
    public TaskScheduler dataCacheTaskScheduler(DataCacheProperties properties) {
        int cronDatasetCount = (int) properties.getDatasets().values().stream()
                .filter(d -> d.isEnabled() && d.getRefreshCron() != null && !d.getRefreshCron().isBlank())
                .count();
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(2, cronDatasetCount));
        scheduler.setThreadNamePrefix("data-cache-scheduler-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean(destroyMethod = "stop")
    public DynamicRefreshScheduler dynamicRefreshScheduler(TaskScheduler dataCacheTaskScheduler,
            DataCacheProperties properties, DataCacheRefreshService dataCacheRefreshService) {
        return new DynamicRefreshScheduler(dataCacheTaskScheduler, properties,
                datasetName -> dataCacheRefreshService.refreshAsync(datasetName));
    }

    @Bean
    public DataCacheStartupCoordinator dataCacheStartupCoordinator(DataCacheProperties properties,
            VersionManager versionManager, DataCacheRefreshService dataCacheRefreshService) {
        return new DataCacheStartupCoordinator(properties, versionManager, dataCacheRefreshService);
    }

    @Bean
    public DataCacheStartupRunner dataCacheStartupRunner(DataCacheProperties properties,
            StartupRecoveryService startupRecoveryService, DataCacheStartupCoordinator dataCacheStartupCoordinator,
            DynamicRefreshScheduler dynamicRefreshScheduler) {
        return new DataCacheStartupRunner(properties, startupRecoveryService, dataCacheStartupCoordinator,
                dynamicRefreshScheduler);
    }

    /**
     * Drives the required startup sequence exactly once, after the application context is fully
     * ready: metadata recovery, then per-dataset startup-mode evaluation/initial-load triggering
     * ({@link DataCacheStartupCoordinator}), then - only after that has decided what needs to
     * happen - cron scheduler registration. This ordering avoids a scheduled refresh and a
     * startup-triggered refresh ever being decided independently for the same dataset; the shared
     * {@link RefreshLock} inside the refresh pipeline protects against them running concurrently
     * even so.
     */
    public static final class DataCacheStartupRunner implements ApplicationListener<ApplicationReadyEvent> {

        private final DataCacheProperties properties;
        private final StartupRecoveryService startupRecoveryService;
        private final DataCacheStartupCoordinator dataCacheStartupCoordinator;
        private final DynamicRefreshScheduler dynamicRefreshScheduler;
        private final AtomicInteger runCount = new AtomicInteger();

        DataCacheStartupRunner(DataCacheProperties properties, StartupRecoveryService startupRecoveryService,
                DataCacheStartupCoordinator dataCacheStartupCoordinator, DynamicRefreshScheduler dynamicRefreshScheduler) {
            this.properties = properties;
            this.startupRecoveryService = startupRecoveryService;
            this.dataCacheStartupCoordinator = dataCacheStartupCoordinator;
            this.dynamicRefreshScheduler = dynamicRefreshScheduler;
        }

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            if (runCount.getAndIncrement() > 0) {
                return; // ApplicationReadyEvent can fire more than once in some test contexts; run exactly once.
            }
            if (properties.getRefresh().isRecoverOnStartup()) {
                log.info("event=startup-recovery-begin");
                startupRecoveryService.recoverAll();
                log.info("event=startup-recovery-complete");
            }
            log.info("event=startup-cache-lifecycle-begin executionMode={}", properties.getStartup().getExecutionMode());
            dataCacheStartupCoordinator.runStartupSequence();
            log.info("event=startup-cache-lifecycle-complete");
            dynamicRefreshScheduler.start();
        }
    }

    @Bean
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(name = "dataCacheHealthIndicator")
    public HealthIndicator dataCacheHealthIndicator(DataCacheStatusService statusService) {
        return new DataCacheHealthIndicator(statusService);
    }

    @Bean
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(name = "dremioSourceHealthIndicator")
    public HealthIndicator dremioSourceHealthIndicator(DremioSource dremioSource) {
        return new DremioSourceHealthIndicator(dremioSource);
    }

    @Bean
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(name = "dataCacheReadinessHealthIndicator")
    public HealthIndicator dataCacheReadinessHealthIndicator(DataCacheProperties properties,
            DataCacheStatusService statusService) {
        return new DataCacheReadinessIndicator(properties, statusService);
    }

    /**
     * Optional REST surface. Registered by default; set {@code data-cache.api.enabled=false} when
     * a host application prefers to call {@link DataCacheQueryService}/{@link DataCacheRefreshService}/
     * {@link DataCacheStatusService} directly from its own controllers instead.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "data-cache.api", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class DataCacheApiConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AdminController dataCacheAdminController(DataCacheRefreshService refreshService,
                DataCacheStatusService statusService) {
            return new AdminController(refreshService, statusService);
        }

        @Bean
        @ConditionalOnMissingBean
        public QueryController dataCacheQueryController(DataCacheQueryService queryService) {
            return new QueryController(queryService);
        }

        @Bean
        @ConditionalOnMissingBean
        public GlobalExceptionHandler dataCacheGlobalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
