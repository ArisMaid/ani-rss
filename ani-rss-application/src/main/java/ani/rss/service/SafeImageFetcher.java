package ani.rss.service;

import ani.rss.entity.Config;
import ani.rss.util.basic.CidrRangeChecker;
import cn.hutool.core.util.StrUtil;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** HTTP image fetcher with bounded redirects, bytes and DNS address checks. */
public final class SafeImageFetcher {
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final int MAX_REDIRECTS = 5;
    public static final int MAX_DIMENSION = 16_384;
    public static final long MAX_PIXELS = 100_000_000L;
    private static final Timeout TIMEOUT = Timeout.ofSeconds(10);
    private static final String IMAGE_PRIVATE_ALLOWLIST = "ANI_RSS_IMAGE_PRIVATE_ALLOWLIST";

    private SafeImageFetcher() {
    }

    public static FetchedImage fetch(String value, Config config) {
        URI current = parse(value);
        try (CloseableHttpClient client = createClient(config)) {
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                validateAddress(current, config);
                HttpGet request = new HttpGet(current);
                request.setHeader("Accept", "image/*");
                request.setHeader("User-Agent", "ani-rss-image-fetcher");
                try (CloseableHttpResponse response = client.execute(request)) {
                    int status = response.getCode();
                    if (status >= 300 && status < 400) {
                        String location = response.getFirstHeader("Location") == null
                                ? null : response.getFirstHeader("Location").getValue();
                        if (StrUtil.isBlank(location)) {
                            throw new IllegalStateException("image redirect has no location");
                        }
                        current = current.resolve(parse(location));
                        continue;
                    }
                    if (status < 200 || status >= 300) {
                        throw new IllegalStateException("image fetch returned HTTP " + status);
                    }
                    String contentType = response.getFirstHeader("Content-Type") == null
                            ? "" : response.getFirstHeader("Content-Type").getValue()
                            .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                    if (!contentType.startsWith("image/") || "image/svg+xml".equals(contentType)) {
                        throw new IllegalStateException("response is not a safe raster image");
                    }
                    HttpEntity entity = response.getEntity();
                    if (entity == null) {
                        throw new IllegalStateException("image response has no body");
                    }
                    long declared = entity.getContentLength();
                    if (declared > MAX_BYTES) {
                        throw new IllegalStateException("image exceeds 10 MiB");
                    }
                    byte[] bytes = readLimited(entity.getContent());
                    String detectedType;
                    try {
                        detectedType = validateRaster(bytes);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                    if (!detectedType.equals(normalizeContentType(contentType))) {
                        throw new IllegalStateException("image content type does not match response body");
                    }
                    return new FetchedImage(bytes, detectedType);
                }
            }
        } catch (LinkageError e) {
            throw new IllegalStateException("image HTTP client is incompatible with this Java runtime", e);
        } catch (IOException e) {
            throw new IllegalStateException("image fetch failed", e);
        }
        throw new IllegalStateException("too many image redirects");
    }

    private static CloseableHttpClient createClient(Config config) {
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return resolveForClient(host, config);
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                resolveForClient(host, config);
                return normalizeHost(host);
            }
        };
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(TIMEOUT)
                .setSocketTimeout(TIMEOUT)
                .build();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(TIMEOUT)
                .setResponseTimeout(TIMEOUT)
                .build();
        var builder = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .disableAutomaticRetries();

        if (Boolean.TRUE.equals(config == null ? null : config.getProxy())) {
            String proxyHost = normalizeHost(config.getProxyHost());
            Integer proxyPort = config.getProxyPort();
            if (proxyHost.isEmpty() || proxyPort == null || proxyPort < 1 || proxyPort > 65535) {
                throw new IllegalStateException("proxy configuration is incomplete");
            }
            HttpHost proxy = new HttpHost(proxyHost, proxyPort);
            builder.setRoutePlanner(new ConfiguredProxyRoutePlanner(proxy, config));
            if (StrUtil.isAllNotBlank(config.getProxyUsername(), config.getProxyPassword())) {
                BasicCredentialsProvider credentials = new BasicCredentialsProvider();
                credentials.setCredentials(new AuthScope(proxy), new UsernamePasswordCredentials(
                        config.getProxyUsername(), config.getProxyPassword().toCharArray()));
                builder.setDefaultCredentialsProvider(credentials);
            }
        }
        return builder.build();
    }

    private static InetAddress[] resolveForClient(String host, Config config) throws UnknownHostException {
        if (isConfiguredProxyHost(host, config)) {
            return InetAddress.getAllByName(normalizeHost(host));
        }
        return resolveAndValidate(host, config);
    }

    private static boolean isConfiguredProxyHost(String host, Config config) {
        return config != null && StrUtil.isNotBlank(config.getProxyHost()) &&
                normalizeHost(host).equals(normalizeHost(config.getProxyHost()));
    }

    private static final class ConfiguredProxyRoutePlanner extends DefaultProxyRoutePlanner {
        private final Config config;

        private ConfiguredProxyRoutePlanner(HttpHost proxy, Config config) {
            super(proxy);
            this.config = config;
        }

        @Override
        protected HttpHost determineProxy(HttpHost target, HttpContext context) throws HttpException {
            return target != null && shouldUseConfiguredProxy(target.getHostName(), config)
                    ? super.determineProxy(target, context) : null;
        }
    }

    private static boolean shouldUseConfiguredProxy(String host, Config config) {
        return config != null && Boolean.TRUE.equals(config.getProxy()) &&
                isProxyListed(normalizeHost(host), config);
    }

    public static boolean isForbiddenAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                address.isLinkLocalAddress() || address.isSiteLocalAddress() ||
                address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int value = ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16) |
                    ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
            return (value & 0xff000000) == 0 ||
                    (value & 0xffc00000) == 0x64400000 || // 100.64/10
                    (value & 0xffffff00) == 0xc0000000 || // 192.0.0/24
                    (value & 0xffffff00) == 0xc0000200 || // TEST-NET-1
                    (value & 0xfffe0000) == 0xc6120000 || // 198.18/15
                    (value & 0xffffff00) == 0xc6336400 || // TEST-NET-2
                    (value & 0xffffff00) == 0xcb007100 || // TEST-NET-3
                    (value & 0xffffff00) == 0xc0586300 || // deprecated 6to4 relay
                    (value & 0xf0000000) == 0xe0000000 ||
                    (value & 0xf0000000) == 0xf0000000;
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if ((first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80)) {
                return true;
            }
            if (bytes.length == 16 && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0 &&
                    bytes[3] == 0 && bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0 &&
                    bytes[7] == 0 && bytes[8] == 0 && bytes[9] == 0 && bytes[10] == (byte) 0xff &&
                    bytes[11] == (byte) 0xff) {
                try {
                    return isForbiddenAddress(InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)));
                } catch (IOException ignored) {
                    return true;
                }
            }
            return hasPrefix(bytes, new int[]{0, 0, 0, 0}, 96) || // IPv4-compatible
                    hasPrefix(bytes, new int[]{0x00, 0x64, 0xff, 0x9b}, 96) || // NAT64
                    hasPrefix(bytes, new int[]{0x00, 0x64, 0xff, 0x9b, 0x00, 0x01}, 48) || // local-use NAT64
                    hasPrefix(bytes, new int[]{0x01, 0x00}, 64) || // discard-only
                    hasPrefix(bytes, new int[]{0x20, 0x01, 0x00, 0x00}, 32) || // Teredo
                    hasPrefix(bytes, new int[]{0x20, 0x01, 0x00, 0x10}, 28) || // ORCHID
                    hasPrefix(bytes, new int[]{0x20, 0x01, 0x00, 0x20}, 28) || // ORCHIDv2
                    hasPrefix(bytes, new int[]{0x20, 0x01, 0x00, 0x02}, 48) || // benchmarking
                    hasPrefix(bytes, new int[]{0x20, 0x01, 0x0d, 0xb8}, 32) || // documentation
                    hasPrefix(bytes, new int[]{0x20, 0x02}, 16) || // 6to4
                    hasPrefix(bytes, new int[]{0x3f, 0xff}, 20) || // documentation
                    hasPrefix(bytes, new int[]{0x5f, 0x00}, 16); // segment-routing SIDs
        }
        return false;
    }

    private static void validateAddress(URI uri, Config config) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("only HTTP and HTTPS image URLs are allowed");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null || StrUtil.isBlank(uri.getHost())) {
            throw new IllegalArgumentException("image URL contains unsafe components");
        }
        if (uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("image URL port is invalid");
        }
        String host = normalizeHost(uri.getHost());
        try {
            resolveAndValidate(host, config);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("image host cannot be resolved", e);
        }
    }

    private static InetAddress[] resolveAndValidate(String host, Config config) throws UnknownHostException {
        String normalizedHost = normalizeHost(host);
        InetAddress[] addresses = InetAddress.getAllByName(normalizedHost);
        validateResolvedAddresses(normalizedHost, addresses, config);
        return addresses;
    }

    static void validateResolvedAddresses(String host, InetAddress[] addresses, Config config) {
        String normalizedHost = normalizeHost(host);
        Set<String> allowlist = allowlist(config);
        boolean explicitlyAllowed = allowlist.contains(normalizedHost);
        boolean domainName = !isIpLiteral(normalizedHost);
        // In transparent/fake-IP proxy environments DNS returns 198.18/15 for
        // configured upstream domains. The exception is deliberately limited to
        // the application's proxy-domain list; arbitrary domains must remain
        // blocked even when a system proxy happens to use fake IPs.
        boolean proxyListed = isProxyListed(normalizedHost, config);
        for (InetAddress address : addresses) {
            boolean proxyFakeIp = domainName && proxyListed && isProxyFakeIp(address);
            if (isForbiddenAddress(address) && !proxyFakeIp &&
                    !explicitlyAllowed && !allowedByCidr(address, allowlist)) {
                throw new IllegalArgumentException("image URL resolves to a private or reserved address");
            }
        }
    }

    private static boolean isProxyFakeIp(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int value = ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16) |
                ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
        return (value & 0xfffe0000) == 0xc6120000; // 198.18.0.0/15 proxy fake-IP range
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        if (host.isEmpty() || !host.chars().allMatch(ch -> ch == '.' || ch >= '0' && ch <= '9')) {
            return false;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return true;
        }
        for (String part : parts) {
            try {
                if (part.isEmpty() || Integer.parseInt(part) > 255) {
                    return true;
                }
            } catch (NumberFormatException e) {
                return true;
            }
        }
        return true;
    }

    private static boolean isProxyListed(String host, Config config) {
        if (config == null || StrUtil.isBlank(config.getProxyList())) {
            return false;
        }
        for (String entry : config.getProxyList().split("[,\\r\\n]")) {
            String candidate = normalizeHost(entry);
            if (candidate.isEmpty()) {
                continue;
            }
            if (host.equals(candidate) || host.endsWith("." + candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> allowlist(Config config) {
        String configured = System.getProperty(IMAGE_PRIVATE_ALLOWLIST);
        if (StrUtil.isBlank(configured)) {
            configured = System.getenv(IMAGE_PRIVATE_ALLOWLIST);
        }
        if (StrUtil.isBlank(configured)) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (String value : configured.split("[,\\n]")) {
            if (StrUtil.isNotBlank(value)) {
                result.add(normalizeHost(value.trim()));
            }
        }
        return result;
    }

    private static boolean allowedByCidr(InetAddress address, Set<String> allowlist) {
        if (address instanceof Inet4Address) {
            String ip = address.getHostAddress();
            return allowlist.stream().anyMatch(cidr -> CidrRangeChecker.isIpInRange(ip, cidr));
        }
        return allowlist.stream().anyMatch(cidr -> isIpv6InRange(address, cidr));
    }

    private static boolean isIpv6InRange(InetAddress address, String cidr) {
        int separator = cidr.lastIndexOf('/');
        if (!(address instanceof Inet6Address) || separator <= 0 || !cidr.contains(":")) {
            return false;
        }
        try {
            int prefixLength = Integer.parseInt(cidr.substring(separator + 1));
            if (prefixLength < 0 || prefixLength > 128) {
                return false;
            }
            InetAddress network = InetAddress.getByName(cidr.substring(0, separator));
            return network instanceof Inet6Address &&
                    hasPrefix(address.getAddress(), toUnsigned(network.getAddress()), prefixLength);
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    private static String normalizeHost(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1);
        }
        while (value.length() > 1 && value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static int[] toUnsigned(byte[] bytes) {
        int[] result = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            result[i] = bytes[i] & 0xff;
        }
        return result;
    }

    private static boolean hasPrefix(byte[] address, int[] prefix, int prefixLength) {
        if (prefixLength > address.length * 8) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;
        for (int i = 0; i < fullBytes; i++) {
            int expected = i < prefix.length ? prefix[i] : 0;
            if ((address[i] & 0xff) != expected) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        int expected = fullBytes < prefix.length ? prefix[fullBytes] : 0;
        return ((address[fullBytes] & 0xff) & mask) == (expected & mask);
    }

    private static URI parse(String value) {
        if (StrUtil.isBlank(value)) {
            throw new IllegalArgumentException("image URL is required");
        }
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid image URL", e);
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > MAX_BYTES) {
                    throw new IllegalStateException("image exceeds 10 MiB");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String normalizeContentType(String contentType) {
        return switch (contentType) {
            case "image/jpg", "image/pjpeg" -> "image/jpeg";
            case "image/x-png" -> "image/png";
            case "image/x-ms-bmp" -> "image/bmp";
            default -> contentType;
        };
    }

    private static String detectContentType(byte[] bytes) {
        if (startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) {
            return "image/png";
        }
        if (startsWith(bytes, 0xff, 0xd8, 0xff)) {
            return "image/jpeg";
        }
        if (startsWith(bytes, 'G', 'I', 'F', '8', '7', 'a') ||
                startsWith(bytes, 'G', 'I', 'F', '8', '9', 'a')) {
            return "image/gif";
        }
        if (startsWith(bytes, 'R', 'I', 'F', 'F') && bytes.length >= 12 &&
                startsWithAt(bytes, 8, 'W', 'E', 'B', 'P')) {
            return "image/webp";
        }
        if (startsWith(bytes, 'B', 'M')) {
            return "image/bmp";
        }
        return null;
    }

    public static String validateRaster(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("image is empty");
        }
        String detectedType = detectContentType(bytes);
        if (detectedType == null) {
            throw new IllegalArgumentException("image is not a supported raster format");
        }
        try {
            validateImageStructure(bytes, detectedType);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return detectedType;
    }

    private static void validateImageStructure(byte[] bytes, String contentType) {
        long[] dimensions = switch (contentType) {
            case "image/png" -> pngDimensions(bytes);
            case "image/jpeg" -> jpegDimensions(bytes);
            case "image/gif" -> gifDimensions(bytes);
            case "image/webp" -> webpDimensions(bytes);
            case "image/bmp" -> bmpDimensions(bytes);
            default -> throw new IllegalStateException("unsupported raster image format");
        };
        long width = dimensions[0];
        long height = dimensions[1];
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION ||
                width > MAX_PIXELS / height) {
            throw new IllegalStateException("image dimensions exceed safety limits");
        }
    }

    private static long[] pngDimensions(byte[] bytes) {
        if (bytes.length < 24 || readInt32BigEndian(bytes, 8) != 13 ||
                !startsWithAt(bytes, 12, 'I', 'H', 'D', 'R')) {
            throw new IllegalStateException("PNG header is invalid");
        }
        return new long[]{readUnsignedInt32BigEndian(bytes, 16), readUnsignedInt32BigEndian(bytes, 20)};
    }

    private static long[] gifDimensions(byte[] bytes) {
        if (bytes.length < 10) {
            throw new IllegalStateException("GIF header is truncated");
        }
        return new long[]{readUnsignedInt16LittleEndian(bytes, 6), readUnsignedInt16LittleEndian(bytes, 8)};
    }

    private static long[] bmpDimensions(byte[] bytes) {
        if (bytes.length < 26) {
            throw new IllegalStateException("BMP header is truncated");
        }
        long dibSize = readUnsignedInt32LittleEndian(bytes, 14);
        if (dibSize == 12) {
            return new long[]{readUnsignedInt16LittleEndian(bytes, 18),
                    readUnsignedInt16LittleEndian(bytes, 20)};
        }
        long width = readSignedInt32LittleEndian(bytes, 18);
        long height = Math.abs((long) readSignedInt32LittleEndian(bytes, 22));
        return new long[]{width, height};
    }

    private static long[] webpDimensions(byte[] bytes) {
        if (bytes.length < 25) {
            throw new IllegalStateException("WebP header is truncated");
        }
        if (bytes.length >= 30 && startsWithAt(bytes, 12, 'V', 'P', '8', 'X')) {
            return new long[]{1 + readUnsignedInt24LittleEndian(bytes, 24),
                    1 + readUnsignedInt24LittleEndian(bytes, 27)};
        }
        if (startsWithAt(bytes, 12, 'V', 'P', '8', 'L') && (bytes[20] & 0xff) == 0x2f) {
            int b0 = bytes[21] & 0xff;
            int b1 = bytes[22] & 0xff;
            int b2 = bytes[23] & 0xff;
            int b3 = bytes[24] & 0xff;
            long width = 1L + b0 + ((long) (b1 & 0x3f) << 8);
            long height = 1L + (b1 >> 6) + ((long) b2 << 2) + ((long) (b3 & 0x0f) << 10);
            return new long[]{width, height};
        }
        if (bytes.length >= 30 && startsWithAt(bytes, 12, 'V', 'P', '8', ' ') &&
                startsWithAt(bytes, 23, 0x9d, 0x01, 0x2a)) {
            return new long[]{readUnsignedInt16LittleEndian(bytes, 26) & 0x3fff,
                    readUnsignedInt16LittleEndian(bytes, 28) & 0x3fff};
        }
        throw new IllegalStateException("WebP frame header is invalid");
    }

    private static long[] jpegDimensions(byte[] bytes) {
        int offset = 2;
        while (offset < bytes.length) {
            while (offset < bytes.length && (bytes[offset] & 0xff) == 0xff) {
                offset++;
            }
            if (offset >= bytes.length) {
                break;
            }
            int marker = bytes[offset++] & 0xff;
            if (marker == 0xd8 || marker == 0x01 || marker >= 0xd0 && marker <= 0xd9) {
                continue;
            }
            if (offset + 1 >= bytes.length) {
                break;
            }
            int segmentLength = readUnsignedInt16BigEndian(bytes, offset);
            if (segmentLength < 2 || offset + segmentLength > bytes.length) {
                break;
            }
            if (isStartOfFrame(marker)) {
                if (segmentLength < 7) {
                    break;
                }
                return new long[]{readUnsignedInt16BigEndian(bytes, offset + 5),
                        readUnsignedInt16BigEndian(bytes, offset + 3)};
            }
            offset += segmentLength;
        }
        throw new IllegalStateException("JPEG frame header is invalid");
    }

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xc0 && marker <= 0xcf &&
                marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
    }

    private static int readUnsignedInt16BigEndian(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static int readUnsignedInt16LittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static int readInt32BigEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16 |
                (bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xff;
    }

    private static long readUnsignedInt32BigEndian(byte[] bytes, int offset) {
        return readInt32BigEndian(bytes, offset) & 0xffff_ffffL;
    }

    private static long readUnsignedInt32LittleEndian(byte[] bytes, int offset) {
        return readSignedInt32LittleEndian(bytes, offset) & 0xffff_ffffL;
    }

    private static int readSignedInt32LittleEndian(byte[] bytes, int offset) {
        return bytes[offset] & 0xff | (bytes[offset + 1] & 0xff) << 8 |
                (bytes[offset + 2] & 0xff) << 16 | (bytes[offset + 3] & 0xff) << 24;
    }

    private static long readUnsignedInt24LittleEndian(byte[] bytes, int offset) {
        return bytes[offset] & 0xffL | (bytes[offset + 1] & 0xffL) << 8 |
                (bytes[offset + 2] & 0xffL) << 16;
    }

    private static boolean startsWith(byte[] bytes, int... signature) {
        return startsWithAt(bytes, 0, signature);
    }

    private static boolean startsWithAt(byte[] bytes, int offset, int... signature) {
        if (bytes.length - offset < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[offset + i] & 0xff) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    public record FetchedImage(byte[] bytes, String contentType) {
        public FetchedImage {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
