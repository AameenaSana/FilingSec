package com.filinglens.edgar;

/**
 * One row of SEC's ticker directory ({@code company_tickers_exchange.json}).
 *
 * @param cik 10-digit CIK
 * @param exchange may be {@code null} (e.g. OTC listings without an exchange)
 */
public record CompanyTickerEntry(String cik, String name, String ticker, String exchange) {
}
