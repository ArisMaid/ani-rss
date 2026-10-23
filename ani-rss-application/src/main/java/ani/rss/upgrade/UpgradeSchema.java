package ani.rss.upgrade;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class UpgradeSchema {
    private UpgradeSchema() {}
    public static void migrate(Connection c, int version) throws SQLException {
        if (version >= 12) return;
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            // The envelope columns support bounded queries. Typed payloads retain
            // immutable evidence and snapshots without extending every index.
            if(version<10) {
            for (String table : UpgradeRepository.TABLES) {
                s.executeUpdate("CREATE TABLE " + table + " (id TEXT PRIMARY KEY, parent_id TEXT NOT NULL DEFAULT '',"
                        + " identity_key TEXT, state TEXT NOT NULL DEFAULT '', revision INTEGER NOT NULL DEFAULT 1,"
                        + " updated_at INTEGER NOT NULL, payload TEXT NOT NULL CHECK(length(payload)<=1048576))");
                s.executeUpdate("CREATE INDEX idx_" + table + "_parent_state ON " + table + "(parent_id,state,updated_at)");
                s.executeUpdate("CREATE UNIQUE INDEX idx_" + table + "_identity ON " + table + "(identity_key) WHERE identity_key IS NOT NULL");
            }
            s.executeUpdate("ALTER TABLE download_ownership ADD COLUMN owner_kind TEXT NOT NULL DEFAULT 'SUBSCRIPTION'");
            s.executeUpdate("ALTER TABLE download_ownership ADD COLUMN owner_id TEXT");
            s.executeUpdate("UPDATE download_ownership SET owner_id=subscription_id");
            s.executeUpdate("INSERT INTO schema_migrations(version,applied_at) VALUES(10," + System.currentTimeMillis() + ")");
            }
            if(version<11) {
            for(String table:UpgradeRepository.TABLES) {
                s.executeUpdate("ALTER TABLE "+table+" ADD COLUMN subject_id TEXT");
                s.executeUpdate("ALTER TABLE "+table+" ADD COLUMN library_id TEXT");
                s.executeUpdate("ALTER TABLE "+table+" ADD COLUMN resource_hash TEXT");
                s.executeUpdate("ALTER TABLE "+table+" ADD COLUMN retry_at INTEGER NOT NULL DEFAULT 0");
                s.executeUpdate("ALTER TABLE "+table+" ADD COLUMN lease_until INTEGER NOT NULL DEFAULT 0");
                s.executeUpdate("ALTER TABLE "+table+" ADD COLUMN generation INTEGER NOT NULL DEFAULT 0");
                s.executeUpdate("CREATE INDEX idx_"+table+"_ready ON "+table+"(state,retry_at,lease_until)");
            }
            s.executeUpdate("ALTER TABLE upgrade_jobs ADD COLUMN cancel_requested INTEGER NOT NULL DEFAULT 0");
            s.executeUpdate("CREATE TRIGGER upgrade_subscription_owner AFTER INSERT ON download_ownership WHEN NEW.owner_kind='SUBSCRIPTION' AND NEW.owner_id IS NULL BEGIN UPDATE download_ownership SET owner_id=NEW.subscription_id WHERE ownership_id=NEW.ownership_id; END");
            s.executeUpdate("CREATE TRIGGER upgrade_subscription_owner_change AFTER UPDATE OF subscription_id ON download_ownership WHEN NEW.owner_kind='SUBSCRIPTION' BEGIN UPDATE download_ownership SET owner_id=NEW.subscription_id WHERE ownership_id=NEW.ownership_id; END");
            s.executeUpdate("INSERT INTO schema_migrations(version,applied_at) VALUES(11,"+System.currentTimeMillis()+")");
            }
            s.executeUpdate("CREATE TABLE upgrade_commit_locks (media_key TEXT PRIMARY KEY, subject_id TEXT NOT NULL, library_id TEXT NOT NULL, chapter_key TEXT NOT NULL, content_edition TEXT NOT NULL, operation_id TEXT NOT NULL, state TEXT NOT NULL, lease_until INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE INDEX idx_upgrade_commit_locks_operation ON upgrade_commit_locks(operation_id,state)");
            s.executeUpdate("CREATE INDEX idx_upgrade_commit_locks_subject_library ON upgrade_commit_locks(subject_id,library_id,state)");
            s.executeUpdate("INSERT INTO schema_migrations(version,applied_at) VALUES(12,"+System.currentTimeMillis()+")");
            c.commit();
        } catch (SQLException e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(auto); }
    }
}
