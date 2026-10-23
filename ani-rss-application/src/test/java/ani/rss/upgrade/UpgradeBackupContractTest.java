package ani.rss.upgrade;

import ani.rss.backup.BackupArchive;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.bean.BeanUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpgradeBackupContractTest {
    @TempDir Path directory;
    private String previousConfig;
    private Config original;
    @BeforeEach void before() {
        previousConfig=System.getProperty("CONFIG"); System.setProperty("CONFIG",directory.resolve("source").toString()); DatabaseManager.close(); original=ConfigUtil.snapshot();
    }
    @AfterEach void after() {
        DatabaseManager.close(); BeanUtil.copyProperties(original,ConfigUtil.CONFIG);
        if(previousConfig==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",previousConfig);
    }
    @Test void exportedDatabasePreservesAllUpgradeTablesSchemaClaimsAndCancellation() throws Exception {
        Path source=Files.createDirectories(directory.resolve("source")); var settings=new UpgradeSettings(); settings.setDefaultPolicyId("policy");
        Config config=ConfigUtil.snapshot(); config.setUpgrade(settings).setUpgradeMode(UpgradeMode.OFF);
        Files.writeString(source.resolve("config.v2.json"),GsonStatic.toJson(config)); Files.writeString(source.resolve("ani.v2.json"),"[]");
        UpgradeRepository repository=new UpgradeRepository();
        var policy=new QualityPolicyEngine.Policy(); policy.setId("policy"); repository.save("upgrade_policies",policy.getId(),null,null,"ACTIVE",policy.getRevision(),policy);
        Target target=new Target(); target.setId("target"); target.setSubjectId("100"); target.setLibraryId("lib");
        repository.save("upgrade_targets",target.getId(),target.getLibraryId(),"100:lib",target.getState(),target.getRevision(),target);
        Release release=new ReleaseAnalysisService().parse("[A] Example - 01 [1080p][CHS][MKV]","100","FIXTURE"); release.setId("release"); release.setInfoHash("a".repeat(40));
        repository.save("upgrade_releases",release.getId(),target.getId(),"target:"+release.getInfoHash(),"ELIGIBLE",1,release);
        Version version=new Version(); version.setId("version"); version.setTargetId(target.getId()); version.setAttributes(release); version.setPath(directory.resolve("media.mkv").toString());
        repository.save("upgrade_versions",version.getId(),target.getId(),null,"ACTIVE",1,version);
        Job installing=new Job(); installing.setId("installing"); installing.setTargetId(target.getId()); installing.setState(JobState.INSTALLING); installing.setLeaseUntil(123456); installing.setGeneration(12);
        repository.save("upgrade_jobs",installing.getId(),target.getId(),"install:fixture",installing.getState().name(),1,installing);
        Job cancelled=new Job(); cancelled.setId("cancelled"); cancelled.setState(JobState.QUEUED); repository.save("upgrade_jobs",cancelled.getId(),target.getId(),"cancel:fixture",cancelled.getState().name(),1,cancelled); repository.requestCancellation(cancelled.getId());
        JobFile file=new JobFile(); file.setId("file"); file.setJobId(installing.getId()); file.setAttributes(release); file.setAction("INSTALL");
        repository.save("upgrade_job_files",file.getId(),installing.getId(),null,"PLANNED",1,file); assertTrue(new ChapterCommitLockService().acquire(installing,target,List.of(file),0));
        for(String table:UpgradeRepository.TABLES) if(repository.counts(table).isEmpty()) repository.save(table,table+":fixture",target.getId(),null,"FIXTURE",2,Map.of("id",table+":fixture","subjectId","100","libraryId","lib","evidence","saved fixture"));
        List<String> tables=new ArrayList<>(UpgradeRepository.TABLES); tables.add("upgrade_commit_locks");
        Map<String,List<List<Object>>> expected=DatabaseManager.withConnection(connection->snapshot(connection,tables));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); BackupArchive.create(bytes,source,"3.2.43.72");
        version.setProtectedVersion(true); repository.save("upgrade_versions",version.getId(),target.getId(),null,"ACTIVE",2,version);
        Path archive=Files.write(directory.resolve("backup.zip"),bytes.toByteArray()),extracted=directory.resolve("extracted");
        assertTrue(BackupArchive.validateAndExtract(archive,extracted).valid());
        try(Connection restored=DriverManager.getConnection("jdbc:sqlite:"+extracted.resolve("database.db")); Statement query=restored.createStatement()) {
            query.execute("PRAGMA query_only=ON"); assertEquals(expected,snapshot(restored,tables));
            try(ResultSet rows=query.executeQuery("SELECT MAX(version) FROM schema_migrations")) { assertTrue(rows.next()); assertEquals(12,rows.getInt(1)); }
            try(ResultSet rows=query.executeQuery("SELECT cancel_requested FROM upgrade_jobs WHERE id='cancelled'")) { assertTrue(rows.next()); assertTrue(rows.getBoolean(1)); }
            try(ResultSet rows=query.executeQuery("SELECT state FROM upgrade_commit_locks")) { assertTrue(rows.next()); assertEquals("HELD",rows.getString(1)); }
            try(ResultSet rows=query.executeQuery("PRAGMA integrity_check")) { assertTrue(rows.next()); assertEquals("ok",rows.getString(1)); }
        }
        assertTrue(repository.get("upgrade_versions",version.getId(),Version.class).orElseThrow().isProtectedVersion());
        assertEquals(UpgradeMode.OFF,GsonStatic.fromJson(Files.readString(extracted.resolve("config.v2.json")),Config.class).getUpgradeMode());
    }
    private static Map<String,List<List<Object>>> snapshot(Connection connection,List<String> tables) throws SQLException {
        Map<String,List<List<Object>>> snapshot=new LinkedHashMap<>();
        for(String table:tables) try(Statement query=connection.createStatement(); ResultSet rows=query.executeQuery("SELECT * FROM "+table+" ORDER BY 1")) {
            List<List<Object>> records=new ArrayList<>(); int columns=rows.getMetaData().getColumnCount();
            while(rows.next()) { List<Object> record=new ArrayList<>(); for(int index=1;index<=columns;index++) record.add(rows.getObject(index)); records.add(record); }
            snapshot.put(table,records);
        }
        return snapshot;
    }
}
