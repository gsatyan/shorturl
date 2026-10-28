package com.schwab.assessment.schwab_url_shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

class CodeGeneratorTests {

	@Test
	void codesAreSevenBase62Characters() {
		for (long id : new long[] { 0, 1, 10_001, 3_521_614_606_207L }) {
			assertThat(CodeGenerator.encode(id)).hasSize(7).matches("[0-9A-Za-z]{7}");
		}
	}

	@Test
	void distinctIdsGiveDistinctCodes() {
		var codes = new HashSet<String>();
		for (long id = 1; id <= 200_000; id++) {
			codes.add(CodeGenerator.encode(id));
		}
		assertThat(codes).hasSize(200_000);
	}

	@Test
	void consecutiveIdsDontGiveConsecutiveLookingCodes() {
		var first = CodeGenerator.encode(10_001);
		var second = CodeGenerator.encode(10_002);

		int samePositions = 0;
		for (int i = 0; i < 7; i++) {
			samePositions += (first.charAt(i) == second.charAt(i)) ? 1 : 0;
		}
		assertThat(samePositions).as("%s vs %s", first, second).isLessThan(2);
	}

	@Test
	void codesAreStable() {
		// Pins A and B: if these change, every issued code would map differently (see CodeGenerator). Expected values
		// computed independently: (2_176_477_521_915 · id + 1_234_567_890_123) mod 62^7, Base62.
		assertThat(CodeGenerator.encode(1)).isEqualTo("y3J8vcU");
		assertThat(CodeGenerator.encode(10_001)).isEqualTo("J7pqpa2");
		assertThat(CodeGenerator.encode(10_002)).isEqualTo("vRYehxx");
	}

	@Test
	void idsOutsideTheCodeSpaceAreRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> CodeGenerator.encode(-1));
		assertThatIllegalArgumentException().isThrownBy(() -> CodeGenerator.encode(CodeGenerator.CODE_SPACE));
	}

}
