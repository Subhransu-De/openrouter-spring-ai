package example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BranchesTests {

	@Test
	void takesOneBranch() {
		assertEquals(1, Branches.pick(true));
	}

}
