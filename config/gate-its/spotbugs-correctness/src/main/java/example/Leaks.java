package example;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public final class Leaks {

	private Object instance;

	public static int nullablePath(boolean present) {
		String value = present ? "value" : null;
		return value.length();
	}

	public static int nullableReturn(File directory) {
		return directory.list().length;
	}

	public static int unclosed(File file) throws IOException {
		FileInputStream stream = new FileInputStream(file);
		return stream.read();
	}

	public Object doubleCheck() {
		if (this.instance == null) {
			synchronized (this) {
				if (this.instance == null) {
					this.instance = new Object();
				}
			}
		}
		return this.instance;
	}

}
