package example;

import java.sql.Connection;
import java.sql.SQLException;

public final class Query {

	private Query() {
	}

	public static void byName(Connection connection, String name) throws SQLException {
		try (var statement = connection.createStatement()) {
			statement.execute("SELECT name FROM users WHERE name = '" + name + "'");
		}
	}

}
