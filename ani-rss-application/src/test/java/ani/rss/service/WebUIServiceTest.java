package ani.rss.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebUIServiceTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("CONFIG");
    }

    @Test
    void uploadsValidWebUiArchiveAndPersistsMetadata() throws Exception {
        byte[] archive = zip(
                entry("webui.json", "{\"owner\":\"owner\",\"repo\":\"repo\",\"version\":\"1.0.0\",\"filename\":\"webui.zip\"}"),
                directory("assets/"),
                entry("assets/index.html", "<html></html>"));

        WebUIService service = new WebUIService(new GithubService());
        service.upload(new MockMultipartFile("file", "webui.zip", "application/zip", archive));

        assertTrue(Files.isRegularFile(tempDir.resolve("webui/assets/index.html")));
        assertEquals("owner", service.getWebUI().getOwner());
    }

    @Test
    void rejectsZipPathTraversalWithoutWritingOutsideWebUiDirectory() throws Exception {
        byte[] archive = zip(
                entry("webui.json", "{\"owner\":\"owner\",\"repo\":\"repo\",\"version\":\"1.0.0\",\"filename\":\"webui.zip\"}"),
                entry("../outside.txt", "must not be written"));

        assertThrows(IllegalArgumentException.class, () -> new WebUIService(new GithubService()).upload(
                new MockMultipartFile("file", "webui.zip", "application/zip", archive)));
        assertTrue(Files.notExists(tempDir.resolve("outside.txt")));
    }

    @Test
    void rejectsArchiveWithoutSafeMetadata() throws Exception {
        byte[] archive = zip(entry("index.html", "<html></html>"));

        assertThrows(IllegalArgumentException.class, () -> new WebUIService(new GithubService()).upload(
                new MockMultipartFile("file", "webui.zip", "application/zip", archive)));
        assertTrue(Files.notExists(tempDir.resolve("webui")));
    }

    private static Entry entry(String name, String content) {
        return new Entry(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry directory(String name) {
        return new Entry(name, new byte[0]);
    }

    private static byte[] zip(Entry... entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private record Entry(String name, byte[] content) {
    }
}
