package ani.rss.recovery;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import cn.hutool.core.util.StrUtil;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable, conservative identity for one selected RSS release. */
final class RecoveryItemIdentity {
    private static final Pattern RESOLUTION = Pattern.compile(
            "(?i)(?:^|[^0-9])(480|720|1080|1440|2160|4320)p(?:$|[^0-9])");
    private static final Pattern DIMENSIONS = Pattern.compile(
            "(?i)(1280x720|1920x1080|2560x1440|3840x2160|7680x4320)");
    private static final Pattern SIZE = Pattern.compile(
            "(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:kib|kb|mib|mb|gib|gb|tib|tb)\\b");
    private static final Pattern HASH = Pattern.compile("(?i)\\b[0-9a-f]{8,64}\\b");
    private static final Pattern BITRATE = Pattern.compile(
            "(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:k|m)?bps\\b");
    private static final Pattern PUBLICATION_TIME = Pattern.compile(
            "(?i)\\b(?:19|20)\\d{2}(?:[-_./]?\\d{2}){2}(?:[t _-]?\\d{2}(?:[:._-]?\\d{2}){1,2}z?)?\\b");
    private static final Pattern TECHNICAL_NOISE = Pattern.compile(
            "(?i)\\b(?:x26[45]|h\\.?26[45]|hevc|avc|av1|aac|flac|opus|eac3|ac3|"
                    + "10bit|8bit|hi10p|ma10p|yuv420p10|mkv|mp4|ts|torrent)\\b");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private RecoveryItemIdentity() {
    }

    static Value from(Ani ani, Item item) {
        String episode = item == null || item.getEpisode() == null
                ? "unknown"
                : BigDecimal.valueOf(item.getEpisode()).stripTrailingZeros().toPlainString();
        String season = ani == null || ani.getSeason() == null ? "unknown" : ani.getSeason().toString();
        String subgroup = normalize(item == null ? null : item.getSubgroup());
        String originalTitle = item == null ? null : StrUtil.blankToDefault(item.getTitle(), item.getReName());
        String resolution = resolution(originalTitle);
        String variant = variant(ani, item, originalTitle, subgroup);
        String output = normalizeOutput(item == null ? null : item.getReName());
        boolean named = StrUtil.isNotBlank(originalTitle);
        return new Value(season + ":" + episode,
                subgroup + "|" + resolution + "|" + variant, output, named);
    }

    private static String variant(Ani ani, Item item, String title, String subgroup) {
        if (StrUtil.isBlank(title)) {
            return "";
        }
        String value = Normalizer.normalize(title, Normalizer.Form.NFKC);
        value = SIZE.matcher(value).replaceAll(" ");
        value = HASH.matcher(value).replaceAll(" ");
        value = BITRATE.matcher(value).replaceAll(" ");
        value = PUBLICATION_TIME.matcher(value).replaceAll(" ");
        value = TECHNICAL_NOISE.matcher(value).replaceAll(" ");
        value = RESOLUTION.matcher(value).replaceAll(" ");
        value = DIMENSIONS.matcher(value).replaceAll(" ");
        value = normalize(value);
        value = removePhrase(value, subgroup);
        value = removePhrase(value, normalize(ani == null ? null : ani.getTitle()));
        value = WHITESPACE.matcher(value).replaceAll(" ").trim();
        if (value.isEmpty()) {
            value = normalize(item == null ? null : item.getReName());
        }
        return value;
    }

    private static String resolution(String value) {
        if (StrUtil.isBlank(value)) {
            return "none";
        }
        Matcher dimensions = DIMENSIONS.matcher(value);
        if (dimensions.find()) {
            return switch (dimensions.group(1).toLowerCase(Locale.ROOT)) {
                case "1280x720" -> "720p";
                case "1920x1080" -> "1080p";
                case "2560x1440" -> "1440p";
                case "3840x2160" -> "2160p";
                case "7680x4320" -> "4320p";
                default -> "none";
            };
        }
        Matcher resolution = RESOLUTION.matcher(value);
        return resolution.find() ? resolution.group(1).toLowerCase(Locale.ROOT) + "p" : "none";
    }

    private static String normalizeOutput(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        if (separator >= 0) {
            normalized = normalized.substring(separator + 1);
        }
        normalized = normalized.replaceFirst("(?i)\\.(?:mkv|mp4|ts)$", "");
        return normalize(normalized);
    }

    static String normalize(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}]+", " ");
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim();
    }

    private static String removePhrase(String value, String phrase) {
        if (value.isEmpty() || phrase.isEmpty()) {
            return value;
        }
        return WHITESPACE.matcher(value.replace(phrase, " ")).replaceAll(" ").trim();
    }

    record Value(String episodeKey, String fingerprint, String outputKey, boolean named) {
        String releaseKey() {
            return episodeKey + "\u0000" + fingerprint;
        }

        boolean sameRelease(Value other) {
            return named && other != null && other.named
                    && episodeKey.equals(other.episodeKey)
                    && fingerprint.equals(other.fingerprint);
        }

        boolean sameOutput(Value other) {
            return other != null && !outputKey.isEmpty() && outputKey.equals(other.outputKey);
        }
    }
}
