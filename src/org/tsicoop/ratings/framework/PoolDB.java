package org.tsicoop.ratings.framework;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

@SuppressWarnings("unchecked")
public class PoolDB extends DB {

    private static HikariDataSource basicDataSource = null;

    private void initBasicDataSource() {
        HikariConfig config = new HikariConfig();

        String host = SystemConfig.getAppConfig().getProperty("framework.db.host");
        String port = SystemConfig.getAppConfig().getProperty("framework.db.port");
        String dbName = SystemConfig.getAppConfig().getProperty("framework.db.name");

        String username = SystemConfig.getAppConfig().getProperty("framework.db.user");
        String password = SystemConfig.getAppConfig().getProperty("framework.db.password");

        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + dbName;

        System.out.println("DB HOST = " + host);
        System.out.println("DB PORT = " + port);
        System.out.println("DB URL = " + jdbcUrl);

        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        basicDataSource = new HikariDataSource(config);
        System.out.println("HikariCP DataSource initialized for PostgreSQL.");
    }

    public PoolDB() throws SQLException {
        super();
        con = createConnection(true);
    }

    public PoolDB(boolean autocommit) throws SQLException {
        super();
        con = createConnection(autocommit);
    }

    public Connection getConnection() {
        return con;
    }

    public Connection createConnection(boolean autocommit) throws SQLException {
        Connection connection = null;
        try {
            Class.forName("org.postgresql.Driver");

            if (basicDataSource == null) {
                initBasicDataSource();
            }

            connection = basicDataSource.getConnection();
            connection.setAutoCommit(autocommit);

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        return connection;
    }
}