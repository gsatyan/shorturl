package com.schwab.assessment.schwab_url_shortener.link;

import java.math.BigInteger;

/**
 * Turns a unique ID into a 7-character code (docs/DESIGN.md §6.1, steps 2–3). The ID goes through the fixed
 * bijection {@code (A·id + B) mod 62^7}, so consecutive IDs give unrelated-looking codes and nobody can enumerate
 * links by counting, then it's Base62-encoded and left-padded.
 * <p>
 * A bijection means distinct IDs below 62^7 always give distinct codes. This hides order but isn't a secret, which
 * is acceptable because short links aren't access controls.
 */
final class CodeGenerator {

	static final int LENGTH = 7;

	static final long CODE_SPACE = 3_521_614_606_208L;   // 62^7

	// NEVER CHANGE A OR B once any code has been issued: every later ID would map onto a different code, and new codes
	// could collide with existing ones. gcd(A, 62^7) = 1, which makes the mapping a bijection. A is about 0.618 · 62^7
	// (the golden ratio), so consecutive IDs land far apart and their codes share no visible pattern.
	private static final BigInteger A = BigInteger.valueOf(2_176_477_521_915L);

	private static final BigInteger B = BigInteger.valueOf(1_234_567_890_123L);

	private static final BigInteger MODULUS = BigInteger.valueOf(CODE_SPACE);

	private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
		.toCharArray();

	private CodeGenerator() {
	}

	static String encode(long id) {
		if (id < 0 || id >= CODE_SPACE) {
			throw new IllegalArgumentException("ID out of range: " + id);
		}
		// BigInteger because A·id overflows a long; this runs once per created link, not per redirect.
		var permuted = A.multiply(BigInteger.valueOf(id)).add(B).mod(MODULUS).longValueExact();
		var code = new char[LENGTH];
		for (int i = LENGTH - 1; i >= 0; i--) {
			code[i] = ALPHABET[(int) (permuted % 62)];
			permuted /= 62;
		}
		return new String(code);
	}

}
