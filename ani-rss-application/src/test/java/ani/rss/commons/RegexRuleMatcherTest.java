package ani.rss.commons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegexRuleMatcherTest {

    @Test
    void invalidRuleIsIgnoredForExcludeAndMatchFiltering() {
        assertFalse(RegexRuleMatcher.matches("??", "episode 02", "test-exclude"));
        assertFalse(RegexRuleMatcher.doesNotMatch("??", "episode 02", "test-match"));
    }

    @Test
    void validRuleRetainsExpectedMatchingSemantics() {
        assertTrue(RegexRuleMatcher.matches("S01E02", "Anime S01E02", "test-exclude"));
        assertTrue(RegexRuleMatcher.doesNotMatch("1080p", "Anime 720p", "test-match"));
        assertFalse(RegexRuleMatcher.doesNotMatch("720p", "Anime 720p", "test-match"));
    }
}
