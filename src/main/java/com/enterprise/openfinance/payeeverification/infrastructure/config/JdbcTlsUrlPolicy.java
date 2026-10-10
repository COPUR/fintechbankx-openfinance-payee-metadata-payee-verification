package com.enterprise.openfinance.payeeverification.infrastructure.config;

import java.util.Map;

/**
 * Startup counterpart of the chart's strict JDBC URL parse
 * (deploy/helm/.../templates/_helpers.tpl, payee.strictJdbcUrl): the PostgreSQL
 * driver may only connect with sslmode=verify-full against the mounted RDS CA bundle.
 * <ul>
 *   <li>jdbc:postgresql:// with exactly one '?' and no fragment; nothing
 *       parameter-like before the '?';</li>
 *   <li>exactly one sslmode, equal to verify-full, and exactly one sslrootcert, equal to
 *       the expected bundle path;</li>
 *   <li>no other ssl* key (sslfactory, sslfactoryarg, sslhostnameverifier,
 *       sslpasswordcallback, sslcert, ...) and no service;</li>
 *   <li>TLS keys in lower case only, and no percent-encoded parameter name.</li>
 * </ul>
 * Messages name the property and the rule, never the URL (it may hold credentials).
 */
public final class JdbcTlsUrlPolicy {

    private JdbcTlsUrlPolicy() {
    }

    public static void requireVerifiedTls(String property, String url, String expectedRootCert) {
        // red skeleton: no check yet
    }

    public static void requireNoTlsDriverProperties(String property, Map<String, String> driverProperties) {
        // red skeleton: no check yet
    }
}
