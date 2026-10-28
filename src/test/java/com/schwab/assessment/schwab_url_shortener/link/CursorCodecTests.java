package com.schwab.assessment.schwab_url_shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.schwab.assessment.schwab_url_shortener.domain.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorCodecTests {

	private static final String POSITION = "LINK#2026-10-27T20:00:00.000Z#aZ3kP9q";

	private final CursorCodec codec = new CursorCodec("test-secret");

	@Test
	void roundTripsForTheSameOwner() {
		var cursor = codec.encode("owner-a", POSITION);

		assertThat(cursor).doesNotContain("owner-a");
		assertThat(codec.decode("owner-a", cursor)).isEqualTo(POSITION);
	}

	@Test
	void anotherOwnersCursorIsRejected() {
		assertInvalid("owner-b", codec.encode("owner-a", POSITION));
	}

	@Test
	void editedPositionIsRejected() {
		var cursor = codec.encode("owner-a", POSITION);
		var forged = codec.encode("owner-a", "LINK#2030-01-01T00:00:00.000Z#zzzzzzz").split("\\.")[0] + "."
				+ cursor.split("\\.")[1];

		assertInvalid("owner-a", forged);
	}

	@Test
	void cursorSignedWithAnotherSecretIsRejected() {
		assertInvalid("owner-a", new CursorCodec("other-secret").encode("owner-a", POSITION));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "garbage", "!!!.???", "TElOSyM.", "." })
	void malformedCursorsAreRejected(String cursor) {
		assertInvalid("owner-a", cursor);
	}

	private void assertInvalid(String ownerId, String cursor) {
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> codec.decode(ownerId, cursor))
			.satisfies(failure -> assertThat(failure.getStatusCode().value()).isEqualTo(400));
	}

}
