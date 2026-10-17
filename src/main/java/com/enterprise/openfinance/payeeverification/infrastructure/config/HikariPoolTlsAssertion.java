package com.enterprise.openfinance.payeeverification.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Checks the effective connection pool, not only the properties: after
 * spring.datasource.hikari.* is bound onto the HikariDataSource and before the pool opens
 * (it opens on the first getConnection), its jdbcUrl must pass {@link JdbcTlsUrlPolicy},
 * no data-source property may touch TLS (ssl*, service), and no dataSourceClassName or
 * DataSource may replace the URL (Hikari would ignore jdbcUrl, sslmode included). On under
 * the same switch as {@link AwsTransportSecurityConfiguration}: always, unless
 * fintechbankx.tls.enforce is false (local profile and tests only).
 */
final class HikariPoolTlsAssertion implements BeanPostProcessor {

    private final Environment environment;

    HikariPoolTlsAssertion(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource pool
                && AwsTransportSecurityConfiguration.enforced(environment)) {
            String where = "DataSource " + beanName;
            if (pool.getDataSourceClassName() != null || pool.getDataSource() != null) {
                throw new IllegalStateException(where + " must connect through its verified jdbcUrl, not a"
                        + " dataSourceClassName or DataSource (spring.datasource.hikari.data-source-class-name)");
            }
            JdbcTlsUrlPolicy.requireVerifiedTls(where + " jdbcUrl", pool.getJdbcUrl(),
                    AwsTransportSecurityConfiguration.caBundle(environment));
            Map<String, String> driverProperties = new LinkedHashMap<>();
            pool.getDataSourceProperties().forEach((key, value) -> driverProperties.put(String.valueOf(key), ""));
            JdbcTlsUrlPolicy.requireNoTlsDriverProperties(where + " dataSourceProperties", driverProperties);
        }
        return bean;
    }
}
