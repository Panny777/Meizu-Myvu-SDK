package dev.myvu.sdk;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import dev.myvu.sdk.app.AppLayer;
import dev.myvu.sdk.app.InboundRouter;
import dev.myvu.sdk.app.RelaySession;
import dev.myvu.sdk.app.feature.ClockSync;
import dev.myvu.sdk.app.feature.SystemSettings;
import dev.myvu.sdk.app.feature.Teleprompter;
import dev.myvu.sdk.app.feature.Trackpad;
import dev.myvu.sdk.connection.AudioProfiles;
import dev.myvu.sdk.connection.RelaySupervisor;
import dev.myvu.sdk.crypto.StarryCrypto;
import dev.myvu.sdk.event.GlassesEvent;
import dev.myvu.sdk.protocol.AbilityReply;
import dev.myvu.sdk.protocol.InitBurst;
import dev.myvu.sdk.protocol.MsgType;
import dev.myvu.sdk.protocol.Relay;
import dev.myvu.sdk.protocol.RelayMessage;
import dev.myvu.sdk.protocol.Session;
import dev.myvu.sdk.protocol.link.DeviceId;
import dev.myvu.sdk.protocol.link.DeviceInfo;
import dev.myvu.sdk.protocol.link.LinkCommands;
import dev.myvu.sdk.protocol.link.LinkMessage;
import dev.myvu.sdk.protocol.link.LinkProtocol;
import dev.myvu.sdk.transport.Transport;
import dev.myvu.sdk.transport.TransportListener;
import dev.myvu.sdk.transport.ble.BleMessageChannel;
import dev.myvu.sdk.transport.ble.BlePackets;
import dev.myvu.sdk.transport.ble.BlePairing;
import dev.myvu.sdk.transport.ble.BleTransport;
import dev.myvu.sdk.transport.ble.GlassesScanner;
import dev.myvu.sdk.transport.bt.RfcommTransport;
import dev.myvu.sdk.util.Hex;
import dev.myvu.sdk.util.SdkLog;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * The SDK's entry point: owns the connection to the glasses and every piece of
 * protocol state.
 *
 * THREADING: all protocol state lives on the single "myvu-conn" thread, so
 * nothing in protocol/ or app/ needs locking. Transports post inbound payloads
 * here; callers post outbound requests here. Listener callbacks are delivered
 * on the executor supplied to {@link #addListener} (main thread by default).
 *
 * TWO TRANSPORTS RUN AT ONCE, and the order is not a preference:
 *
 *  1. BLE first. The glasses' classic radio does not answer a page until BLE has
 *     brought them up -- a cold createBond just times out after ~13s with no
 *     ACL. BLE carries the ECDH bond and, crucially, is the ONLY place the
 *     app-relay's address is announced (CMD_SPP_SERVER_UUID_SYNC), because the
 *     glasses regenerate that UUID every session.
 *  2. RFCOMM second, to that per-session UUID. This is the link that actually
 *     carries app traffic; the fixed "channel 13" in early captures answered the
 *     handshake but never ACKed a single app message.
 *
 * Each transport runs its own independent RunAsOne session (see RelaySession).
 */
public class MyvuClient implements BleTransport.Listener, RelaySupervisor.Delegate {

    private final Context context;
    private final MyvuConfig config;
    private final HandlerThread connThread;
    private final Handler conn;
    private final InitBurstSource initBurstSource;

    private volatile ConnectionState state = ConnectionState.IDLE;
    private String targetMac;
    private BluetoothDevice device;
    private byte[] ownId;
    private String ownMac;
    private String sessionId;

    private BleTransport ble;
    private BlePairing pairing;
    /** BLE discovery for the "auto search" connect path; null unless searching. */
    private GlassesScanner scanner;
    private volatile DeviceInfo glassesInfo;
    /**
     * NOT final: a reconnect must start from a fresh sequencer. The glasses
     * track the last received msgId and discard anything that looks stale, so
     * reusing this across connections would make the second session's traffic
     * be silently dropped.
     */
    private RelaySession bleSession = new RelaySession();

    private RfcommTransport rfcomm;
    private RelaySession rfSession;
    /** Learned over BLE; the address of the real app-relay channel. */
    private String sppUuid;
    /** True from the moment we open the relay socket until its session is ready. */
    private boolean relayEstablishing;
    private static final long RELAY_ESTABLISH_TIMEOUT_MS = 15000;

    private RelaySupervisor supervisor;

    /**
     * Connects the standard HFP/A2DP audio profiles so the glasses light their
     * own "phone connected" indicator. Kept across auto-reconnects; closed only
     * on shutdown. See AudioProfiles for the permission caveats.
     */
    private AudioProfiles audioProfiles;
    /**
     * The ECDH material from the BLE bond, retained so we can push an updated
     * DeviceInfo (WRITE_SWITCH_INFO) when the audio profiles connect after the
     * bond -- the btStatus in the first DeviceInfo is only ACL-level, since the
     * profiles come up seconds later.
     */
    private byte[] bondKey;
    private byte[] bondIv;
    private int bondMode;
    /** Last btStatus we told the glasses; suppresses redundant re-sends. */
    private int lastSentBtStatus = LinkCommands.BTSTATUS_DEFAULT;

    /** Answers glasses-initiated requests (launch-app, time sync, AI triggers). */
    private final InboundRouter inbound = new InboundRouter(new InboundRouter.Sender() {
        @Override
        public void send(String actionJson, String targetPkg, String sourcePkg) {
            sendActionNow(actionJson, targetPkg, sourcePkg);
        }
    });

    // ---------------------------------------------------------- listeners

    /**
     * Connection lifecycle and glasses-initiated events. All methods have
     * empty defaults so implementers pick only what they need.
     */
    public interface Listener {
        default void onConnectionStateChanged(ConnectionState state) {}
        /** The paired glasses' identity (name, MAC, battery, btStatus). */
        default void onDeviceInfo(DeviceInfo info) {}
        /** Auto-search found glasses at this MAC; persist it to skip the scan next time. */
        default void onGlassesDiscovered(String mac) {}
        default void onEvent(GlassesEvent event) {}
        /** Raw body of every non-audio inbound relay message. */
        default void onRawInbound(String jsonBody) {}
    }

    /**
     * Receives the glasses' streamed microphone (code:109 Opus bodies).
     *
     * Called on the connection thread, dozens of times per second while the
     * glasses stream -- implementations must hand the frame off, never block.
     * The myvu-ai module installs this; without a listener frames are acked
     * and dropped.
     */
    public interface AudioFrameListener {
        void onAudioFrame(byte[] code109Body);
    }

    private static final class ListenerEntry {
        final Listener listener;
        final Executor executor;
        ListenerEntry(Listener l, Executor e) { listener = l; executor = e; }
    }

    private final CopyOnWriteArrayList<ListenerEntry> listeners = new CopyOnWriteArrayList<>();
    private volatile AudioFrameListener audioFrameListener;

    /** Adds a listener called on the main thread (or inline under JVM tests). */
    public void addListener(Listener listener) {
        addListener(listener, mainThreadExecutor());
    }

    public void addListener(Listener listener, Executor executor) {
        for (ListenerEntry e : listeners) {
            if (e.listener == listener) return;
        }
        listeners.add(new ListenerEntry(listener, executor));
    }

    public void removeListener(Listener listener) {
        for (ListenerEntry e : listeners) {
            if (e.listener == listener) listeners.remove(e);
        }
    }

    /** Installs the mic-frame sink (connection-thread callback); null clears it. */
    public void setAudioFrameListener(AudioFrameListener listener) {
        this.audioFrameListener = listener;
    }

    private static Executor mainThreadExecutor() {
        try {
            Looper looper = Looper.getMainLooper();
            if (looper != null) {
                final Handler main = new Handler(looper);
                return new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        main.post(command);
                    }
                };
            }
        } catch (Throwable ignored) {
            // Stubbed android.os under JVM unit tests.
        }
        return new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }

    private void dispatchState(final ConnectionState s) {
        for (final ListenerEntry e : listeners) {
            e.executor.execute(new Runnable() {
                @Override
                public void run() {
                    e.listener.onConnectionStateChanged(s);
                }
            });
        }
    }

    private void dispatchDeviceInfo(final DeviceInfo info) {
        for (final ListenerEntry e : listeners) {
            e.executor.execute(new Runnable() {
                @Override
                public void run() {
                    e.listener.onDeviceInfo(info);
                }
            });
        }
    }

    private void dispatchDiscovered(final String mac) {
        for (final ListenerEntry e : listeners) {
            e.executor.execute(new Runnable() {
                @Override
                public void run() {
                    e.listener.onGlassesDiscovered(mac);
                }
            });
        }
    }

    private void dispatchEvent(final GlassesEvent event) {
        for (final ListenerEntry e : listeners) {
            e.executor.execute(new Runnable() {
                @Override
                public void run() {
                    e.listener.onEvent(event);
                }
            });
        }
    }

    private void dispatchRaw(final String body) {
        for (final ListenerEntry e : listeners) {
            e.executor.execute(new Runnable() {
                @Override
                public void run() {
                    e.listener.onRawInbound(body);
                }
            });
        }
    }

    // -------------------------------------------------------- construction

    {
        // The glasses' AI button (code:3) and wake word (code:7) both land
        // here; the myvu-ai module (or the app) subscribes via onEvent. The
        // control:0 release semantics are documented on GlassesEvent.AiTrigger.
        inbound.setAiTriggerListener(new InboundRouter.AiTriggerListener() {
            @Override
            public void onAiTrigger(int code, org.json.JSONObject payload) {
                // The glasses' mic audio only flows over the app relay. With
                // the relay down (its retry budget spent), a press listened to
                // nothing and timed out with "0 packets in" -- so treat the
                // press like the glasses asking for the relay back. The
                // release (control:0) is not a moment audio is needed.
                boolean release = payload != null && payload.optInt("control", 1) == 0;
                if (!release && supervisor != null) supervisor.wake();
                dispatchEvent(new GlassesEvent.AiTrigger(code, payload));
            }
        });
    }

    public MyvuClient(Context context) {
        this(context, MyvuConfig.builder().build());
    }

    public MyvuClient(Context context, MyvuConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.initBurstSource = config.initBurstSource != null
                ? config.initBurstSource : bundledInitBurst();
        this.connThread = new HandlerThread("myvu-conn");
        this.connThread.start();
        this.conn = new Handler(connThread.getLooper());
    }

    /** The capture shipped inside the SDK's AAR. */
    private InitBurstSource bundledInitBurst() {
        return new InitBurstSource() {
            @Override
            public InputStream open() throws java.io.IOException {
                return context.getAssets().open(InitBurst.ASSET_NAME);
            }
        };
    }

    /** The connection thread; feature modules (nav, ai) schedule work on it. */
    public Handler connectionHandler() { return conn; }
    public ConnectionState state() { return state; }
    public DeviceInfo glassesInfo() { return glassesInfo; }
    public String sppUuid() { return sppUuid; }
    public InboundRouter inboundRouter() { return inbound; }

    // ------------------------------------------------------------- connect

    /** Connects to the glasses at the given MAC. Safe to call from any thread. */
    public void connect(final String mac) {
        conn.post(new Runnable() {
            @Override
            public void run() {
                // A second connect (repeat tap, service restart, redelivered
                // intent) must not stand up a parallel BLE stack against the
                // same glasses -- they accept one central at a time.
                if (state != ConnectionState.IDLE && state != ConnectionState.FAILED) {
                    SdkLog.trace("connect ignored: already " + state);
                    return;
                }
                userStopped = false;
                cancelReconnect();
                targetMac = mac;
                beginConnect();
            }
        });
    }

    /**
     * The "auto search" connect path: scan for a MYVU device over BLE, then
     * connect to whatever we find -- so the user doesn't have to know the MAC.
     * Falls back to a bonded MYVU device if none is advertising (they may be
     * bonded but asleep; the page attempt will wake or fail cleanly).
     */
    public void connectAutoSearch() {
        conn.post(new Runnable() {
            @Override
            public void run() {
                if (state != ConnectionState.IDLE && state != ConnectionState.FAILED) {
                    SdkLog.trace("auto-search ignored: already " + state);
                    return;
                }
                userStopped = false;
                cancelReconnect();
                beginAutoSearch();
            }
        });
    }

    private void beginAutoSearch() {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            failHard("no Bluetooth adapter on this device");
            return;
        }
        if (!adapter.isEnabled()) {
            failHard("Bluetooth is off -- turn it on and reconnect");
            return;
        }
        // Reuse CONNECTING: callers already read this as "searching for the
        // glasses", which is exactly what we're doing.
        setState(ConnectionState.CONNECTING);
        if (scanner == null) scanner = new GlassesScanner(adapter, conn);
        scanner.start(new GlassesScanner.Callback() {
            @Override
            public void onFound(final BluetoothDevice device, String name) {
                conn.post(new Runnable() {
                    @Override
                    public void run() {
                        connectTo(device.getAddress());
                    }
                });
            }

            @Override
            public void onTimeout() {
                conn.post(new Runnable() {
                    @Override
                    public void run() {
                        BluetoothDevice bonded = firstBondedGlasses(adapter);
                        if (bonded != null) {
                            SdkLog.log("no advertisement seen; trying the bonded "
                                    + "glasses " + bonded.getAddress());
                            connectTo(bonded.getAddress());
                        } else {
                            failHard("couldn't find your glasses -- make sure they're "
                                    + "on and nearby, then try again");
                        }
                    }
                });
            }

            @Override
            public void onError(final String reason) {
                conn.post(new Runnable() {
                    @Override
                    public void run() {
                        failHard("auto-search failed: " + reason);
                    }
                });
            }
        });
    }

    /** Adopt a discovered/bonded MAC and run the normal connect flow. */
    private void connectTo(String mac) {
        if (state != ConnectionState.CONNECTING) return; // cancelled meanwhile
        targetMac = mac;
        dispatchDiscovered(mac);
        beginConnect();
    }

    private BluetoothDevice firstBondedGlasses(BluetoothAdapter adapter) {
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String n = d.getName();
                if (n != null && n.toUpperCase(Locale.US).contains("MYVU")) return d;
            }
        } catch (SecurityException ignored) {
        }
        return null;
    }

    /** Tears the connection down; the client stays usable for a later connect. */
    public void disconnect() {
        conn.post(new Runnable() {
            @Override
            public void run() {
                userStopped = true;
                cancelReconnect();
                teardown();
                setState(ConnectionState.IDLE);
            }
        });
    }

    /** Final teardown; the instance is dead afterwards (its thread has quit). */
    public void shutdown() {
        disconnect();
        conn.post(new Runnable() {
            @Override
            public void run() {
                if (audioProfiles != null) {
                    audioProfiles.close();
                    audioProfiles = null;
                }
            }
        });
        connThread.quitSafely();
    }

    private void teardown() {
        if (scanner != null) scanner.stop();
        if (supervisor != null) {
            supervisor.stop();
            supervisor = null;
        }
        closeRelay();
        if (ble != null) {
            ble.close();
            ble = null;
        }
        if (pairing != null) {
            // Cancels its timeout; otherwise a torn-down connection still fires
            // a pairing failure into a dead state machine later.
            pairing.cancel();
            pairing = null;
        }
        sppUuid = null;
        // Bond keys are per-session; a new BLE bond derives fresh ones. Drop them
        // so a late profile-state event can't resend with a stale key.
        bondKey = null;
        bondIv = null;
        lastSentBtStatus = LinkCommands.BTSTATUS_DEFAULT;
    }

    private void closeRelay() {
        if (rfcomm != null) {
            rfcomm.close();
            rfcomm = null;
        }
        rfSession = null;
        relayEstablishing = false;
        conn.removeCallbacks(relayEstablishTimeout);
    }

    private void beginConnect() {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            failHard("no Bluetooth adapter on this device");
            return;
        }
        if (!adapter.isEnabled()) {
            // No point retrying on a timer -- this needs the user to act.
            failHard("Bluetooth is off -- turn it on and reconnect");
            return;
        }

        ownMac = localIdentity(adapter);
        ownId = DeviceId.macToBytes(ownMac);
        sessionId = deriveSession(ownId);
        SdkLog.log("target=" + targetMac + " ownId=" + Hex.encode(ownId)
                + " session=" + sessionId);

        device = adapter.getRemoteDevice(targetMac);
        // Stand up (once) the classic-audio profile manager. Reused across
        // reconnects so the profile proxies bind a single time.
        if (audioProfiles == null && config.connectAudioProfiles) {
            audioProfiles = new AudioProfiles(context, adapter, targetMac, audioListener);
        }
        // Fresh relay state per connection attempt (see the field comment).
        bleSession = new RelaySession();

        setState(ConnectionState.CONNECTING);
        ble = new BleTransport(context, device, conn, this);
        ble.connect();
    }

    /**
     * The identity we advertise. BluetoothAdapter.getAddress() has returned a
     * fixed placeholder since Android 6 for privacy reasons, so this is a
     * stand-in rather than our real MAC. Confirmed on hardware that the glasses
     * accept it -- they only use it to key the session.
     */
    private String localIdentity(BluetoothAdapter adapter) {
        String addr = adapter.getAddress();
        if (addr == null || addr.isEmpty() || "02:00:00:00:00:00".equals(addr)) {
            return config.localIdentityFallback;
        }
        return addr;
    }

    /** session = the last two bytes of our id, as a decimal string. */
    private static String deriveSession(byte[] id) {
        int v = ((id[id.length - 2] & 0xFF) << 8) | (id[id.length - 1] & 0xFF);
        return String.valueOf(v);
    }

    // ------------------------------------------------ BleTransport.Listener

    @Override
    public void onReady(BleTransport transport) {
        SdkLog.log("BLE link stable -- starting the ECDH bond");
        setState(ConnectionState.PAIRING);
        // Report the truthful status now: the BLE ACL is up, so at least
        // CONNECTED_ACL holds; if the audio profiles happen to already be
        // connected (a warm reconnect), advertise that instead. We upgrade this
        // to HFP/A2DP later, once connectAudioProfiles() takes effect.
        int btStatus = audioProfiles != null
                ? audioProfiles.currentBtStatus() : LinkCommands.BTSTATUS_CONNECTED_ACL;
        lastSentBtStatus = btStatus;
        pairing = new BlePairing(transport, conn, ownId, ownMac, config.deviceName,
                config.categoryId, btStatus,
                new BlePairing.Callback() {
                    @Override
                    public void onPaired(DeviceInfo glasses) {
                        glassesInfo = glasses;
                        // Retain the bond keys so we can push an updated btStatus
                        // when the audio profiles come up after the bond.
                        bondKey = pairing.sharedSecret();
                        bondIv = pairing.iv();
                        bondMode = pairing.encryptMode();
                        pairing = null;
                        dispatchDeviceInfo(glasses);
                        establishBleSession();
                    }

                    @Override
                    public void onFailed(String reason) {
                        pairing = null;
                        fail("BLE pairing failed: " + reason);
                    }
                });
        pairing.start();
    }

    @Override
    public void onInternalMessage(int pkgType, byte[] payload) {
        // While the bond is running, the pairing state machine consumes these.
        if (pairing != null && pairing.onInternalMessage(payload)) return;

        LinkMessage msg;
        try {
            msg = LinkProtocol.parse(payload);
        } catch (Exception e) {
            SdkLog.trace("internal <- unparseable (" + payload.length + "B)");
            return;
        }

        switch (msg.cmd) {
            case LinkCommands.CMD_SPP_SERVER_UUID_SYNC:
                handleSppUuidSync(msg.data);
                break;

            case LinkCommands.CMD_SPP_SERVER_REQUEST_CONNECT:
                SdkLog.trace("<- SPP_SERVER_REQUEST_CONNECT");
                if (supervisor != null) supervisor.wake();
                break;

            case LinkCommands.CMD_SPP_SERVER_REQUEST_STATE_OPEN:
                SdkLog.trace("<- SPP server open");
                if (supervisor != null) supervisor.wake();
                break;

            case LinkCommands.CMD_SPP_SERVER_REQUEST_STATE_CLOSE:
                // The glasses' SPP server is going away, so our socket is dead
                // even though socket-level liveness would not tell us that.
                //
                // These arrive in BURSTS (three within 200ms observed), and a
                // burst refers to sockets that are already gone. Acting on one
                // while a new socket is coming up tore down the replacement and
                // produced a tight connect/close loop, so ignore them during
                // establishment and let the establish timeout arbitrate.
                if (relayEstablishing) {
                    SdkLog.trace("<- SPP server close (stale; relay still establishing)");
                    break;
                }
                SdkLog.log("<- SPP server closed by the glasses -- dropping the relay");
                closeRelay();
                if (supervisor != null) supervisor.onRelayLost();
                break;

            default:
                SdkLog.trace("internal <- LinkProtocol cmd=" + msg.cmd
                        + " (" + msg.data.length + "B)");
                break;
        }
    }

    /**
     * The app relay lives at a random 16-bit UUID the glasses regenerate every
     * session and announce only here. Nothing else tells us where to connect.
     */
    private void handleSppUuidSync(byte[] data) {
        String uuid;
        try {
            uuid = LinkProtocol.sppShortUuidToString(data);
        } catch (Exception e) {
            SdkLog.warn("bad SPP UUID payload: " + Hex.encode(data));
            return;
        }
        if (uuid.equals(sppUuid)) {
            SdkLog.trace("<- SPP_SERVER_UUID_SYNC (unchanged)");
            return;
        }
        // A different UUID means a new relay instance: the old socket is dead.
        if (sppUuid != null) {
            SdkLog.log("relay UUID changed " + sppUuid + " -> " + uuid);
            closeRelay();
        }
        sppUuid = uuid;
        SdkLog.log("<- SPP_SERVER_UUID_SYNC: uuid=" + sppUuid);
        if (supervisor != null) supervisor.wake();
    }

    @Override
    public void onExternalMessage(int pkgType, byte[] payload) {
        routePayload(payload, bleSession, null);
    }

    @Override
    public void onDisconnected(String reason) {
        SdkLog.warn("BLE " + reason);
        teardown();
        setState(ConnectionState.FAILED);
        // A dropped link (out of range, glasses asleep, watchdog) should heal
        // itself rather than sit dead until the user notices.
        scheduleReconnect("BLE link dropped");
    }

    // ---------------------------------------------------------- reconnect

    /** Doubles each attempt so a persistently-absent device is not hammered. */
    private static final long RECONNECT_BASE_MS = 2000;
    private static final long RECONNECT_MAX_MS = 60000;

    /** True once the user asks to stop; suppresses auto-reconnect. */
    private boolean userStopped;
    private int reconnectAttempt;
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (userStopped || state == ConnectionState.READY) return;
            SdkLog.log("reconnecting to the glasses (attempt " + reconnectAttempt + ")");
            beginConnect();
        }
    };

    private void scheduleReconnect(String why) {
        if (userStopped || !config.autoReconnect) return;
        conn.removeCallbacks(reconnectRunnable);
        reconnectAttempt++;
        long delay = Math.min(RECONNECT_MAX_MS,
                RECONNECT_BASE_MS * (1L << Math.min(5, reconnectAttempt - 1)));
        SdkLog.log(why + " -- retrying in " + (delay / 1000) + "s");
        conn.postDelayed(reconnectRunnable, delay);
    }

    private void cancelReconnect() {
        conn.removeCallbacks(reconnectRunnable);
        reconnectAttempt = 0;
    }

    // ------------------------------------------------------- BLE session

    private void establishBleSession() {
        setState(ConnectionState.SESSION);
        sendAbility(bleSession, null);
    }

    // --------------------------------------------------- RFCOMM (relay)

    /**
     * A relay counts as up only once its session handshake has finished.
     *
     * An open socket is NOT sufficient: the glasses can close their SPP server
     * mid-handshake (cmd 73), leaving a socket that looks alive but carries a
     * session that never completed. Checking only the socket left us wedged in
     * that half-state, silently falling back to BLE.
     */
    @Override
    public boolean isRelayConnected() {
        if (relayEstablishing) return true; // handshake in flight; give it time
        return rfcomm != null && rfcomm.isConnected() && rfSession != null && rfSession.ready;
    }

    @Override
    public boolean canConnectRelay() {
        return sppUuid != null && device != null && !relayEstablishing;
    }

    @Override
    public void connectRelay() {
        if (relayEstablishing) return;
        closeRelay();
        relayEstablishing = true;

        UUID uuid = UUID.fromString(sppUuid);
        SdkLog.log("opening the app relay: RFCOMM -> " + uuid);
        rfSession = new RelaySession();
        rfcomm = new RfcommTransport(device, uuid, relayListener, conn);
        rfcomm.connect();

        // Bound the whole connect+handshake+init-burst window so a stalled
        // bring-up is retried instead of hanging the relay indefinitely.
        conn.postDelayed(relayEstablishTimeout, RELAY_ESTABLISH_TIMEOUT_MS);
    }

    private final Runnable relayEstablishTimeout = new Runnable() {
        @Override
        public void run() {
            if (!relayEstablishing) return;
            relayEstablishing = false;
            if (rfSession != null && rfSession.ready) return; // finished in time
            SdkLog.warn("app relay did not finish its handshake within "
                    + (RELAY_ESTABLISH_TIMEOUT_MS / 1000) + "s -- retrying");
            closeRelay();
            if (supervisor != null) supervisor.onRelayLost();
        }
    };

    private void relayEstablished() {
        relayEstablishing = false;
        conn.removeCallbacks(relayEstablishTimeout);
    }

    private final TransportListener relayListener = new TransportListener() {
        @Override
        public void onConnected(Transport transport) {
            SdkLog.log("app relay connected -- running its own session handshake");
            sendAbility(rfSession, transport);
        }

        @Override
        public void onPayload(Transport transport, byte[] payload) {
            routePayload(payload, rfSession, transport);
        }

        @Override
        public void onDisconnected(Transport transport, Throwable cause) {
            relayEstablished();
            if (cause != null) {
                SdkLog.warn("app relay lost: " + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage());
            } else {
                SdkLog.log("app relay closed");
            }
            if (rfcomm == transport) {
                rfcomm = null;
                rfSession = null;
            }
            if (supervisor != null) supervisor.onRelayLost();
        }
    };

    // ------------------------------------------- session handshake (both)

    /**
     * The RunAsOne ability handshake. Identical on both transports -- only the
     * framing below differs -- which is why Session/Relay are transport-agnostic.
     */
    private void sendAbility(RelaySession session, Transport transport) {
        if (session == null) return;
        try {
            byte[] msg = Session.buildAbilityMessage(
                    Hex.encode(ownId), config.deviceName, sessionId);
            SdkLog.log("-> ability handshake (session=" + sessionId + ")");
            sendOn(transport, msg);
        } catch (Exception e) {
            SdkLog.error("could not build the ability message", e);
        }
    }

    private void routePayload(byte[] payload, RelaySession session, Transport transport) {
        if (session == null) return;

        // The ability reply is a bare StreamReq (class byte 0x02); everything
        // else is a relay frame (prefix 0x01).
        if (payload.length > 0 && (payload[0] & 0xFF) == Session.AUTH_CLASS_BYTE) {
            handleAbilityReply(payload, session, transport);
            return;
        }

        RelayMessage m = Relay.parseFrame(payload);
        if (m == null) {
            SdkLog.trace("<- unparsed " + payload.length + "B "
                    + Hex.encode(payload, 0, Math.min(32, payload.length)));
            return;
        }
        handleRelayMessage(m, session, transport);
    }

    private void handleAbilityReply(byte[] payload, final RelaySession session,
                                    final Transport transport) {
        AbilityReply reply = Session.parseAbilityReply(payload);
        // The glasses repeat this reply; answering twice would start a second
        // interleaved init burst on the same sequencer.
        if (session.authConfirmed) {
            SdkLog.trace("<- duplicate ability reply ignored");
            return;
        }
        session.authConfirmed = true;

        SdkLog.log("<- ability reply from deviceId=" + reply.deviceId);
        try {
            // Without AUTH_SUCCESS the glasses ack our data but never engage
            // the app layer.
            byte[] confirm = Session.buildAuthSuccessMessage(
                    Hex.encode(ownId), config.deviceName, sessionId);
            SdkLog.log("-> AUTH_SUCCESS");
            sendOn(transport, confirm);
        } catch (Exception e) {
            SdkLog.error("could not build AUTH_SUCCESS", e);
            return;
        }
        conn.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendInitBurst(session, transport);
            }
        }, 500);
    }

    private void handleRelayMessage(RelayMessage m, RelaySession session, Transport transport) {
        if (m.msgType == MsgType.SEND_SUCCESS) {
            SdkLog.trace("<- ack msgId=" + m.msgId
                    + (transport != null ? " [relay]" : " [ble]"));
            return;
        }
        if (m.msgType == MsgType.SEND) {
            session.seq.lastRecvId = m.msgId;

            // The glasses stream their own microphone as Opus packets in
            // code:109 messages, dozens per second. They are binary, so they
            // must never reach the log or the JSON scanner -- doing so saturated
            // this thread and stalled everything behind it. Hand them to the
            // audio listener (myvu-ai installs one), which is what they are for.
            if (isAudioFrame(m.msgBody)) {
                if (m.needCallback != 0) sendOn(transport, session.seq.ackFrame(m));
                AudioFrameListener afl = audioFrameListener;
                if (afl != null) afl.onAudioFrame(m.msgBody);
                if (++audioFrameCount % 200 == 0) {
                    SdkLog.trace("received " + audioFrameCount + " glasses mic frames");
                }
                return;
            }

            String body = new String(m.msgBody, StandardCharsets.UTF_8);
            SdkLog.log("<- msgId=" + m.msgId + " " + truncate(body, 200));
            // The glasses re-send indefinitely until acknowledged.
            if (m.needCallback != 0) sendOn(transport, session.seq.ackFrame(m));
            // Answer anything that needs a reply (launch-app, time sync, AI).
            inbound.handle(body);
            dispatchRaw(body);
            // Surface each embedded JSON object the SDK does not itself consume
            // (AI triggers are dispatched from the InboundRouter callback). This
            // is high-volume telemetry mixed with query replies -- consumers
            // filter it via the event stream.
            for (String candidate : InboundRouter.findJsonObjects(body)) {
                if (candidate.length() <= 4) continue;
                if (isAiTriggerObject(candidate)) continue; // already dispatched
                dispatchEvent(new GlassesEvent.Unknown(candidate));
            }
            return;
        }
        SdkLog.trace("<- relay msgType=" + m.msgType + " msgId=" + m.msgId);
    }

    // --------------------------------------------------------- init burst

    /**
     * Replays the captured opening messages with a fresh 1..N msgId sequence,
     * paced 200ms apart. Without this the glasses' relay dispatcher never fully
     * wakes and silently drops everything sent afterwards. Required on EVERY
     * transport, BLE and RFCOMM alike.
     */
    private void sendInitBurst(RelaySession session, Transport transport) {
        final List<InitBurst.Entry> entries;
        try {
            InputStream in = initBurstSource.open();
            entries = InitBurst.load(in);
            in.close();
        } catch (Exception e) {
            SdkLog.error("could not read the init burst capture", e);
            return;
        }
        SdkLog.log("-> init burst (" + entries.size() + " messages"
                + (transport != null ? ", relay)" : ", ble)"));
        scheduleInitMessage(entries, 0, session, transport);
    }

    private void scheduleInitMessage(final List<InitBurst.Entry> entries, final int index,
                                     final RelaySession session, final Transport transport) {
        if (index >= entries.size()) {
            session.ready = true;
            SdkLog.log("init burst complete -- "
                    + (transport != null ? "app relay ready" : "BLE session ready"));
            onSessionReady(transport);
            return;
        }
        if (!isUsable(transport)) {
            SdkLog.warn("link dropped during the init burst at message " + index
                    + " -- session left incomplete");
            // Leaving the session half-initialised is not recoverable: the
            // glasses' dispatcher never woke, so tear it down and let the
            // supervisor (relay) or the user (BLE) start a clean one rather
            // than sitting in a state that looks connected but drops traffic.
            if (transport != null) {
                closeRelay();
                if (supervisor != null) supervisor.onRelayLost();
            } else {
                fail("BLE init burst did not complete");
            }
            return;
        }

        InitBurst.Entry e = entries.get(index);
        sendOn(transport, session.seq.dataFrame(
                e.msgBody, e.category, e.needCallback, e.appUniteCode));
        conn.postDelayed(new Runnable() {
            @Override
            public void run() {
                scheduleInitMessage(entries, index + 1, session, transport);
            }
        }, 200);
    }

    private void onSessionReady(Transport transport) {
        setState(ConnectionState.READY);
        // A clean session clears the backoff, so the next drop starts fresh.
        cancelReconnect();
        if (transport != null) relayEstablished();

        // Apply defaults only on the transport that will actually carry app
        // traffic. BLE goes ready first and the relay takes over seconds later,
        // so applying on both sent all four commands twice. If the glasses have
        // already told us where the relay lives, wait for it; if they never do,
        // BLE is the active transport and gets them.
        boolean relayExpected = (transport == null) && sppUuid != null;
        if (!relayExpected) {
            if (config.applyDefaultSettings) applyDefaults();
            // The classic radio is provably awake by now (this same link is
            // classic RFCOMM when transport != null; BLE otherwise), so it is
            // safe to page the audio profiles. This is what makes the glasses
            // show "phone connected"; the app relay alone never does.
            if (audioProfiles != null) audioProfiles.connect(device);
        }

        if (transport == null) {
            // BLE is up: from here the glasses will sync the relay UUID, and the
            // supervisor takes over opening (and reopening) the relay.
            if (supervisor == null) {
                supervisor = new RelaySupervisor(conn, this);
                supervisor.start();
            }
            supervisor.wake();
        }
    }

    /**
     * Live state pushed on connect and re-pushed after a relay reconnect, since
     * the init burst deliberately omits it (the captured SyncOffSetTime and
     * sync_clone_data frames carry stale values and are filtered out).
     */
    private void applyDefaults() {
        try {
            sendActionNow(ClockSync.build());
            sendActionNow(SystemSettings.setWearDetection(true));
            sendActionNow(SystemSettings.setZenMode(false));
            sendActionNow(SystemSettings.setScreenOffTime(10));
        } catch (Exception e) {
            SdkLog.error("could not apply the default settings", e);
        }
    }

    // ------------------------------------------------- classic audio profiles

    /** Profile connection-state changes land here (posted onto the conn thread). */
    private final AudioProfiles.Listener audioListener = new AudioProfiles.Listener() {
        @Override
        public void onStatusChanged(final int btStatus) {
            conn.post(new Runnable() {
                @Override
                public void run() {
                    onBtStatusChanged(btStatus);
                }
            });
        }
    };

    private void onBtStatusChanged(int btStatus) {
        if (btStatus == lastSentBtStatus) return;
        SdkLog.log("BT audio status changed -> " + btStatusName(btStatus)
                + "; updating the glasses");
        resendDeviceInfo(btStatus);
    }

    /**
     * Pushes a fresh DeviceInfo carrying the current btStatus over the BLE
     * pairing channel, so the glasses learn the phone is now HFP/A2DP-connected
     * (or dropped back to ACL). Mirrors BlePairing.sendOwnDeviceInfo's double
     * encryption, reusing the retained bond keys.
     */
    private void resendDeviceInfo(int btStatus) {
        if (bondKey == null || ble == null || !ble.isConnected()) return;
        try {
            byte[] info = DeviceInfo.build(
                    ownMac.toUpperCase(), "", config.categoryId, "",
                    config.deviceName, 100, btStatus);
            byte[] inner = StarryCrypto.encrypt(info, bondKey, bondIv, bondMode);
            byte[] wsi = LinkProtocol.writeSwitchInfo(inner, 0);
            byte[] outer = StarryCrypto.encrypt(wsi, bondKey, bondIv, bondMode);
            byte[] msg = LinkProtocol.build(ownId, LinkCommands.CMD_WRITE_SWITCH_INFO, outer);
            SdkLog.log("-> WRITE_SWITCH_INFO btStatus=" + btStatusName(btStatus)
                    + " (" + msg.length + "B)");
            lastSentBtStatus = btStatus;
            ble.internal().sendSingleAcked(msg, BlePackets.PKG_STARRY_DATA,
                    new BleMessageChannel.AckCallback() {
                        @Override
                        public void onAck(int status) {
                            if (status != BlePackets.ACK_SUCCESS) {
                                SdkLog.warn("btStatus update was not acked (status="
                                        + status + ")");
                            }
                        }
                    });
        } catch (Exception e) {
            SdkLog.error("could not resend DeviceInfo btStatus", e);
        }
    }

    private static String btStatusName(int s) {
        switch (s) {
            case LinkCommands.BTSTATUS_CONNECTED_ACL:  return "ACL";
            case LinkCommands.BTSTATUS_CONNECTED_HFP:  return "HFP";
            case LinkCommands.BTSTATUS_CONNECTED_A2DP: return "A2DP";
            default: return String.valueOf(s);
        }
    }

    // --------------------------------------------------------- app layer

    /** Sends one app action through the relay. Safe to call from any thread. */
    public void sendAction(final String actionJson) {
        sendAction(actionJson, AppLayer.PKG_LAUNCHER, AppLayer.PKG_LAUNCHER);
    }

    /**
     * Sends with explicit routing. Several features must NOT go to the launcher:
     * teleprompter messages come from com.upuphone.ar.tici, the launch-app ack
     * rides the interconnect channel, and nav frames address the nav app.
     */
    public void sendAction(final String actionJson, final String targetPkg,
                           final String sourcePkg) {
        conn.post(new Runnable() {
            @Override
            public void run() {
                sendActionNow(actionJson, targetPkg, sourcePkg);
            }
        });
    }

    /** Connection-thread-only variant. */
    private void sendActionNow(String actionJson) {
        sendActionNow(actionJson, AppLayer.PKG_LAUNCHER, AppLayer.PKG_LAUNCHER);
    }

    private void sendActionNow(String actionJson, String targetPkg, String sourcePkg) {
        RelaySession session = activeSession();
        Transport transport = activeTransport();
        if (session == null || !session.ready) {
            SdkLog.warn("no ready session -- action dropped");
            return;
        }
        sendOn(transport, session.seq.dataFrame(
                session.appLayer.buildSendActionBody(actionJson, targetPkg, sourcePkg)));
        SdkLog.log("-> action msgId=" + session.seq.getOutId()
                + (transport != null ? " [relay] " : " [ble] ") + truncate(actionJson, 120));
    }

    // ------------------------------------------------------- feature API

    /**
     * Opens the teleprompter. Two messages 400ms apart, both sourced from the
     * tici package -- the content is dropped if the app has not come up yet.
     */
    public void openTeleprompter(final String text, final String title) {
        conn.post(new Runnable() {
            @Override
            public void run() {
                try {
                    sendActionNow(Teleprompter.buildOpen(text, title),
                            AppLayer.PKG_LAUNCHER, AppLayer.PKG_TICI);
                    conn.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                sendActionNow(Teleprompter.buildContent(text, title),
                                        AppLayer.PKG_LAUNCHER, AppLayer.PKG_TICI);
                            } catch (Exception e) {
                                SdkLog.error("teleprompter content failed", e);
                            }
                        }
                    }, Teleprompter.OPEN_TO_CONTENT_DELAY_MS);
                } catch (Exception e) {
                    SdkLog.error("teleprompter open failed", e);
                }
            }
        });
    }

    public void teleprompterHighlight(final int index, final String title) {
        try {
            sendAction(Teleprompter.buildHighlight(index, title),
                    AppLayer.PKG_LAUNCHER, AppLayer.PKG_TICI);
        } catch (Exception e) {
            SdkLog.error("teleprompter highlight failed", e);
        }
    }

    // ----------------------------------------------------------- trackpad
    // The phone as a remote touchpad for the glasses' launcher. Each event is a
    // "phonepad" action to the launcher; sendAction already routes there.

    public void trackpadStart()       { sendAction(Trackpad.start()); }
    public void trackpadStop()        { sendAction(Trackpad.stop()); }
    public void trackpadClick()       { sendAction(Trackpad.click()); }
    public void trackpadDoubleClick() { sendAction(Trackpad.doubleClick()); }
    public void trackpadLongPress()   { sendAction(Trackpad.longPress()); }

    public void trackpadSwipe(int direction, float startX, float startY,
                              float endX, float endY, float speedX, float speedY) {
        sendAction(Trackpad.swipe(direction, startX, startY, endX, endY, speedX, speedY));
    }

    // ------------------------------------------------------------ settings

    /** Volume 0-15. */
    public void setVolume(int value) {
        safeSend(safeVolume(value));
    }

    /** Brightness, observed 0-10. */
    public void setBrightness(int value) {
        safeSend(safeBrightness(value));
    }

    /** Turn the glasses' WiFi radio on/off. */
    public void toggleWifi(boolean on) {
        safeSend(safeToggleWifi(on));
    }

    /** Standby-widget field-of-view position 0-3. */
    public void setStandbyPosition(int position) {
        safeSend(safeStandby(position));
    }

    /** Do-not-disturb. */
    public void setZenMode(boolean on) {
        safeSend(safeZen(on));
    }

    /** Low-power HUD; closes all apps. */
    public void setAirMode(boolean on) {
        safeSend(safeAir(on));
    }

    /** Auto on/off when worn. */
    public void setWearDetection(boolean on) {
        safeSend(safeWear(on));
    }

    /** Music touch-panel control. */
    public void setMusicTpControl(boolean on) {
        safeSend(safeMusicTp(on));
    }

    /** Display auto-off, seconds. */
    public void setScreenOffTime(int seconds) {
        safeSend(safeScreenOff(seconds));
    }

    /** Rename the glasses. */
    public void setDeviceName(String name) {
        safeSend(safeDeviceName(name));
    }

    /** Language + country, e.g. en/US. */
    public void setLanguage(String language, String country) {
        safeSend(safeLanguage(language, country));
    }

    /** Push the current wall-clock time to the glasses. */
    public void syncTime() {
        safeSend(safeClockSync());
    }

    /** Send arbitrary hand-written action JSON. */
    public void sendRaw(String actionJson) {
        sendAction(actionJson);
    }

    /** Any no-argument "system" query. Replies arrive as QueryReply events. */
    public void query(String subAction) {
        try {
            sendAction(SystemSettings.query(subAction));
        } catch (Exception e) {
            SdkLog.error("query failed", e);
        }
    }

    /** Shows a notification card on the glasses. */
    public void showNotification(String title, String body) {
        try {
            sendAction(AppLayer.buildNotificationAction(title, body));
        } catch (Exception e) {
            SdkLog.error("could not build the notification", e);
        }
    }

    // -- small builders, each swallowing the (unreachable) JSONException ------

    private String safeVolume(int v) {
        try { return SystemSettings.setVolume(v); } catch (Exception e) { return null; }
    }
    private String safeBrightness(int v) {
        try { return SystemSettings.setBrightness(v); } catch (Exception e) { return null; }
    }
    private String safeToggleWifi(boolean on) {
        try { return SystemSettings.toggleWifi(on); } catch (Exception e) { return null; }
    }
    private String safeStandby(int p) {
        try { return SystemSettings.setStandbyPosition(p); } catch (Exception e) { return null; }
    }
    private String safeZen(boolean on) {
        try { return SystemSettings.setZenMode(on); } catch (Exception e) { return null; }
    }
    private String safeAir(boolean on) {
        try { return SystemSettings.setAirMode(on); } catch (Exception e) { return null; }
    }
    private String safeWear(boolean on) {
        try { return SystemSettings.setWearDetection(on); } catch (Exception e) { return null; }
    }
    private String safeMusicTp(boolean on) {
        try { return SystemSettings.setMusicTpControl(on); } catch (Exception e) { return null; }
    }
    private String safeScreenOff(int s) {
        try { return SystemSettings.setScreenOffTime(s); } catch (Exception e) { return null; }
    }
    private String safeDeviceName(String n) {
        try { return SystemSettings.setDeviceName(n); } catch (Exception e) { return null; }
    }
    private String safeLanguage(String l, String c) {
        try { return SystemSettings.setLanguage(l, c); } catch (Exception e) { return null; }
    }
    private String safeClockSync() {
        try { return ClockSync.build(); } catch (Exception e) { return null; }
    }

    private void safeSend(String actionJson) {
        if (actionJson != null) sendAction(actionJson);
    }

    // ------------------------------------------------------- send routing

    /**
     * Prefer the classic-BT relay once ready; fall back to BLE otherwise.
     *
     * The fallback is deliberate but must be LOUD. Silently degrading to BLE
     * turned a dead relay into a confusing partial success -- messages logged as
     * sent while the glasses did nothing -- and cost real debugging time.
     */
    private RelaySession activeSession() {
        if (rfSession != null && rfSession.ready && isRelayConnected()) {
            relayFallbackWarned = false;
            return rfSession;
        }
        if (bleSession.ready) {
            if (!relayFallbackWarned) {
                relayFallbackWarned = true;
                SdkLog.warn("app relay is DOWN -- falling back to BLE. Features that "
                        + "need the relay (nav HUD, teleprompter) may not respond.");
            }
            return bleSession;
        }
        return null;
    }

    private boolean relayFallbackWarned;

    private Transport activeTransport() {
        if (rfSession != null && rfSession.ready && isRelayConnected()) return rfcomm;
        return null; // null means BLE's external channel
    }

    /** A null transport routes over BLE's external characteristic. */
    private void sendOn(Transport transport, byte[] payload) {
        if (transport != null) {
            transport.send(payload);
        } else if (ble != null && ble.isReady()) {
            ble.external().send(payload, BlePackets.PKG_COMMON_DATA);
        }
    }

    private boolean isUsable(Transport transport) {
        return transport != null ? transport.isConnected() : (ble != null && ble.isReady());
    }

    // ------------------------------------------------------------ helpers

    /** A transient failure: tear down and retry on a backoff. */
    private void fail(String why) {
        SdkLog.warn(why);
        teardown();
        setState(ConnectionState.FAILED);
        scheduleReconnect("connection failed");
    }

    /** A failure the user must resolve (Bluetooth off, no adapter): no retry. */
    private void failHard(String why) {
        SdkLog.warn(why);
        cancelReconnect();
        teardown();
        setState(ConnectionState.FAILED);
    }

    private void setState(ConnectionState s) {
        state = s;
        dispatchState(s);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "null";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    /** How many mic frames we have seen, for the periodic summary line. */
    private int audioFrameCount;

    /** True if this object is an AI button/wake-word trigger (dispatched elsewhere). */
    private static boolean isAiTriggerObject(String candidate) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(candidate);
            int code = o.optInt("code", -1);
            return code == 3 || code == 7;
        } catch (Exception e) {
            return false;
        }
    }

    /** ASCII for the marker that identifies a CODE_RECORD_DATA_TRANS message. */
    private static final byte[] AUDIO_MARKER =
            "\"code\":109".getBytes(StandardCharsets.US_ASCII);

    /**
     * True for the glasses' streamed microphone audio (code:109).
     *
     * Matched on raw bytes rather than by decoding: the payload is mostly Opus
     * data, so building a String just to inspect it is exactly the cost we are
     * trying to avoid. Same approach the Python client takes.
     */
    private static boolean isAudioFrame(byte[] body) {
        if (body.length < AUDIO_MARKER.length) return false;
        outer:
        for (int i = 0; i <= body.length - AUDIO_MARKER.length; i++) {
            for (int j = 0; j < AUDIO_MARKER.length; j++) {
                if (body[i + j] != AUDIO_MARKER[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
