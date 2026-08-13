package com.enterprise.datacache.config;

import java.time.Duration;

/** Connection settings for the Dremio Arrow Flight SQL endpoint. Never log {@link #password}. */
public class DremioProperties {

    /** Dremio coordinator hostname. Required when any dataset or the verification task uses this source. */
    private String host;

    /** Arrow Flight SQL port. Dremio's default Flight endpoint port is 32010. */
    private int port = 32010;

    private String username;

    private String password;

    /** Whether to negotiate TLS with the Flight endpoint. */
    private boolean sslEnabled = true;

    /**
     * When {@code sslEnabled} is true and the server certificate is not signed by a public CA,
     * point this at a PEM bundle to trust. Left null to use the JVM default trust store.
     */
    private String trustedCertificatesPath;

    private Duration connectTimeout = Duration.ofSeconds(30);

    private Duration queryTimeout = Duration.ofMinutes(30);

    /** SQL executed by the live-connectivity verification task (see docs/04). Runs no dataset load. */
    private String verificationSql = "SELECT 1";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }

    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }

    public String getTrustedCertificatesPath() {
        return trustedCertificatesPath;
    }

    public void setTrustedCertificatesPath(String trustedCertificatesPath) {
        this.trustedCertificatesPath = trustedCertificatesPath;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getQueryTimeout() {
        return queryTimeout;
    }

    public void setQueryTimeout(Duration queryTimeout) {
        this.queryTimeout = queryTimeout;
    }

    public String getVerificationSql() {
        return verificationSql;
    }

    public void setVerificationSql(String verificationSql) {
        this.verificationSql = verificationSql;
    }

    @Override
    public String toString() {
        // Deliberately omits password.
        return "DremioProperties{host=" + host + ", port=" + port + ", username=" + username
                + ", sslEnabled=" + sslEnabled + ", connectTimeout=" + connectTimeout
                + ", queryTimeout=" + queryTimeout + "}";
    }
}
