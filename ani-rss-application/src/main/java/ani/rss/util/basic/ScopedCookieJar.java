package ani.rss.util.basic;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Cookie storage owned by one remote client instance. Hutool's built-in cookie
 * manager is JVM-global, so it cannot safely be used by active and temporary
 * downloader clients at the same time.
 */
public final class ScopedCookieJar {
    private final CookieManager manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    public <T> T execute(HttpRequest request, Function<HttpResponse, T> handler) {
        URI uri = URI.create(request.getUrl());
        apply(request, uri);
        try (HttpResponse response = request.execute()) {
            store(uri, response.headers());
            return handler.apply(response);
        }
    }

    private synchronized void apply(HttpRequest request, URI uri) {
        try {
            List<String> cookies = manager.get(uri, Map.of()).entrySet().stream()
                    .filter(entry -> "Cookie".equalsIgnoreCase(entry.getKey()))
                    .flatMap(entry -> entry.getValue().stream())
                    .toList();
            if (cookies.isEmpty()) {
                request.disableCookie();
            } else {
                request.cookie(String.join("; ", cookies));
            }
        } catch (IOException e) {
            throw new IllegalStateException("load scoped cookies failed", e);
        }
    }

    private synchronized void store(URI uri, Map<String, List<String>> headers) {
        try {
            manager.put(uri, headers);
        } catch (IOException e) {
            throw new IllegalStateException("store scoped cookies failed", e);
        }
    }
}
