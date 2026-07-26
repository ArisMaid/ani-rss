package ani.rss.auth;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** Numeric client-address checks used by the optional private-network boundary. */
public final class ClientAddressPolicy {
    private ClientAddressPolicy() {
    }

    public static boolean isPrivate(String value) {
        try {
            if (value == null) {
                return false;
            }
            String numeric = value.trim();
            int zone = numeric.indexOf('%');
            if (zone >= 0) {
                numeric = numeric.substring(0, zone);
            }
            boolean ipv4Literal = numeric.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
            boolean ipv6Literal = numeric.contains(":") && numeric.matches("[0-9A-Fa-f:.]+");
            if (!ipv4Literal && !ipv6Literal) {
                return false;
            }
            InetAddress address = InetAddress.getByName(numeric);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                    address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                return true;
            }
            byte[] bytes = address.getAddress();
            if (address instanceof Inet4Address) {
                int first = bytes[0] & 0xff;
                int second = bytes[1] & 0xff;
                return first == 100 && second >= 64 && second <= 127;
            }
            if (address instanceof Inet6Address) {
                return ((bytes[0] & 0xfe) == 0xfc);
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }
}
