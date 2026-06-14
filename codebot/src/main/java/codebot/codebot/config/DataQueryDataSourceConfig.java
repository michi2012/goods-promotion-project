package codebot.codebot.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.jdbc.DataSourceBuilder;

import javax.sql.DataSource;

@Configuration
public class DataQueryDataSourceConfig {

    // initializationFailTimeout(-1): 자격증명이 비어있거나 DB가 아직 준비되지 않아도
    // 애플리케이션 컨텍스트가 시작될 수 있도록 한다. 실제 연결은 쿼리 실행 시점에 시도된다.
    private static DataSource buildDataSource(String url, String username, String password) {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    @Bean
    public DataSource orderDataSource(
            @Value("${data-query.datasource.order.url}") String url,
            @Value("${data-query.datasource.order.username}") String username,
            @Value("${data-query.datasource.order.password}") String password) {
        return buildDataSource(url, username, password);
    }

    @Bean
    public JdbcTemplate orderJdbcTemplate(@Qualifier("orderDataSource") DataSource orderDataSource) {
        return new JdbcTemplate(orderDataSource);
    }

    @Bean
    public DataSource paymentDataSource(
            @Value("${data-query.datasource.payment.url}") String url,
            @Value("${data-query.datasource.payment.username}") String username,
            @Value("${data-query.datasource.payment.password}") String password) {
        return buildDataSource(url, username, password);
    }

    @Bean
    public JdbcTemplate paymentJdbcTemplate(@Qualifier("paymentDataSource") DataSource paymentDataSource) {
        return new JdbcTemplate(paymentDataSource);
    }

    @Bean
    public DataSource userDataSource(
            @Value("${data-query.datasource.user.url}") String url,
            @Value("${data-query.datasource.user.username}") String username,
            @Value("${data-query.datasource.user.password}") String password) {
        return buildDataSource(url, username, password);
    }

    @Bean
    public JdbcTemplate userJdbcTemplate(@Qualifier("userDataSource") DataSource userDataSource) {
        return new JdbcTemplate(userDataSource);
    }
}
