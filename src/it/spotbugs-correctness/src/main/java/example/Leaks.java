package example;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public final class Leaks {

	private Leaks() {
	}

	public static int nullablePath(boolean present) {
		String value = present ? "value" : null;
		return value.length();
	}

	public static int unclosed(File file) throws IOException {
		FileInputStream stream = new FileInputStream(file);
		return stream.read();
	}

}
