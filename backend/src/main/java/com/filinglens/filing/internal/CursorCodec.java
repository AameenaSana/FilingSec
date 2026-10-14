package com.filinglens.filing.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.filinglens.edgar.Accession;
import com.filinglens.platform.error.InvalidRequestException;

/** Opaque page cursors: the accession number of the last item, base64url-encoded. */
final class CursorCodec {

	private CursorCodec() {
	}

	static String encode(String accession) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(accession.getBytes(StandardCharsets.US_ASCII));
	}

	static String decode(String cursor) {
		try {
			String accession = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
			if (Accession.isValid(accession)) {
				return Accession.normalize(accession);
			}
		}
		catch (IllegalArgumentException ex) {
			// fall through
		}
		throw new InvalidRequestException("invalid-cursor", "The page cursor is not valid");
	}

}
