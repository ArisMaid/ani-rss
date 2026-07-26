package ani.rss.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAddressPolicyTest {
    @Test
    void classifiesIpv4AndIpv6PrivateNetworks() {
        assertTrue(ClientAddressPolicy.isPrivate("127.0.0.1"));
        assertTrue(ClientAddressPolicy.isPrivate("10.1.2.3"));
        assertTrue(ClientAddressPolicy.isPrivate("172.16.1.2"));
        assertTrue(ClientAddressPolicy.isPrivate("192.168.1.2"));
        assertTrue(ClientAddressPolicy.isPrivate("100.64.1.2"));
        assertTrue(ClientAddressPolicy.isPrivate("fd00::1"));
        assertTrue(ClientAddressPolicy.isPrivate("fe80::1"));
        assertFalse(ClientAddressPolicy.isPrivate("8.8.8.8"));
        assertFalse(ClientAddressPolicy.isPrivate("2001:4860:4860::8888"));
        assertFalse(ClientAddressPolicy.isPrivate("not-an-address"));
    }
}
