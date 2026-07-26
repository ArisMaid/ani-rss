package ani.rss.commons;

import java.util.Locale;

public record ByteRange(long start, long end, long resourceLength) {
    public ByteRange {
        if (resourceLength < 0 || start < 0 || end < start || end >= resourceLength) {
            throw new IllegalArgumentException("invalid byte range");
        }
    }

    public long length() {
        return end - start + 1;
    }

    public String contentRange() {
        return "bytes " + start + "-" + end + "/" + resourceLength;
    }

    public static ByteRange parseSingle(String header, long resourceLength) {
        if (resourceLength <= 0) {
            throw new UnsatisfiedRangeException(resourceLength);
        }
        if (header == null || !header.toLowerCase(Locale.ROOT).startsWith("bytes=")) {
            throw new MalformedRangeException();
        }
        String value = header.substring(header.indexOf('=') + 1).trim();
        if (value.isEmpty() || value.contains(",")) {
            throw new MalformedRangeException();
        }
        int delimiter = value.indexOf('-');
        if (delimiter < 0 || value.indexOf('-', delimiter + 1) >= 0) {
            throw new MalformedRangeException();
        }

        String startPart = value.substring(0, delimiter).trim();
        String endPart = value.substring(delimiter + 1).trim();
        try {
            if (startPart.isEmpty()) {
                long suffixLength = Long.parseLong(endPart);
                if (suffixLength <= 0) {
                    throw new MalformedRangeException();
                }
                long start = Math.max(0, resourceLength - suffixLength);
                return new ByteRange(start, resourceLength - 1, resourceLength);
            }

            long start = Long.parseLong(startPart);
            if (start < 0 || start >= resourceLength) {
                throw new UnsatisfiedRangeException(resourceLength);
            }
            long end = endPart.isEmpty() ? resourceLength - 1 : Long.parseLong(endPart);
            if (end < start) {
                throw new UnsatisfiedRangeException(resourceLength);
            }
            end = Math.min(end, resourceLength - 1);
            return new ByteRange(start, end, resourceLength);
        } catch (NumberFormatException e) {
            throw new MalformedRangeException();
        }
    }

    public static final class MalformedRangeException extends IllegalArgumentException {
    }

    public static final class UnsatisfiedRangeException extends IllegalArgumentException {
        private final long resourceLength;

        public UnsatisfiedRangeException(long resourceLength) {
            this.resourceLength = resourceLength;
        }

        public long resourceLength() {
            return resourceLength;
        }
    }
}
