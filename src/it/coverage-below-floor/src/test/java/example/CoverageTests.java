package example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoverageTests {

	@Test
	void coversOneMethod() {
		assertEquals(1, Coverage.covered());
	}

}
