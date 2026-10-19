package example;

// An interface has no constructor line, so the one line below is fully covered.
public interface Branches {

	static int pick(boolean flag) {
		return flag ? 1 : 0;
	}

}
