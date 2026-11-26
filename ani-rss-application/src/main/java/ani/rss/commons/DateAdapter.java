package ani.rss.commons;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.*;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.Date;
import java.util.Objects;

public class DateAdapter implements JsonDeserializer<Date>, JsonSerializer<Date> {
    @Override
    public JsonElement serialize(Date src, Type typeOfSrc, JsonSerializationContext context) {
        return src == null ? JsonNull.INSTANCE : new JsonPrimitive(DateUtil.formatDate(src));
    }

    @Override
    public Date deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (Objects.isNull(json) || json.isJsonNull()) {
            return null;
        }
        String value = json.getAsString();
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            if (ReUtil.isMatch("^(19|20)\\d{2}$", value)) {
                return DateTime.of(value, "yyyy");
            }
            if (ReUtil.isMatch("^(19|20)\\d{2}-(0[1-9]|1[0-2])$", value)) {
                return DateTime.of(value, "yyyy-MM");
            }
            // Hutool 会宽松修正越界日期，先校验 Bangumi 日期和时间戳的日期部分。
            if (ReUtil.isMatch("^\\d{4}-\\d{2}-\\d{2}(?:[T ].*)?$", value)) {
                LocalDate.parse(value.substring(0, 10));
            }
            return DateUtil.parse(value);
        } catch (RuntimeException e) {
            throw new JsonParseException(e);
        }
    }
}
