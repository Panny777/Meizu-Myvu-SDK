# MYVU Android SDK (unofficial)

An unofficial Android SDK for **Meizu MYVU / Star Air** AR glasses (model
XGA010C). It connects to the glasses, drives the teleprompter, notifications,
system settings and trackpad, and optionally adds turn-by-turn navigation and a
voice assistant.

> **Unofficial and unaffiliated.** This project is not produced, endorsed, or
> supported by Meizu. "MYVU" and "Meizu" are trademarks of their respective
> owners. The protocol was reverse-engineered from packet captures; behaviour
> may break with any firmware update. Use at your own risk.

Built from a working reverse-engineered client and modelled on the layered
design of the [Brilliant Labs SDK](https://github.com/brilliantlabsAR/brilliant_sdk):
a dependency-light core plus optional feature modules and a sample app.

## Modules

| Module | Artifact | What it adds | Extra dependencies |
|---|---|---|---|
| Core | `myvu-core` | Connection, pairing, teleprompter, notifications, settings, trackpad, queries, raw actions | Kotlin coroutines only |
| Nav | `myvu-nav` | Turn-by-turn HUD navigation | Google Play Services location (swappable) |
| AI | `myvu-ai` | Voice assistant over the glasses' mic (pluggable STT / LLM / TTS) | none |

Only `myvu-core` is required. Add `myvu-nav` / `myvu-ai` if you want those
features.

## Install (JitPack)

`settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

App `build.gradle` — the coordinate is `com.github.<owner>.<repo>:<module>:<tag>`:

```groovy
dependencies {
    implementation 'com.github.Panny777.Meizu-Myvu-SDK:myvu-core:v0.1.0'
    implementation 'com.github.Panny777.Meizu-Myvu-SDK:myvu-nav:v0.1.0'  // optional
    implementation 'com.github.Panny777.Meizu-Myvu-SDK:myvu-ai:v0.1.0'   // optional
}
```

[![JitPack](https://jitpack.io/v/Panny777/Meizu-Myvu-SDK.svg)](https://jitpack.io/#Panny777/Meizu-Myvu-SDK)

Minimum SDK 26, compiled against SDK 34, Java 17.

## Permissions

The SDK's manifest contributes the Bluetooth permissions; your app must request
the runtime ones (API 31+):

| Permission | Why | Runtime? |
|---|---|---|
| `BLUETOOTH_CONNECT` | connect / pair / GATT | yes (API 31+) |
| `BLUETOOTH_SCAN` (`neverForLocation`) | auto-search for glasses | yes (API 31+) |
| `BLUETOOTH_PRIVILEGED` | best-effort HFP/A2DP so the glasses show "phone connected" | not grantable to normal apps; safe to leave declared |
| `ACCESS_FINE_LOCATION` | `myvu-nav` only | yes |
| `INTERNET` | `myvu-nav` (routing) and cloud AI engines | no |

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    requestPermissions(arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
    ), 1)
}
```

## Quickstart — Kotlin (facade)

```kotlin
val glasses = MyvuGlasses(context)               // MyvuConfig defaults are fine

lifecycleScope.launch {
    glasses.state.collect { println("state = $it") }
}

lifecycleScope.launch {
    glasses.connect()                            // null MAC = auto-search; suspends until READY
    glasses.openTeleprompter("Hello from the SDK", title = "Demo")
    glasses.setBrightness(8)
    val battery = glasses.query("request_phone_battery")  // best-effort reply
}
```

`MyvuGlasses` exposes `state: StateFlow<ConnectionState>`,
`deviceInfo: StateFlow<DeviceInfo?>`, `events: SharedFlow<GlassesEvent>` and
`rawInbound: SharedFlow<String>`. Call `glasses.close()` when done.

## Quickstart — Java (core)

```java
MyvuClient client = new MyvuClient(context);     // or new MyvuClient(context, config)
client.addListener(new MyvuClient.Listener() {
    @Override public void onConnectionStateChanged(ConnectionState s) {
        Log.i("app", "state = " + s);
    }
    @Override public void onDeviceInfo(DeviceInfo info) {
        Log.i("app", "glasses battery = " + info.battery);
    }
});
client.connectAutoSearch();                       // or client.connect("2C:6F:4E:..:..:..")
// once READY:
client.openTeleprompter("Hello from the SDK", "Demo");
client.setBrightness(8);
```

## Feature overview

All available on both `MyvuClient` (Java) and `MyvuGlasses` (Kotlin):

- **Teleprompter** — `openTeleprompter(text, title)`, `teleprompterHighlight(index, title)`
- **Notifications** — `showNotification(title, body)`
- **Settings** — `setBrightness` (0–10), `setVolume` (0–15), `toggleWifi`,
  `setZenMode`, `setAirMode`, `setWearDetection`, `setMusicTpControl`,
  `setScreenOffTime`, `setStandbyPosition` (0–3), `setDeviceName`, `setLanguage`
- **Clock** — `syncTime()`
- **Trackpad** — `trackpadStart/Stop/Click/DoubleClick/LongPress/Swipe(...)`
- **Queries** — `query(subAction)` (e.g. `get_device_info`, `request_phone_battery`,
  `get_brightness`, `request_wifi_list`); replies arrive via `events`/`onRawInbound`
- **Escape hatch** — `sendRaw(actionJson)` for hand-written action JSON

### Navigation (`myvu-nav`)

```java
NavSession nav = new NavSession(context, client, new FusedLocationSource(context));
nav.start("Times Square");   // place name or "lat,lon"
// nav.stop();
```

Use `LocationManagerSource` instead of `FusedLocationSource` to avoid Google
Play Services, or pass a custom `RouteProvider` to use your own OSRM instance.

### AI assistant (`myvu-ai`)

The glasses stream their own microphone; the SDK runs the on-glasses protocol
(VAD, captions, TTS play-state) and delegates recognition and answering to
engines you provide:

```java
AiSession ai = new AiSession(context, client,
        mySpeechToText,      // implements SpeechToText
        myLanguageModel,     // implements LanguageModel
        null);               // null = platform TextToSpeech
ai.attach();                 // now responds to the glasses' AI button / wake word
```

The sample app ships Groq Whisper (`GroqSpeechToText`) and Claude
(`ClaudeLanguageModel`) adapters as a reference. The SDK itself ships no cloud
clients or API keys.

## Keeping the connection alive

The SDK does not ship a service — foreground-service type, notification channel
and Doze policy are app decisions. `sample-app/.../MyvuService.java` is a
copy-and-adapt reference: a `connectedDevice` foreground service that owns a
single `MyvuClient` and exposes it statically.

## Custom logging

```java
SdkLog.setLogger((level, message, error) -> { /* route to your logger */ });
```

## Troubleshooting

- **Nothing connects / pairing hangs** — the glasses accept **one** central at a
  time. Force-stop the official Meizu app before connecting.
- **`createBond` times out** — BLE must come up before BR/EDR; the SDK already
  orders this. Don't pre-bond the glasses manually.
- **Relay-down warnings, features unresponsive** — the classic-BT app relay
  carries teleprompter/nav; if it drops the SDK falls back to BLE and warns
  loudly. It re-establishes on its own.
- **MIUI / some OEMs** — sideloaded builds may need `adb ... pm install` and
  battery-optimisation exemption for the connection to survive backgrounding.

## Building from source

```
./gradlew build              # all modules + minified sample release (proves R8 rules)
./gradlew testDebugUnitTest  # the byte-level protocol test suite
```

Requires JDK 17. See [PROTOCOL.md](PROTOCOL.md) for the wire protocol.

## License

MIT — see [LICENSE](LICENSE). © 2026 Panny777. Reverse-engineered protocol
knowledge; the decompiled official app is not included.
