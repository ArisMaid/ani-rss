package ani.rss.util.basic;

import ani.rss.commons.CacheUtils;
import ani.rss.commons.MavenUtils;
import ani.rss.entity.Config;
import ani.rss.entity.web.Header;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpConnection;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.cookie.GlobalCookieManager;
import lombok.extern.slf4j.Slf4j;

import java.net.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HttpReq {


    public static final CookieManager COOKIE_MANAGER;

    static {
        COOKIE_MANAGER = new CookieManager();
        COOKIE_MANAGER.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
    }

    private static void config(HttpRequest req) {
        GlobalCookieManager.setCookieManager(COOKIE_MANAGER);

        req.timeout(1000 * 20)
                .setFollowRedirects(true);

        String ua = "ani-rss/{} (https://github.com/wushuo894/ani-rss)";
        ua = StrUtil.format(ua, MavenUtils.getVersion());

        req.header(Header.USER_AGENT, ua);
    }

    public static HttpRequest post(String url) {
        return post(url, ConfigUtil.CONFIG);
    }

    public static HttpRequest post(String url, Config config) {
        HttpRequest req = HttpRequestPlus.post(url);
        config(req);
        setProxy(req, config);
        return req;
    }

    public static HttpRequest get(String url) {
        return get(url, ConfigUtil.CONFIG);
    }

    public static HttpRequest get(String url, Config config) {
        HttpRequest req = HttpRequestPlus.get(url);
        config(req);
        setProxy(req, config);
        return req;
    }

    public static HttpRequest put(String url) {
        HttpRequest req = HttpRequestPlus.put(url);
        config(req);
        setProxy(req);
        return req;
    }

    public static HttpRequest delete(String url) {
        HttpRequest req = HttpRequestPlus.delete(url);
        config(req);
        setProxy(req);
        return req;
    }

    /**
     * 设置代理
     *
     * @param req HttpRequest
     */
    public static void setProxy(HttpRequest req) {
        setProxy(req, ConfigUtil.CONFIG);
    }

    /**
     * 设置代理
     *
     * @param req    HttpRequest
     * @param config 设置
     */
    public static void setProxy(HttpRequest req, Config config) {
        String url = req.getUrl();
        String safeUrl = sanitizeUrl(url);
        Boolean proxy = config.getProxy();
        if (!Boolean.TRUE.equals(proxy)) {
            log.debug("代理未开启 {}", safeUrl);
            return;
        }

        if (!isProxy(url, config)) {
            // 不进行代理
            return;
        }

        String proxyHost = config.getProxyHost();
        Integer proxyPort = config.getProxyPort();
        if (StrUtil.isBlank(proxyHost) || Objects.isNull(proxyPort)) {
            log.debug("代理参数不全 {}", safeUrl);
            return;
        }

        String proxyUsername = config.getProxyUsername();
        String proxyPassword = config.getProxyPassword();
        try {
            req.setHttpProxy(proxyHost, proxyPort);
            if (StrUtil.isAllNotBlank(proxyUsername, proxyPassword)) {
                req.basicProxyAuth(proxyUsername, proxyPassword);
            }
            log.debug("使用代理 {}", safeUrl);
        } catch (Exception e) {
            log.error("设置代理失败 {} type:{}", safeUrl, e.getClass().getSimpleName());
        }
    }

    public static String getUrl(HttpResponse response) {
        URL url = ((HttpConnection) ReflectUtil.getFieldValue(response, "httpConnection")).getUrl();
        return url.toString();
    }

    public static void assertStatus(HttpResponse response) {
        boolean ok = response.isOk();
        int status = response.getStatus();
        String url = sanitizeUrl(getUrl(response));
        Assert.isTrue(ok, "url: {}, status: {}", url, status);
    }

    public static void assertXml(HttpResponse response) {
        String url = sanitizeUrl(getUrl(response));
        String contentType = response.header(Header.CONTENT_TYPE);
        Assert.notBlank(contentType, "ContentType 为空, {}", url);

        boolean isXML = contentType.startsWith("application/xml") ||
                contentType.startsWith("application/rss+xml") ||
                contentType.startsWith("text/xml");

        Assert.isTrue(isXML, "非 XML 链接, {} {}", url, contentType);
    }

    /**
     * 是否代理
     *
     * @param url 链接
     * @return 是否使用代理
     */
    public static Boolean isProxy(String url) {
        return isProxy(url, ConfigUtil.CONFIG);
    }

    public static Boolean isProxy(String url, Config config) {
        String host = URLUtil.url(url).getHost();
        String proxyList = config.getProxyList();

        if (StrUtil.isBlank(host) || StrUtil.isBlank(proxyList)) {
            return false;
        }

        String key = StrFormatter.format("proxyList:{}", SecureUtil.md5(proxyList));

        List<String> split = CacheUtils.get(key);

        if (Objects.isNull(split)) {
            split = StrUtil.split(proxyList, "\n", true, true);
            CacheUtils.put(key, split, TimeUnit.MINUTES.toMillis(10));
        }

        if (split.isEmpty()) {
            return false;
        }

        if (split.contains(host)) {
            return true;
        }

        for (String s : split) {
            if (host.endsWith("." + s)) {
                return true;
            }
        }
        return false;
    }

    public static String sanitizeUrl(String value) {
        try {
            URI uri = URI.create(value);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null).toString();
        } catch (Exception ignored) {
            return "<invalid-url>";
        }
    }

}
