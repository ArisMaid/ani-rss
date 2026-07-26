package ani.rss.commons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ByteRangeTest {
    @Test
    void parsesClosedRangeInclusively() {
        ByteRange range = ByteRange.parseSingle("bytes=2-4", 13);
        assertEquals(2, range.start());
        assertEquals(4, range.end());
        assertEquals(3, range.length());
        assertEquals("bytes 2-4/13", range.contentRange());
    }

    @Test
    void parsesSingleByte() {
        assertEquals(1, ByteRange.parseSingle("bytes=0-0", 13).length());
    }

    @Test
    void parsesOpenAndSuffixRanges() {
        assertEquals(new ByteRange(5, 12, 13), ByteRange.parseSingle("bytes=5-", 13));
        assertEquals(new ByteRange(10, 12, 13), ByteRange.parseSingle("bytes=-3", 13));
    }

    @Test
    void rejectsInvalidAndMultipleRanges() {
        assertThrows(ByteRange.UnsatisfiedRangeException.class,
                () -> ByteRange.parseSingle("bytes=13-14", 13));
        assertThrows(ByteRange.MalformedRangeException.class,
                () -> ByteRange.parseSingle("bytes=0-1,3-4", 13));
        assertThrows(ByteRange.MalformedRangeException.class,
                () -> ByteRange.parseSingle("bytes=abc-def", 13));
    }
}
