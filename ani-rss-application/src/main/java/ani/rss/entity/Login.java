package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 登录
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
@Schema(description = "登录")
public class Login implements Serializable {
    /**
     * 用户名
     */
    @Schema(description = "用户名")
    private String username;
    /**
     * 密码
     */
    @Schema(description = "密码 (MD5摘要；本地强化校验信息保存在私有状态文件)")
    private String password;
    /**
     * ip
     */
    @Schema(description = "ip", hidden = true)
    private String ip;
    /**
     * key
     */
    @Schema(description = "key", hidden = true)
    private String key;

    @Schema(description = "当前登录凭据是否为默认值")
    private Boolean isDefault;

    public Login(String username, String password) {
        this.username = username;
        this.password = password;
    }
}
