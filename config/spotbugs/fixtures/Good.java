package example;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;

public class Good {
    private volatile Object instance;
    private final Date date;
    public int read = 42;

    public Good(Date date) { this.date = new Date(date.getTime()); }
    public Date date() { return new Date(date.getTime()); }

    public int nullablePath(boolean present) {
        String value = present ? "value" : null;
        return value == null ? 0 : value.length();
    }

    public int nullableReturn(File directory) {
        String[] entries = directory.list();
        return entries == null ? 0 : entries.length;
    }

    public int resource(File file) throws IOException {
        try (FileInputStream stream = new FileInputStream(file)) { return stream.read(); }
    }

    public Object doubleCheck() {
        if (instance == null) {
            synchronized (this) {
                if (instance == null) { instance = new Object(); }
            }
        }
        return instance;
    }

    public int entry() { return internal(read); }
    private int internal(int value) { return value + 1; }

    public void sql(Connection connection, String value) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT name FROM users WHERE name = ?")) {
            statement.setString(1, value);
            statement.execute();
        }
    }
}
