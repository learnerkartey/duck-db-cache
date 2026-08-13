package com.enterprise.datacache.config;

import com.enterprise.datacache.dremio.DremioFlightSqlSource;
import com.enterprise.datacache.health.DataCacheHealthIndicator;
import com.enterprise.datacache.health.DataCacheStatusService;
import com.enterprise.datacache.health.DataCacheStatusServiceImpl;
import com.enterprise.datacache.health.DremioSourceHealthIndicator;
import com.enterprise.datacache.metadata.MetadataStore;
import com.enterprise.datacache.metrics.DataCacheMetrics;
import com.enterprise.datacache.query.DataCacheQueryService;
import com.enterprise.datacache.query.DataCacheQueryServiceImpl;
import com.enterprise.datacache.query.DuckDbQueryEngine;
import com.enterprise.datacache.query.QueryRegistry;
import com.enterprise.datacache.refresh.DataCacheRefreshService;
import com.enterprise.datacache.refresh.DataCacheRefreshServiceImpl;
import com.enterprise.datacache.refresh.DynamicRefreshScheduler;
import com.enterprise.datacache.refresh.RefreshCoordinator;
import com.enterprise.datacache.refresh.RefreshLock;
import com.enterprise.datacache.refresh.RetryExecutor;
import com.enterprise.datacache.refresh.RetrySleeper;
import com.enterprise.datacache.refresh.StartupRecoveryService;
import com.enterprise.datacache.duckdb.DuckDbConnectionFactory;
import com.enterprise.datacache.spi.DremioSource;
import com.enterprise.datacache.util.SqlResourceLoader;
import com.enterprise.datacache.validation.DatasetValidationService;
import com.enterprise.datacache.version.VersionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Wires the entire data cache - Dremio source, Arrow/DuckDB pipeline, metadata, versioning,
 * refresh orchestration, dynamic scheduling, and the DuckDB query engine - as plain Spring beans
 * so that {@code data-cache-core} is fully self-sufficient inside any Spring Boot 3 application
 * that supplies {@code data-cache.*} configuration. Registered via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p>Set {@code data-cache.enabled=false} to disable every bean in this class.
 */
@AutoConfiguration
@EnableConfigurationProperties(DataCacheProperties.class)
@ConditionalOnProperty(prefix = "data-cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataCacheAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DataCacheAutoConfiguration.class);

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
            DataCacheProperties properties) {
        return new DuckDbQueryEngine(queryRegistry, versionManager, properties.getDuckdb(), properties.getPagination());
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
    public DataCacheStartupRunner dataCacheStartupRunner(DataCacheProperties properties,
            StartupRecoveryService startupRecoveryService, DynamicRefreshScheduler dynamicRefreshScheduler,
            DataCacheRefreshService dataCacheRefreshService) {
        return new DataCacheStartupRunner(properties, startupRecoveryService, dynamicRefreshScheduler, dataCacheRefreshService);
    }

    /**
     * Runs startup recovery, starts the dynamic scheduler, and fires {@code load-on-startup}
     * datasets - once, after the application context is fully ready, so it never delays readiness
     * probes or races bean initialization.
     */
    public static final class DataCacheStartupRunner implements ApplicationListener<ApplicationReadyEvent> {

        private final DataCacheProperties properties;
        private final StartupRecoveryService startupRecoveryService;
        private final DynamicRefreshScheduler dynamicRefreshScheduler;
        private final DataCacheRefreshService dataCacheRefreshService;
        private final AtomicInteger runCount = new AtomicInteger();

        DataCacheStartupRunner(DataCacheProperties properties, StartupRecoveryService startupRecoveryService,
                DynamicRefreshScheduler dynamicRefreshScheduler, DataCacheRefreshService dataCacheRefreshService) {
            this.properties = properties;
            this.startupRecoveryService = startupRecoveryService;
            this.dynamicRefreshScheduler = dynamicRefreshScheduler;
            this.dataCacheRefreshService = dataCacheRefreshService;
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
            dynamicRefreshScheduler.start();
            properties.getDatasets().forEach((name, config) -> {
                if (config.isEnabled() && config.isLoadOnStartup()) {
                    log.info("event=load-on-startup-triggered dataset={}", name);
                    dataCacheRefreshService.refreshAsync(name);
                }
            });
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
}
