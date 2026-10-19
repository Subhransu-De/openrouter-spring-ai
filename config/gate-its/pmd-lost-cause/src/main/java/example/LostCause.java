package example;

import java.io.IOException;

public final class LostCause {

	private LostCause() {
	}

	public static void run() {
		try {
			throw new IOException("synthetic");
		}
		catch (IOException ex) {
			throw new IllegalStateException("cause dropped");
		}
	}

}
