package ani.rss.download;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.torrent.TransmissionRpcBody;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TransmissionRpcCodec {
    private TransmissionRpcCodec() {
    }

    public static JsonObject encode(TransmissionRpcBody body, TransmissionDialect dialect, long id) {
        JsonObject root = new JsonObject();
        Map<String, Object> arguments = convertArguments(body.getArguments(), dialect);
        if (dialect == TransmissionDialect.JSON_RPC_2) {
            root.addProperty("jsonrpc", "2.0");
            root.addProperty("id", id);
            root.addProperty("method", body.getMethod().getJsonRpcValue());
            root.add("params", JsonParser.parseString(GsonStatic.toJson(arguments)));
        } else {
            root.addProperty("tag", "ani-rss-" + id);
            root.addProperty("method", body.getMethod().getLegacyValue());
            root.add("arguments", JsonParser.parseString(GsonStatic.toJson(arguments)));
        }
        return root;
    }

    public static JsonObject payload(String response, TransmissionDialect dialect) {
        JsonObject root = JsonParser.parseString(response).getAsJsonObject();
        JsonElement payload = root.get(dialect == TransmissionDialect.JSON_RPC_2 ? "result" : "arguments");
        if (payload != null && payload.isJsonObject()) {
            JsonObject object = payload.getAsJsonObject();
            if (dialect == TransmissionDialect.JSON_RPC_2 && object.has("arguments") &&
                    object.get("arguments").isJsonObject()) {
                return object.getAsJsonObject("arguments");
            }
            return object;
        }
        if (root.has("arguments") && root.get("arguments").isJsonObject()) {
            return root.getAsJsonObject("arguments");
        }
        return new JsonObject();
    }

    public static boolean success(String response, TransmissionDialect dialect) {
        JsonObject root = JsonParser.parseString(response).getAsJsonObject();
        if (dialect == TransmissionDialect.JSON_RPC_2) {
            return (!root.has("error") || root.get("error").isJsonNull()) && root.has("result");
        }
        return "success".equalsIgnoreCase(root.has("result") ? root.get("result").getAsString() : "");
    }

    public static boolean looksLikeJsonRpc2(String response) {
        try {
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            return "2.0".equals(root.has("jsonrpc") ? root.get("jsonrpc").getAsString() : null);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Map<String, Object> convertArguments(Map<String, Object> arguments, TransmissionDialect dialect) {
        if (dialect == TransmissionDialect.LEGACY) {
            return arguments;
        }
        Map<String, Object> converted = new HashMap<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String key = snakeCase(entry.getKey());
            Object value = entry.getValue();
            if ("fields".equals(key) && value instanceof List<?> list) {
                List<String> fields = new ArrayList<>();
                for (Object field : list) {
                    fields.add(snakeCase(String.valueOf(field)));
                }
                value = fields;
            }
            converted.put(key, value);
        }
        return converted;
    }

    private static String snakeCase(String value) {
        return switch (value) {
            case "download-dir" -> "download_dir";
            case "delete-local-data" -> "delete_local_data";
            case "hashString" -> "hash_string";
            case "downloadDir" -> "download_dir";
            case "isFinished" -> "is_finished";
            case "isStalled" -> "is_stalled";
            case "totalSize" -> "total_size";
            case "haveValid" -> "have_valid";
            default -> value.replace('-', '_')
                    .replaceAll("([a-z])([A-Z])", "$1_$2")
                    .toLowerCase(Locale.ROOT);
        };
    }
}
