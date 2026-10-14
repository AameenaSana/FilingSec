package com.filinglens.company.internal;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.filinglens.company.CompanyRef;
import com.filinglens.company.Watchlists;

@Component
class WatchlistsAdapter implements Watchlists {

	private final JdbcClient jdbc;

	private final CompanyRepository companies;

	WatchlistsAdapter(JdbcClient jdbc, CompanyRepository companies) {
		this.jdbc = jdbc;
		this.companies = companies;
	}

	@Override
	public List<CompanyRef> watchedCompanies() {
		return jdbc.sql("SELECT DISTINCT company_id FROM watchlist_item ORDER BY company_id")
			.query(Long.class)
			.list()
			.stream()
			.flatMap(id -> companies.findRef(id).stream())
			.toList();
	}

	@Override
	public List<Watcher> watchers(long companyId) {
		return jdbc.sql("SELECT user_id, form_types, notify FROM watchlist_item WHERE company_id = :c ORDER BY id")
			.param("c", companyId)
			.query((rs, n) -> new Watcher(rs.getObject("user_id", UUID.class),
					Arrays.asList((String[]) rs.getArray("form_types").getArray()), rs.getBoolean("notify")))
			.list();
	}

}
