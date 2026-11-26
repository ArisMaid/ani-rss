package ani.rss.commons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DateAdapterTest {
    private final Gson gson = new GsonBuilder().registerTypeAdapter(Date.class, new DateAdapter()).create();

    @ParameterizedTest
    @CsvSource({
            "2026, 2026-01-01T00:00:00",
            "2026-11, 2026-11-01T00:00:00",
            "2026-11-27, 2026-11-27T00:00:00",
            "2026-11-27 12:34:56, 2026-11-27T12:34:56"
    })
    void readsBangumiDatesAndWritesOnlyTheDate(String input, String expected) {
        LocalDateTime dateTime = LocalDateTime.parse(expected);
        Date parsed = gson.fromJson(gson.toJson(input), Date.class);

        assertEquals(Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant()), parsed);
        assertEquals(gson.toJson(dateTime.toLocalDate().toString()), gson.toJson(parsed, Date.class));
    }

    @Test
    void handlesBlankAndNullDatesAndRejectsInvalidInput() {
        assertNull(gson.fromJson("null", Date.class));
        assertNull(gson.fromJson(gson.toJson("  "), Date.class));
        assertEquals("null", gson.toJson(null, Date.class));
        assertThrows(JsonParseException.class, () -> gson.fromJson(gson.toJson("invalid date"), Date.class));
        assertThrows(JsonParseException.class, () -> gson.fromJson(gson.toJson("2026-02-30"), Date.class));
    }
}
