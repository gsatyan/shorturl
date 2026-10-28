package com.schwab.assessment.schwab_url_shortener.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 as lowercase hex: how API keys and idempotency keys appear in item keys (docs/DESIGN.md §7.1), so the raw
 * values are never stored.
 */
public final class Hashing {

	private Hashing() {
	}

	public static String sha256Hex(String value) {
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is required on every Java platform", impossible);
		}
	}

}
