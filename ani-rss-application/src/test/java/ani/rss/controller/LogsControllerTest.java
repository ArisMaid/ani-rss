package ani.rss.controller;

import ani.rss.entity.Global;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogsControllerTest {
    @TempDir
    Path tempDir;
    private String previousConfig;

    @BeforeEach
    void setUp() throws Exception {
        previousConfig = System.getProperty("CONFIG");
        System.setProperty("CONFIG", tempDir.toString());
        Path logs = Files.createDirectories(tempDir.resolve("logs"));
        Files.writeString(logs.resolve("ani-rss.log"), "safe log");
        Files.writeString(logs.resolve("not-a-log.txt"), "skip");
        Path nested = Files.createDirectories(logs.resolve("nested"));
        Files.writeString(nested.resolve("nested.log"), "skip nested");
        Global.RESPONSE.set(new MockHttpServletResponse());
    }

    @AfterEach
    void tearDown() {
        Global.RESPONSE.remove();
        if (previousConfig == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", previousConfig);
        }
    }

    @Test
    void exportsOnlyDirectRegularLogFiles() throws Exception {
        new LogsController().downloadLogs();
        MockHttpServletResponse response = (MockHttpServletResponse) Global.RESPONSE.get();
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(response.getContentAsByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
            }
        }

        assertEquals(List.of("ani-rss.log"), entries);
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }
}
