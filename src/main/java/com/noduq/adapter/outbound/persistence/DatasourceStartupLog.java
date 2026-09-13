package com.noduq.adapter.outbound.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
class DatasourceStartupLog implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DatasourceStartupLog.class);

	private final DataSource dataSource;

	DatasourceStartupLog(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (dataSource instanceof HikariDataSource hikari) {
			log.info("Postgres jdbcUser={} jdbcUrl={}", hikari.getUsername(), redact(hikari.getJdbcUrl()));
		}
	}

	private static String redact(String url) {
		if (url == null || url.isBlank()) {
			return "";
		}
		return url.replaceAll("(?i)(password=)[^&]+", "$1***")
				.replaceAll("://([^:/@]+):([^@]+)@", "://$1:***@");
	}
}
