package com.filinglens.company;

import java.util.Optional;

/** Company lookups for other modules. */
public interface CompanyDirectory {

	/**
	 * Finds a company by CIK, loading it from SEC EDGAR if it is not known yet.
	 * @throws com.filinglens.platform.error.NotFoundException if SEC has no such company
	 * @throws com.filinglens.edgar.EdgarUnavailableException if SEC is unavailable and
	 * the company is not stored
	 */
	CompanyRef resolve(String cik);

	Optional<CompanyRef> findById(long id);

}
