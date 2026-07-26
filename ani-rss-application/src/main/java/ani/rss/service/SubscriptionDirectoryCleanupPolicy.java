package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.ownership.DownloadOwnership;
import cn.hutool.core.util.StrUtil;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Determines the immutable download-root boundary for empty-directory cleanup.
 *
 * <p>Only the literal part before the first subscription-dependent template
 * segment is used as a boundary. This prevents a static custom save path from
 * authorizing removal of its parent directory merely because it is currently
 * empty.</p>
 */
final class SubscriptionDirectoryCleanupPolicy {
    private SubscriptionDirectoryCleanupPolicy() {
    }

    static Map<String, Path> resolveBoundaries(
            Map<String, Ani> subscriptions,
            Collection<DownloadOwnership> ownerships,
            Config config) {
        if (subscriptions == null || subscriptions.isEmpty() || ownerships == null ||
                ownerships.isEmpty() || config == null) {
            return Map.of();
        }

        Map<String, Path> boundaries = new LinkedHashMap<>();
        for (DownloadOwnership ownership : ownerships) {
            if (ownership == null || StrUtil.isBlank(ownership.ownershipId())) {
                continue;
            }
            Ani subscription = subscriptions.get(ownership.subscriptionId());
            resolveBoundary(subscription, ownership, config)
                    .ifPresent(boundary -> boundaries.put(ownership.ownershipId(), boundary));
        }
        return Map.copyOf(boundaries);
    }

    static Optional<Path> resolveBoundary(Ani subscription, DownloadOwnership ownership, Config config) {
        if (subscription == null || ownership == null || config == null ||
                StrUtil.isBlank(ownership.saveRoot())) {
            return Optional.empty();
        }
        try {
            String template = effectiveTemplate(subscription, config);
            Optional<Path> boundary = staticTemplateBoundary(template);
            if (boundary.isEmpty()) {
                return Optional.empty();
            }

            Path saveRoot = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
            Path normalizedBoundary = boundary.get().toAbsolutePath().normalize();
            if (saveRoot.equals(normalizedBoundary) || !saveRoot.startsWith(normalizedBoundary)) {
                return Optional.empty();
            }
            return Optional.of(normalizedBoundary);
        } catch (RuntimeException ignored) {
            // A malformed or stale template must not expand deletion scope.
            return Optional.empty();
        }
    }

    private static String effectiveTemplate(Ani subscription, Config config) {
        String template = config.getDownloadPathTemplate();
        if (Boolean.TRUE.equals(subscription.getOva()) &&
                StrUtil.isNotBlank(config.getOvaDownloadPathTemplate())) {
            template = config.getOvaDownloadPathTemplate();
        }
        if (Boolean.TRUE.equals(subscription.getCustomDownloadPath()) &&
                StrUtil.isNotBlank(subscription.getCustomDownloadPathTemplate())) {
            template = StrUtil.split(subscription.getCustomDownloadPathTemplate(), "\n", true, true)
                    .stream()
                    .map(FileUtils::getAbsolutePath)
                    .findFirst()
                    .orElse(template);
        }
        return template;
    }

    private static Optional<Path> staticTemplateBoundary(String template) {
        if (StrUtil.isBlank(template)) {
            return Optional.empty();
        }
        int variableIndex = template.indexOf("${");
        if (variableIndex < 0) {
            return Optional.empty();
        }
        int separator = Math.max(template.lastIndexOf('/', variableIndex),
                template.lastIndexOf('\\', variableIndex));
        if (separator < 0) {
            return Optional.empty();
        }
        String prefix = template.substring(0, separator + 1).trim();
        if (!isAbsolutePathPrefix(prefix)) {
            return Optional.empty();
        }
        return Optional.of(Path.of(FileUtils.getAbsolutePath(prefix)).toAbsolutePath().normalize());
    }

    private static boolean isAbsolutePathPrefix(String value) {
        return value.startsWith("/") || value.startsWith("\\\\") ||
                value.matches("(?i)^[a-z]:[\\\\/].*");
    }
}
