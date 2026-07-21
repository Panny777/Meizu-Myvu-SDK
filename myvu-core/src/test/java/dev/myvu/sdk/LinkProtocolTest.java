package dev.myvu.sdk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import dev.myvu.sdk.util.Hex;
import dev.myvu.sdk.protocol.link.DeviceId;
import dev.myvu.sdk.protocol.link.DeviceInfo;
import dev.myvu.sdk.protocol.link.LinkCommands;
import dev.myvu.sdk.protocol.link.LinkMessage;
import dev.myvu.sdk.protocol.link.LinkProtocol;

import org.junit.Test;

public class LinkProtocolTest {

    @Test
    public void sppShortUuidIsDecodedLittleEndian() {
        // The captured payload 21 91 00 00 is 0x9121 (37153) little-endian,
        // which sits inside SecureRandom.nextInt(65535). Big-endian would give
        // 0x21910000 -- far out of range, which is how the endianness was
        // established in the first place.
        assertEquals("00009121-0000-1000-8000-00805f9b34fb",
                LinkProtocol.sppShortUuidToString(Hex.decode("21910000")));
    }

    @Test
    public void sppShortUuidPadsToFourHexDigits() {
        assertEquals("00000042-0000-1000-8000-00805f9b34fb",
                LinkProtocol.sppShortUuidToString(Hex.decode("42000000")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void sppShortUuidRejectsShortPayload() {
        LinkProtocol.sppShortUuidToString(new byte[] { 1, 2 });
    }

    @Test
    public void linkProtocolRoundTrips() {
        byte[] mac = Hex.decode("7ca375d094f1");
        byte[] payload = { 9, 8, 7 };

        LinkMessage m = LinkProtocol.parse(
                LinkProtocol.build(mac, LinkCommands.CMD_SPP_SERVER_UUID_SYNC, payload));

        assertArrayEquals(DeviceId.deal(mac), m.deviceId);
        assertEquals(LinkCommands.CMD_SPP_SERVER_UUID_SYNC, m.cmd);
        assertArrayEquals(payload, m.data);
    }

    @Test
    public void emptyDataFieldIsOmittedAndParsesBack() {
        byte[] mac = Hex.decode("7ca375d094f1");
        LinkMessage m = LinkProtocol.parse(LinkProtocol.build(mac, LinkCommands.CMD_INIT));
        assertEquals(LinkCommands.CMD_INIT, m.cmd);
        assertEquals(0, m.data.length);
    }

    @Test
    public void writeSwitchInfoOmitsZeroCode() {
        byte[] info = { 1, 2, 3 };
        // code == 0 is omitted entirely, matching Python; field 2 still parses.
        assertArrayEquals(info,
                LinkProtocol.parseWriteSwitchInfo(LinkProtocol.writeSwitchInfo(info, 0)));
        assertArrayEquals(info,
                LinkProtocol.parseWriteSwitchInfo(LinkProtocol.writeSwitchInfo(info, 5)));
    }

    @Test
    public void deviceInfoRoundTrips() {
        byte[] encoded = DeviceInfo.build(
                "AA:BB:CC:DD:EE:FF", "", "9999", "", "MyvuAndroid", 100, 0);
        DeviceInfo d = DeviceInfo.parse(encoded);

        assertEquals("AA:BB:CC:DD:EE:FF", d.btMac);
        assertEquals("9999", d.categoryId);
        assertEquals("MyvuAndroid", d.name);
        assertEquals(100, d.battery);
        assertEquals(0, d.btStatus);
    }

    @Test
    public void macHelpersAcceptCommonSeparators() {
        // Synthetic address: the helpers only care about separators and case.
        byte[] expected = Hex.decode("123456789abc");
        assertArrayEquals(expected, DeviceId.macToBytes("12:34:56:78:9A:BC"));
        assertArrayEquals(expected, DeviceId.macToBytes("12-34-56-78-9a-bc"));
        assertEquals("123456789abc", DeviceId.macToHex("12:34:56:78:9A:BC"));
    }
}
