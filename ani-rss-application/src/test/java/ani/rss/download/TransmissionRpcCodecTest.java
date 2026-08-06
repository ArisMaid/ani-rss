package ani.rss.download;

import ani.rss.entity.torrent.TransmissionRpcBody;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransmissionRpcCodecTest {
    @Test
    void encodesLegacyMethodAndArguments() {
        JsonObject root = TransmissionRpcCodec.encode(
                TransmissionRpcBody.torrentRemove("42", false), TransmissionDialect.LEGACY, 7);

        assertEquals("torrent-remove", root.get("method").getAsString());
        assertTrue(root.get("tag").getAsString().startsWith("ani-rss-"));
        assertEquals("42", root.getAsJsonObject("arguments")
                .getAsJsonArray("ids").get(0).getAsString());
        assertFalse(root.getAsJsonObject("arguments")
                .get("delete-local-data").getAsBoolean());
    }

    @Test
    void encodesJsonRpc2WithSnakeCaseParameters() {
        JsonObject root = TransmissionRpcCodec.encode(
                TransmissionRpcBody.torrentRemove("42", true), TransmissionDialect.JSON_RPC_2, 8);

        assertEquals("2.0", root.get("jsonrpc").getAsString());
        assertEquals(8, root.get("id").getAsLong());
        assertEquals("torrent_remove", root.get("method").getAsString());
        assertEquals("42", root.getAsJsonObject("params")
                .getAsJsonArray("ids").get(0).getAsString());
        assertTrue(root.getAsJsonObject("params")
                .get("delete_local_data").getAsBoolean());
    }

    @Test
    void encodesVerifyAndStartForBothTransmissionDialects() {
        JsonObject legacyVerify = TransmissionRpcCodec.encode(
                TransmissionRpcBody.torrentVerify("42"), TransmissionDialect.LEGACY, 9);
        JsonObject modernStart = TransmissionRpcCodec.encode(
                TransmissionRpcBody.torrentStart("42"), TransmissionDialect.JSON_RPC_2, 10);

        assertEquals("torrent-verify", legacyVerify.get("method").getAsString());
        assertEquals("torrent_start", modernStart.get("method").getAsString());
        assertEquals("42", modernStart.getAsJsonObject("params")
                .getAsJsonArray("ids").get(0).getAsString());
    }

    @Test
    void keepsNewDownloadsActiveInBothTransmissionDialects() {
        JsonObject legacy = TransmissionRpcCodec.encode(
                TransmissionRpcBody.torrentAdd(List.of("ani-rss"), "magnet:?xt=urn:btih:test", "/downloads"),
                TransmissionDialect.LEGACY, 11);
        JsonObject modern = TransmissionRpcCodec.encode(
                TransmissionRpcBody.torrentAdd(List.of("ani-rss"), "magnet:?xt=urn:btih:test", "/downloads"),
                TransmissionDialect.JSON_RPC_2, 12);

        assertFalse(legacy.getAsJsonObject("arguments").get("paused").getAsBoolean());
        assertFalse(modern.getAsJsonObject("params").get("paused").getAsBoolean());
    }

    @Test
    void parsesBothResponseShapesAndRejectsRpcErrors() {
        String legacy = "{\"arguments\":{\"torrent-added\":{\"id\":\"1\"}},\"result\":\"success\"}";
        String modern = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"torrent_added\":{\"hash_string\":\"hash-1\"}}}";
        String error = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32600}}";

        assertEquals("1", TransmissionRpcCodec.payload(legacy, TransmissionDialect.LEGACY)
                .getAsJsonObject("torrent-added").get("id").getAsString());
        assertEquals("hash-1", TransmissionRpcCodec.payload(modern, TransmissionDialect.JSON_RPC_2)
                .getAsJsonObject("torrent_added").get("hash_string").getAsString());
        assertTrue(TransmissionRpcCodec.success(legacy, TransmissionDialect.LEGACY));
        assertTrue(TransmissionRpcCodec.success(modern, TransmissionDialect.JSON_RPC_2));
        assertFalse(TransmissionRpcCodec.success(error, TransmissionDialect.JSON_RPC_2));
    }
}
