package dev.myvu.sdk;

/**
 * Immutable configuration for a {@link MyvuClient}.
 *
 * The defaults reproduce the identity the protocol was reverse-engineered
 * with; the glasses accept them as-is, so most apps only ever need
 * {@code MyvuConfig.builder().build()}.
 */
public final class MyvuConfig {

    public static final String DEFAULT_DEVICE_NAME = "MyvuAndroid";
    /** categoryId advertised during pairing and in every DeviceInfo. */
    public static final String DEFAULT_CATEGORY_ID = "9999";
    /**
     * BluetoothAdapter.getAddress() has returned a fixed placeholder since
     * Android 6 for privacy reasons, so a stand-in identity is advertised
     * instead. Confirmed on hardware that the glasses accept it -- they only
     * use it to key the session.
     */
    public static final String DEFAULT_LOCAL_IDENTITY = "AA:BB:CC:DD:EE:FF";

    /** Name the glasses display for this phone. */
    public final String deviceName;
    /** categoryId used in the pairing version JSON and DeviceInfo. */
    public final String categoryId;
    /** Identity advertised when the adapter hides its real MAC. */
    public final String localIdentityFallback;
    /** Init-burst capture; null means the capture bundled with the SDK. */
    public final InitBurstSource initBurstSource;
    /** Reconnect automatically on link drops (exponential backoff 2s..60s). */
    public final boolean autoReconnect;
    /**
     * Page the HFP/A2DP audio profiles once a session is up so the glasses
     * light their "phone connected" indicator. Best-effort: uses reflection
     * and may be a no-op on some builds.
     */
    public final boolean connectAudioProfiles;
    /** Push clock sync + baseline settings when a session becomes ready. */
    public final boolean applyDefaultSettings;

    private MyvuConfig(Builder b) {
        this.deviceName = b.deviceName;
        this.categoryId = b.categoryId;
        this.localIdentityFallback = b.localIdentityFallback;
        this.initBurstSource = b.initBurstSource;
        this.autoReconnect = b.autoReconnect;
        this.connectAudioProfiles = b.connectAudioProfiles;
        this.applyDefaultSettings = b.applyDefaultSettings;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String deviceName = DEFAULT_DEVICE_NAME;
        private String categoryId = DEFAULT_CATEGORY_ID;
        private String localIdentityFallback = DEFAULT_LOCAL_IDENTITY;
        private InitBurstSource initBurstSource;
        private boolean autoReconnect = true;
        private boolean connectAudioProfiles = true;
        private boolean applyDefaultSettings = true;

        public Builder deviceName(String name) {
            this.deviceName = name;
            return this;
        }

        public Builder categoryId(String id) {
            this.categoryId = id;
            return this;
        }

        public Builder localIdentityFallback(String mac) {
            this.localIdentityFallback = mac;
            return this;
        }

        public Builder initBurstSource(InitBurstSource source) {
            this.initBurstSource = source;
            return this;
        }

        public Builder autoReconnect(boolean on) {
            this.autoReconnect = on;
            return this;
        }

        public Builder connectAudioProfiles(boolean on) {
            this.connectAudioProfiles = on;
            return this;
        }

        public Builder applyDefaultSettings(boolean on) {
            this.applyDefaultSettings = on;
            return this;
        }

        public MyvuConfig build() {
            return new MyvuConfig(this);
        }
    }
}
