package ani.rss.commons;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Evaluates user-configured regex rules without letting one invalid rule stop a workflow. */
@Slf4j
public final class RegexRuleMatcher {
    private static final Set<String> REPORTED_INVALID_RULES = ConcurrentHashMap.newKeySet();

    private RegexRuleMatcher() {
    }

    public static boolean matches(String rule, String value, String scope) {
        return evaluate(rule, value, scope).orElse(false);
    }

    public static boolean doesNotMatch(String rule, String value, String scope) {
        return evaluate(rule, value, scope).map(matched -> !matched).orElse(false);
    }

    private static Optional<Boolean> evaluate(String rule, String value, String scope) {
        if (rule == null || rule.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Pattern.compile(rule).matcher(value == null ? "" : value).find());
        } catch (PatternSyntaxException exception) {
            reportInvalidRule(rule, scope);
            return Optional.empty();
        }
    }

    private static void reportInvalidRule(String rule, String scope) {
        String key = scope + '\u0000' + rule;
        if (REPORTED_INVALID_RULES.add(key)) {
            log.warn("ignoring invalid regex rule scope:{} length:{}", scope, rule.length());
        }
    }
}
