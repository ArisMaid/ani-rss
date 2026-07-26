package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

@Data
@Accessors(chain = true)
@Schema(description = "全局变量")
public class Global implements Serializable {
    private static volatile List<String> args = List.of();

    public static void setArgs(String[] values) {
        args = values == null ? List.of() : List.of(values.clone());
    }

    public static List<String> args() {
        return args;
    }

    public static final ThreadLocal<HttpServletRequest> REQUEST = new ThreadLocal<>();
    public static final ThreadLocal<HttpServletResponse> RESPONSE = new ThreadLocal<>();
}
