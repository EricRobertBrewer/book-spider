package com.ericrobertbrewer.bookspider;

import java.sql.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class AbstractDatabaseHelper {

    static {
        // Ensure that a Java database connection class exists.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Unable to find suitable SQLite driver (JDBC). Perhaps try adding as a dependency `org.xerial:sqlite-jdbc:3.x.x`.", e);
        }
    }

    private final Logger logger;
    private Connection connection = null;

    public AbstractDatabaseHelper(Logger logger) {
        this.logger = logger;
    }

    public Logger getLogger() {
        return logger;
    }

    public Connection getConnection() {
        return connection;
    }

    public void connect(String fileName) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + fileName);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Unable to connect to database: `" + fileName + "`.", e);
        }
    }

    public boolean isConnected() {
        if (connection == null) {
            return false;
        }
        try {
            return !connection.isClosed();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Unable to query whether database connection is closed.", e);
        }
        return false;
    }

    public void close() {
        try {
            connection.close();
            connection = null;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Unable to close database connection.", e);
        }
    }

    public abstract void ensureTableExists(String name) throws SQLException;

    protected static void setStringOrNull(PreparedStatement s, int parameterIndex, String value) throws SQLException {
        if (value != null) {
            s.setString(parameterIndex, value);
        } else {
            s.setNull(parameterIndex, Types.VARCHAR);
        }
    }
}
