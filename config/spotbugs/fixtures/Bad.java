package example;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;

public class Bad {
    private Object instance;
    private final Date date;
    public int unread = 42;

    public Bad(Date date) { this.date = date; }
    public Date date() { return date; }

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

    public int entry() { return internal(7); }
    public int internal(int value) { return value + 1; }

    public void sql(Connection connection, String value) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SELECT name FROM users WHERE name = '" + value + "'");
        }
    }
}
