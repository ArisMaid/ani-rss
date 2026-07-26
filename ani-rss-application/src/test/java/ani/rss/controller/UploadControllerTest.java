package ani.rss.controller;

import ani.rss.entity.Global;
import ani.rss.service.UploadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadControllerTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.toString());
        Global.REQUEST.set(new MockHttpServletRequest());
    }

    @AfterEach
    void tearDown() {
        Global.REQUEST.remove();
        System.clearProperty("CONFIG");
    }

    @Test
    void coverUploadUsesValidatedContentAndContentAddressedName() throws Exception {
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        MockMultipartFile file = new MockMultipartFile("file", "cover.txt", "text/plain", png);

        String relative = new UploadService().storeCover(file);

        assertTrue(relative.endsWith(".png"));
        assertTrue(Files.isRegularFile(tempDir.resolve("files").resolve(relative)));
    }

    @Test
    void forgedCoverAndOversizedUploadAreRejected() {
        MockMultipartFile forged = new MockMultipartFile(
                "file", "cover.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
        assertThrows(IllegalArgumentException.class, () -> new UploadService().storeCover(forged));

        MockMultipartFile oversized = new MockMultipartFile(
                "file", "cover.png", "image/png", new byte[1024 * 1024 + 1]);
        assertThrows(IllegalArgumentException.class, () -> new UploadService().storeCover(oversized));
    }

    @Test
    void base64ModeAcceptsOnlyBoundedTorrentPayloads() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("type", "getBase64");
        Global.REQUEST.set(request);
        byte[] torrent = ("d8:announce14:http://tracker4:infod6:lengthi1e4:name1:x" +
                "12:piece lengthi16384e6:pieces20:12345678901234567890ee")
                .getBytes(StandardCharsets.US_ASCII);

        Object encoded = new UploadService().encodeTorrent(new MockMultipartFile(
                "file", "sample.torrent", "application/x-bittorrent", torrent));
        assertEquals(Base64.getEncoder().encodeToString(torrent), encoded);

        assertThrows(IllegalArgumentException.class, () -> new UploadService().encodeTorrent(
                new MockMultipartFile("file", "sample.txt", "text/plain", torrent)));
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> new UploadService().encodeTorrent(
                new MockMultipartFile("file", "sample.torrent", "application/x-bittorrent",
                        "d3:foo3:bare".getBytes(StandardCharsets.US_ASCII))));
        assertEquals("upload is not a valid torrent file", malformed.getMessage());
    }
}
