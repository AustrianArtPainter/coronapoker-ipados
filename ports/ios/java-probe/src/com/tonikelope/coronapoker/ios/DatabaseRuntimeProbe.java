package com.tonikelope.coronapoker.ios;

import com.tonikelope.coronapoker.core.DatabaseService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;

/** Native JDBC checks through the unchanged game DatabaseService. */
public final class DatabaseRuntimeProbe {
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    public static String run(String file) throws Exception {
        try (DatabaseService service = new DatabaseService(file)) {
            service.start();
            Connection db = service.connection();
            try (Statement s = db.createStatement()) {
                s.execute("DROP TABLE IF EXISTS ios_probe_child");
                s.execute("DROP TABLE IF EXISTS ios_probe_balance");
                s.execute("CREATE TABLE ios_probe_balance(id INTEGER PRIMARY KEY, cents INTEGER NOT NULL CHECK(cents>=0))");
                s.execute("CREATE TABLE ios_probe_child(id INTEGER PRIMARY KEY, owner INTEGER REFERENCES ios_probe_balance(id))");
                s.execute("INSERT INTO ios_probe_balance VALUES(1,1000),(2,1000)");
            }
            db.setAutoCommit(false);
            try (PreparedStatement s = db.prepareStatement("UPDATE ios_probe_balance SET cents = cents + ? WHERE id = ?")) {
                s.setInt(1, -200); s.setInt(2, 1); require(s.executeUpdate() == 1, "debit");
                s.setInt(1, 200); s.setInt(2, 2); require(s.executeUpdate() == 1, "credit");
            }
            db.commit();
            Savepoint save = db.setSavepoint("original_jdbc_savepoint");
            try (Statement s = db.createStatement()) { s.executeUpdate("UPDATE ios_probe_balance SET cents=0 WHERE id=1"); }
            db.rollback(save);
            db.releaseSavepoint(save);
            db.commit();
            try (Statement s = db.createStatement(); ResultSet result = s.executeQuery("SELECT SUM(cents) total, MIN(cents) smallest FROM ios_probe_balance")) {
                require(result.next() && result.getLong("total") == 2000 && result.getLong("smallest") == 800, "atomic balance and savepoint recovery");
            }
            boolean foreignKey = false;
            try (Statement s = db.createStatement()) { s.executeUpdate("INSERT INTO ios_probe_child VALUES(1,99)"); }
            catch (java.sql.SQLException expected) { foreignKey = true; }
            require(foreignKey, "foreign-key enforcement");
            db.rollback();
            db.setAutoCommit(true);
            String journal;
            try (Statement s = db.createStatement(); ResultSet result = s.executeQuery("PRAGMA journal_mode")) {
                require(result.next(), "journal mode query"); journal = result.getString(1);
            }
            require("delete".equalsIgnoreCase(journal), "original journal mode");
            int synchronous;
            try (Statement s = db.createStatement(); ResultSet result = s.executeQuery("PRAGMA synchronous")) {
                require(result.next(), "synchronous query"); synchronous = result.getInt(1);
            }
            require(synchronous == 2, "original FULL synchronous policy");
            service.releaseConnection();
            try (Statement s = service.connection().createStatement(); ResultSet result = s.executeQuery("SELECT SUM(cents) FROM ios_probe_balance")) {
                require(result.next() && result.getLong(1) == 2000, "durability after reopen");
            }
            return "{\"scope\":\"unchanged DatabaseService on static SQLite JDBC\",\"sqlite\":\""
                    + NativeSQLite.version() + "\",\"transactions\":true,\"savepoints\":true,\"foreignKeys\":true,"
                    + "\"journalDelete\":true,\"synchronousFull\":true,\"reopenDurability\":true}";
        }
    }

    public static void main(String[] args) throws Exception { System.out.println(run(args[0])); }
}
