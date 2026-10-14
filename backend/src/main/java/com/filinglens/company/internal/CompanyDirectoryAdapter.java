package com.filinglens.company.internal;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.filinglens.company.CompanyDirectory;
import com.filinglens.company.CompanyRef;

@Component
class CompanyDirectoryAdapter implements CompanyDirectory {

	private final CompanyService companies;

	private final CompanyRepository repository;

	CompanyDirectoryAdapter(CompanyService companies, CompanyRepository repository) {
		this.companies = companies;
		this.repository = repository;
	}

	@Override
	public CompanyRef resolve(String cik) {
		long id = companies.resolveId(cik);
		return repository.findRef(id).orElseThrow();
	}

	@Override
	public Optional<CompanyRef> findById(long id) {
		return repository.findRef(id);
	}

}
