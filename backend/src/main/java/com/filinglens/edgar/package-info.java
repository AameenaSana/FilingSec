/**
 * SEC EDGAR gateway: the only code that talks to sec.gov.
 * <p>
 * Enforces SEC's fair-access policy for every request: a declared
 * {@code User-Agent} with a contact email, a global request-rate cap below SEC's
 * 10 requests/second, gzip transfer, bounded retries with backoff, and a
 * traffic pause when SEC signals throttling.
 * <p>
 * Public API: {@link com.filinglens.edgar.EdgarClient} and its response types.
 *
 * @see <a href="https://www.sec.gov/os/accessing-edgar-data">Accessing EDGAR Data</a>
 */
@ApplicationModule(displayName = "SEC EDGAR gateway")
package com.filinglens.edgar;

import org.springframework.modulith.ApplicationModule;
