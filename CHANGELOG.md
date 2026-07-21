# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] — 2026-07-22

### Changed
- **Assistant answers now use the glasses' LLM card scene.** `AiSession` sends
  `assistantConfig` (code 2) at the start of a conversation, opens the card with
  `chatQuery` (code 102) on every turn, and commits the answer with `chatAnswer`
  (code 122) at `base_status` 1 then 2 — matching a btsnoop of the official app.
  Previously the answer went out as a `code:5` card, which is not the surface
  the official app uses.
- Spoken follow-up turns are now opt-in via `AiSession.setSpokenFollowUpTurns`.
  They are off by default because the glasses expect an `isNextRecorded` signal
  that only the official cloud NLU produces; forcing a turn without it wedges
  the session.

### Deprecated
- `AiProtocol.answerCard` / `CODE_TTS_PLAY_REQ` — superseded by
  `chatQuery` + `chatAnswer`. Still present so existing callers compile.

## [0.2.1] — 2026-07-21

### Added
- **Weather support.** `myvu-core` gains the `weather` wire format
  (`MyvuClient.sendWeather`) and surfaces the glasses' `syncWeather` requests as
  `GlassesEvent.WeatherRequested`.
- **New `myvu-weather` module** — `WeatherSync` keeps the glasses' weather panel
  fed (push on connect, refresh every 30 min, retry after 30 s), backed by
  Open-Meteo (no API key). Location comes from a swappable `WeatherLocation`:
  `DeviceWeatherLocation` (platform LocationManager, no Play Services),
  `PlaceWeatherLocation` (geocoded name or `"lat,lon"`) or
  `FixedWeatherLocation`.

### Fixed
- AI voice capture on real hardware: wake the relay supervisor on an AI button
  press, calibrate the VAD noise floor from quiet chunks only, clear the
  no-speech timeout once speech starts, and open mic capture only after the Opus
  decoder is live.
- Tests and docs no longer carry a real device MAC.

## [0.1.0] — 2026-07-21

Initial release. Extracted from the working reverse-engineered Android client
into a reusable multi-module SDK.

### Added
- **`myvu-core`** — `MyvuClient` (Java) and `MyvuGlasses` (Kotlin coroutine
  facade): BLE + classic-Bluetooth connection, ECDH pairing, RunAsOne session,
  init burst, relay supervision and auto-reconnect. Features: teleprompter,
  notifications, system settings, clock sync, trackpad, queries, raw actions.
  Configurable via `MyvuConfig`; pluggable logging via `SdkLog` / `MyvuLogger`;
  glasses events via `GlassesEvent`.
- **`myvu-nav`** — `NavSession` turn-by-turn HUD navigation with a swappable
  `RouteProvider` (default `OsrmRouteProvider`) and `LocationSource`
  (`FusedLocationSource` or the Play-Services-free `LocationManagerSource`).
- **`myvu-ai`** — `AiSession` driving the glasses-microphone assistant protocol,
  with pluggable `SpeechToText` / `LanguageModel` / `TtsEngine` engines.
- **`sample-app`** — reference control panel, foreground-service pattern, and
  Groq/Claude engine adapters.
- JitPack publishing for all three library modules; byte-level protocol test
  suite (`./gradlew testDebugUnitTest`).
