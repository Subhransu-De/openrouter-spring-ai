package example;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

public class Bad {
    private Object instance;

    public int nullablePath(boolean present) {
        String value = present ? "value" : null;
        return value.length();
    }

    public int nullableReturn(File directory) { return directory.list().length; }

    public int resource(File file) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        return stream.read();
    }

    public Object doubleCheck() {
        if (instance == null) {
            synchronized (this) {
                if (instance == null) { instance = new Object(); }
            }
        }
        return instance;
    }

    public void sql(Connection connection, String value) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SELECT name FROM users WHERE name = '" + value + "'");
        }
    }
}
