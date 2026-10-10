package com.enterprise.openfinance.payeeverification.infrastructure.config;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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

    private static final Pattern PARAMETER_LIKE = Pattern.compile("[=&;%]");

    private JdbcTlsUrlPolicy() {
    }

    public static void requireVerifiedTls(String property, String url, String expectedRootCert) {
        String hint = property + " must be jdbc:postgresql://<host>:5432/<db>?sslmode=verify-full&sslrootcert="
                + expectedRootCert;
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw refused(hint, "not a jdbc:postgresql:// URL");
        }
        int query = url.indexOf('?');
        if (url.indexOf('#') >= 0 || query < 0 || url.indexOf('?', query + 1) >= 0) {
            throw refused(hint, "it needs exactly one '?' and no fragment");
        }
        if (PARAMETER_LIKE.matcher(url.substring(0, query)).find()) {
            throw refused(hint, "no parameter may come before the '?'");
        }
        int modes = 0;
        int roots = 0;
        for (String pair : url.substring(query + 1).split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                throw refused(hint, "a parameter has no value");
            }
            String name = pair.substring(0, equals);
            String value = pair.substring(equals + 1);
            if (name.indexOf('%') >= 0) {
                throw refused(hint, "parameter names must not be percent-encoded");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!isTlsKey(lower)) {
                continue;
            }
            if (!name.equals(lower)) {
                throw refused(hint, "TLS keys are lower case only (" + name + ")");
            }
            switch (name) {
                case "sslmode" -> {
                    modes++;
                    if (!"verify-full".equals(value)) {
                        throw refused(hint, "sslmode=" + value + " is not verify-full");
                    }
                }
                case "sslrootcert" -> {
                    roots++;
                    if (!expectedRootCert.equals(value)) {
                        throw refused(hint, "sslrootcert is not the mounted RDS CA bundle");
                    }
                }
                default -> throw refused(hint, name
                        + " is refused (it can switch certificate verification off or redirect the connection)");
            }
        }
        if (modes != 1) {
            throw refused(hint, "sslmode must appear exactly once (found " + modes + ")");
        }
        if (roots != 1) {
            throw refused(hint, "sslrootcert must appear exactly once (found " + roots + ")");
        }
    }

    /** Driver properties set outside the URL (Hikari data-source-properties) must not touch TLS. */
    public static void requireNoTlsDriverProperties(String property, Map<String, String> driverProperties) {
        for (String name : driverProperties.keySet()) {
            if (isTlsKey(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(property + "." + name
                        + " is refused: TLS settings come only from the verified JDBC URL");
            }
        }
    }

    private static boolean isTlsKey(String lowerCaseName) {
        return lowerCaseName.startsWith("ssl") || lowerCaseName.equals("service");
    }

    private static IllegalStateException refused(String hint, String reason) {
        return new IllegalStateException(hint + ": " + reason);
    }
}
