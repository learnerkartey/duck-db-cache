package com.example.hostapp;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans representative of what a real, already-existing host application brings with it: its own
 * business service and its own primary {@link DataSource} (here standing in for something like a
 * SQL Server or Oracle connection pool). Embedding the cache feature must never replace or shadow
 * either of these.
 */
@Configuration
public class HostBeanConfiguration {

    @Bean
    public SampleHostService sampleHostService() {
        return new SampleHostService();
    }

    @Bean
    public DataSource hostDataSource() {
        return new HostOnlyDataSource();
    }

    /** Marker {@link DataSource} implementation: identity, not JDBC behavior, is what the test checks. */
    private static final class HostOnlyDataSource implements DataSource {

        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException("test marker DataSource - not a real connection pool");
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("test marker DataSource - not a real connection pool");
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // no-op marker
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // no-op marker
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
