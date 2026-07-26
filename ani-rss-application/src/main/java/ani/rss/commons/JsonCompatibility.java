package ani.rss.commons;

import ani.rss.entity.NotificationConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retains fields added by a newer compatible producer when the local model is
 * saved again. Gson intentionally ignores unknown fields while deserializing,
 * so persistence boundaries use this merger before writing JSON back to disk.
 */
public final class JsonCompatibility {
    private static final Map<Class<?>, Map<String, Type>> FIELDS = new ConcurrentHashMap<>();

    private JsonCompatibility() {
    }

    public static JsonObject mergeObject(JsonObject original, JsonObject current, Class<?> type) {
        JsonObject result = current.deepCopy();
        if (original == null || type == null) {
            return result;
        }
        Map<String, Type> fields = fields(type);
        for (Map.Entry<String, JsonElement> entry : original.entrySet()) {
            Type fieldType = fields.get(entry.getKey());
            if (fieldType == null) {
                result.add(entry.getKey(), entry.getValue().deepCopy());
                continue;
            }
            JsonElement replacement = current.get(entry.getKey());
            if (replacement != null) {
                result.add(entry.getKey(), mergeValue(entry.getValue(), replacement, fieldType));
            }
        }
        return result;
    }

    public static JsonArray mergeArray(JsonArray original, JsonArray current, Class<?> elementType) {
        if (original == null || elementType == null || !isModelType(elementType)) {
            return current.deepCopy();
        }
        Map<String, JsonObject> originalsByIdentifier = uniqueObjectsByIdentifier(original, elementType);
        Set<String> currentIdentifiers = duplicateIdentifiers(current, elementType);

        // Positional matching would attach future fields to the wrong item after a reorder.
        JsonArray result = new JsonArray(current.size());
        for (JsonElement value : current) {
            if (!value.isJsonObject()) {
                result.add(value.deepCopy());
                continue;
            }
            JsonObject object = value.getAsJsonObject();
            String identifier = identifier(object, elementType);
            JsonObject source = identifier == null || currentIdentifiers.contains(identifier)
                    ? null : originalsByIdentifier.get(identifier);
            result.add(mergeObject(source, object, elementType));
        }
        return result;
    }

    private static Map<String, JsonObject> uniqueObjectsByIdentifier(JsonArray values, Class<?> elementType) {
        Map<String, JsonObject> result = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (JsonElement value : values) {
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject object = value.getAsJsonObject();
            String identifier = identifier(object, elementType);
            if (identifier == null || duplicates.contains(identifier)) {
                continue;
            }
            if (result.putIfAbsent(identifier, object) != null) {
                result.remove(identifier);
                duplicates.add(identifier);
            }
        }
        return result;
    }

    private static Set<String> duplicateIdentifiers(JsonArray values, Class<?> elementType) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (JsonElement value : values) {
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject object = value.getAsJsonObject();
            String identifier = identifier(object, elementType);
            if (identifier != null && !seen.add(identifier)) {
                duplicates.add(identifier);
            }
        }
        return duplicates;
    }

    private static JsonElement mergeValue(JsonElement original, JsonElement current, Type type) {
        Class<?> raw = rawClass(type);
        if (original.isJsonObject() && current.isJsonObject() && isModelType(raw)) {
            return mergeObject(original.getAsJsonObject(), current.getAsJsonObject(), raw);
        }
        if (original.isJsonArray() && current.isJsonArray()) {
            return mergeArray(original.getAsJsonArray(), current.getAsJsonArray(), listElementClass(type));
        }
        return current.deepCopy();
    }

    private static Map<String, Type> fields(Class<?> type) {
        return FIELDS.computeIfAbsent(type, JsonCompatibility::findFields);
    }

    private static Map<String, Type> findFields(Class<?> type) {
        Map<String, Type> values = new HashMap<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (field.isSynthetic() || Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                SerializedName name = field.getAnnotation(SerializedName.class);
                if (name == null) {
                    values.putIfAbsent(field.getName(), field.getGenericType());
                    continue;
                }
                values.putIfAbsent(name.value(), field.getGenericType());
                for (String alternate : name.alternate()) {
                    values.putIfAbsent(alternate, field.getGenericType());
                }
            }
        }
        return Map.copyOf(values);
    }

    private static Class<?> listElementClass(Type type) {
        if (type instanceof ParameterizedType parameterized &&
                parameterized.getActualTypeArguments().length == 1) {
            return rawClass(parameterized.getActualTypeArguments()[0]);
        }
        return null;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }

    private static String identifier(JsonObject value, Class<?> elementType) {
        if (elementType == NotificationConfig.class) {
            return notificationIdentifier(value);
        }
        JsonElement id = value.get("id");
        if (id != null && id.isJsonPrimitive() && !id.getAsString().isBlank()) {
            return "id:" + id.getAsString();
        }
        return null;
    }

    /** Notification documents have no upstream ID, so use their unique public identity. */
    private static String notificationIdentifier(JsonObject value) {
        JsonElement type = value.get("notificationType");
        JsonElement comment = value.get("comment");
        JsonElement sort = value.get("sort");
        if (!isPrimitive(type) || !isPrimitive(comment) || !isPrimitive(sort)) {
            return null;
        }
        return "notification:" + type.getAsString() + '\u0000'
                + comment.getAsString() + '\u0000' + sort.getAsString();
    }

    private static boolean isPrimitive(JsonElement value) {
        return value != null && value.isJsonPrimitive() && !value.isJsonNull();
    }

    private static boolean isModelType(Class<?> type) {
        return type != null && !type.isPrimitive() && !type.isEnum() &&
                !type.getName().startsWith("java.") && !type.getName().startsWith("javax.") &&
                !type.getName().startsWith("jakarta.");
    }
}
