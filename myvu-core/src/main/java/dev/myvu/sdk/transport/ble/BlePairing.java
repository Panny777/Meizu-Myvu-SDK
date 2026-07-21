package dev.myvu.sdk.transport.ble;

import android.os.Handler;

import dev.myvu.sdk.util.Hex;
import dev.myvu.sdk.util.SdkLog;
import dev.myvu.sdk.crypto.EcKeyPair;
import dev.myvu.sdk.crypto.StarryCrypto;
import dev.myvu.sdk.protocol.link.DeviceInfo;
import dev.myvu.sdk.protocol.link.LinkCommands;
import dev.myvu.sdk.protocol.link.LinkMessage;
import dev.myvu.sdk.protocol.link.LinkProtocol;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The version negotiation + ECDH bond on the BLE link/pairing characteristic.
 * Port of client.pair() / _negotiate_version / _exchange_keys / _send_device_info.
 *
 * Sequence:
 *   1. version negotiation  -- FAST_CTR pkgType 17, JSON {"i","v","e","m","b","c"};
 *                              the reply chooses the AES mode
 *   2. WRITE_SWITCH_KEY     -- SinglePacket pkgType 16, our SPKI public key
 *   3. <- WRITE_SWITCH_KEY  -- glasses' pubkey||IV + AES(their DeviceInfo)
 *   4. WRITE_SWITCH_INFO    -- our double-encrypted DeviceInfo; bond established
 *
 * There is no certificate or signature check anywhere in this handshake: any
 * client that speaks the protocol correctly is accepted.
 *
 * Python could await each step in a straight line; here each step continues from
 * the previous one's callback. All of it runs on the connection thread.
 */
public class BlePairing {

    // Values taken from the captured phone handshake.
    private static final int CONNECT_VERSION = 3;
    private static final int BLE_VERSION = 2;
    /** Encryption bitmask we advertise as supported ("e"). */
    private static final int OWN_ENCRYPT_SUPPORT = 5;

    public interface Callback {
        void onPaired(DeviceInfo glasses);
        void onFailed(String reason);
    }

    private final BleTransport transport;
    private final byte[] ownId;
    private final String ownMac;
    private final String deviceName;
    private final String categoryId;
    private final int btStatus;
    private final Callback callback;

    private EcKeyPair keyPair;
    private byte[] sharedSecret;
    private byte[] iv;
    private int encryptMode = StarryCrypto.SYMMETRIC_V3_GCM;

    private enum Step { IDLE, AWAIT_VERSION, AWAIT_KEY_REPLY, DONE }
    private Step step = Step.IDLE;

    /**
     * Bounds the whole bond. Each step waits on a reply that may simply never
     * arrive -- an unexpected LinkProtocol cmd, for instance, leaves us parked
     * in AWAIT_KEY_REPLY forever. Without this the connection sat in PAIRING
     * indefinitely with no error and no retry.
     */
    private static final long PAIRING_TIMEOUT_MS = 20000;

    private final Handler conn;
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            failed("timed out after " + (PAIRING_TIMEOUT_MS / 1000)
                    + "s waiting at step " + step);
        }
    };

    public BlePairing(BleTransport transport, Handler conn, byte[] ownId, String ownMac,
                      String deviceName, String categoryId, int btStatus, Callback callback) {
        this.transport = transport;
        this.conn = conn;
        this.ownId = ownId;
        this.ownMac = ownMac;
        this.deviceName = deviceName;
        this.categoryId = categoryId;
        this.btStatus = btStatus;
        this.callback = callback;
    }

    public byte[] sharedSecret() { return sharedSecret; }
    public byte[] iv() { return iv; }
    public int encryptMode() { return encryptMode; }

    // ------------------------------------------------------------- step 1

    public void start() {
        try {
            JSONObject own = new JSONObject()
                    .put("i", Hex.encode(ownId))
                    .put("v", CONNECT_VERSION)
                    .put("e", OWN_ENCRYPT_SUPPORT)
                    .put("m", 512)
                    .put("b", BLE_VERSION)
                    .put("c", categoryId);
            byte[] payload = own.toString().getBytes(StandardCharsets.UTF_8);

            step = Step.AWAIT_VERSION;
            conn.postDelayed(timeout, PAIRING_TIMEOUT_MS);
            SdkLog.log("-> version negotiation " + own);
            transport.internal().sendFast(payload, BlePackets.PKG_STARRY_DATA_INIT);
        } catch (Exception e) {
            failed("could not build the version payload: " + e);
        }
    }

    /** Feed every message from the internal characteristic here during pairing. */
    public boolean onInternalMessage(byte[] payload) {
        switch (step) {
            case AWAIT_VERSION:
                handleVersionReply(payload);
                return true;
            case AWAIT_KEY_REPLY:
                handleKeyReply(payload);
                return true;
            default:
                return false; // not ours; the caller routes it onward
        }
    }

    private void handleVersionReply(byte[] payload) {
        try {
            JSONObject peer = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            // The peer chooses the symmetric mode: 1=CBC, 2=CTR, else GCM.
            encryptMode = peer.optInt("e", StarryCrypto.SYMMETRIC_V3_GCM);
            SdkLog.log("<- version reply " + peer + " (AES mode " + modeName(encryptMode) + ")");
            exchangeKeys();
        } catch (Exception e) {
            failed("unparseable version reply: " + e);
        }
    }

    // ------------------------------------------------------------- step 2

    private void exchangeKeys() {
        try {
            keyPair = EcKeyPair.generate();
            byte[] wsk = LinkProtocol.writeSwitchKey(keyPair.publicSpkiDer(), ownId);
            byte[] msg = LinkProtocol.build(ownId, LinkCommands.CMD_WRITE_SWITCH_KEY, wsk);

            step = Step.AWAIT_KEY_REPLY;
            SdkLog.log("-> WRITE_SWITCH_KEY (" + msg.length + "B)");
            transport.internal().sendSingleAcked(msg, BlePackets.PKG_STARRY_DATA,
                    new BleMessageChannel.AckCallback() {
                        @Override
                        public void onAck(int status) {
                            if (status != BlePackets.ACK_SUCCESS) {
                                failed("key write was not acked (status=" + status + ")");
                            }
                        }
                    });
        } catch (Exception e) {
            failed("key generation failed: " + e);
        }
    }

    // ------------------------------------------------------------- step 3

    private void handleKeyReply(byte[] payload) {
        try {
            LinkMessage reply = LinkProtocol.parse(payload);
            if (reply.cmd != LinkCommands.CMD_WRITE_SWITCH_KEY) {
                // Not the reply we're waiting for; ignore and keep waiting.
                SdkLog.trace("ignoring LinkProtocol cmd=" + reply.cmd + " during pairing");
                return;
            }

            byte[][] parts = LinkProtocol.parseWriteSwitchKey(reply.data);
            byte[] keyField = parts[0];
            byte[] encryptedInfo = parts[1];
            if (keyField.length <= 16) {
                failed("key field too short (" + keyField.length + "B)");
                return;
            }

            // key field = peer SPKI public key || 16-byte IV
            byte[] peerPub = Arrays.copyOfRange(keyField, 0, keyField.length - 16);
            iv = Arrays.copyOfRange(keyField, keyField.length - 16, keyField.length);
            sharedSecret = keyPair.sharedSecret(peerPub);
            SdkLog.log("ECDH shared secret derived (" + sharedSecret.length + "B)");

            // Decrypting their DeviceInfo is the proof the whole stack is right.
            byte[] infoBytes = StarryCrypto.decrypt(encryptedInfo, sharedSecret, iv, encryptMode);
            DeviceInfo glasses = DeviceInfo.parse(infoBytes);
            SdkLog.log("<- Glasses: " + glasses);

            sendOwnDeviceInfo(glasses);
        } catch (Exception e) {
            failed("key exchange failed (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + "). A garbled DeviceInfo here usually means the "
                    + "negotiated AES mode or the SPKI encoding is wrong.");
        }
    }

    // ------------------------------------------------------------- step 4

    private void sendOwnDeviceInfo(final DeviceInfo glasses) {
        try {
            byte[] info = DeviceInfo.build(
                    ownMac.toUpperCase(), "", categoryId, "", deviceName, 100, btStatus);

            // Double encryption, per generateDeviceInfoSwitchData(): the inner
            // DeviceInfo is encrypted, wrapped in WriteSwitchInfo, then the whole
            // wrapper is encrypted again.
            byte[] inner = StarryCrypto.encrypt(info, sharedSecret, iv, encryptMode);
            byte[] wsi = LinkProtocol.writeSwitchInfo(inner, 0);
            byte[] outer = StarryCrypto.encrypt(wsi, sharedSecret, iv, encryptMode);
            byte[] msg = LinkProtocol.build(ownId, LinkCommands.CMD_WRITE_SWITCH_INFO, outer);

            SdkLog.log("-> WRITE_SWITCH_INFO (" + msg.length + "B)");
            step = Step.DONE;
            transport.internal().sendSingleAcked(msg, BlePackets.PKG_STARRY_DATA,
                    new BleMessageChannel.AckCallback() {
                        @Override
                        public void onAck(int status) {
                            if (status != BlePackets.ACK_SUCCESS) {
                                failed("info write was not acked (status=" + status + ")");
                                return;
                            }
                            conn.removeCallbacks(timeout);
                            SdkLog.log("BLE bond established");
                            callback.onPaired(glasses);
                        }
                    });
        } catch (Exception e) {
            failed("could not send our DeviceInfo: " + e);
        }
    }

    // ------------------------------------------------------------ helpers

    private void failed(String reason) {
        if (step == Step.DONE) return;
        step = Step.DONE;
        conn.removeCallbacks(timeout);
        callback.onFailed(reason);
    }

    /** Cancels the pending timeout when the connection is torn down externally. */
    public void cancel() {
        step = Step.DONE;
        conn.removeCallbacks(timeout);
    }

    private static String modeName(int mode) {
        switch (mode) {
            case StarryCrypto.SYMMETRIC_V1_CBC: return "CBC";
            case StarryCrypto.SYMMETRIC_V2_CTR: return "CTR";
            default: return "GCM";
        }
    }
}
