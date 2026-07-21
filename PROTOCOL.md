# MYVU wire protocol

Reverse-engineered from packet captures of the Meizu MYVU / Star Air (XGA010C)
and its official app. This documents what the SDK implements; it is not an
official spec and may drift with firmware updates.

## The two-link architecture

The glasses require **two Bluetooth links at once**, and the order is mandatory:

1. **BLE first.** The glasses' classic radio does not answer a page until BLE
   has woken them — a cold `createBond` just times out (~13 s, no ACL). BLE
   carries the ECDH bond and is the **only** channel that announces where the
   app relay lives (the per-session RFCOMM UUID).
2. **Classic Bluetooth (RFCOMM) second**, to that per-session UUID. This is the
   link that actually carries app traffic. The fixed "channel 13" seen in early
   captures answers the handshake but never ACKs an app message.

Each link runs its own independent RunAsOne session. `MyvuClient` owns both and
prefers the relay once it is ready, falling back to BLE (loudly) otherwise.

## BLE GATT

`makeUUID(i)` = `0000{i:04x}-0000-1000-8000-00805f9b34fb`.

- **Service** `0x0BD1` (`makeUUID(3025)`, "StarryNet").
- **Characteristics** (Air family, each write-without-response + notify):
  - `0x2020` internal — link / pairing (version negotiation + ECDH)
  - `0x2021` external — application data (relay frames)
  - `0x2022` urgent — heartbeat
  - `0x2023` glass write
  - V2 units use `0x2010 / 0x2011 / 0x2012`. CCCD `0x2902`.
- **MTU** negotiated to 517; fragment unit `DMTU = MTU − 5`.
- **Heartbeat**: `00 00 09 10 00` written to the urgent characteristic every
  **3 s**; without it the glasses' watchdog drops the link.

### BLE packet transport

Little-endian. Every packet starts with a 2-byte sequence `sn`:
- `sn == 0` → control packet: `type` at `[2]`, `pkgType`/command at `[3]`.
- `sn != 0` → data fragment `sn`, payload from `[2:]`.

Control types: 0 CTR, 1 ACK, 2 SINGLE, 3 SINGLE_ACK, 4 MNG, 5 MNG_ACK,
6 FAST_CTR, 7 FAST_ACK, 8 MIX_CTR, 9 SINGLE_NO_ACK.
Package types: 0 COMMON_DATA (app), 16 STARRY_DATA (pairing), 17
STARRY_DATA_INIT (first negotiation). ACK statuses: 0 SUCCESS, 1 READY, 2 BUSY,
3 TIMEOUT, 4 CANCEL, 5 SYNC.

## Pairing (ECDH bond) — internal characteristic

1. **Version negotiation** — FAST_CTR, pkgType 17, JSON
   `{"i":ownIdHex,"v":3,"e":5,"m":512,"b":2,"c":"9999"}`. The reply's `"e"`
   picks the AES mode: 1 = CBC/PKCS5, 2 = CTR/NoPadding, else GCM.
2. **WRITE_SWITCH_KEY (cmd 11)** — our SPKI public key + 6-byte MAC.
3. **← WRITE_SWITCH_KEY** — glasses' SPKI public key ‖ 16-byte IV, plus
   `AES(their DeviceInfo)`. We derive the shared secret and decrypt DeviceInfo.
4. **WRITE_SWITCH_INFO (cmd 13)** — our **double-encrypted** DeviceInfo. Bond
   established.

Crypto: EC **P-256 (secp256r1)**; the raw 32-byte X coordinate is used directly
as the AES-256 key (no KDF). IV = first 16 ASCII chars of a UUID4. Public keys
are X.509 SubjectPublicKeyInfo DER (91 bytes). **No certificate or signature
check anywhere** — any correct speaker is accepted.

`device_id = dealDeviceId(mac)` = reverse the 6 MAC bytes **and** bitwise-NOT
each (verified: `7ca375d094f1 → 0e6b2f8a5c83`).

### LinkProtocol

`LinkProtocol { 1: device_id (bytes), 2: cmd (varint), 3: data (bytes) }`.
Commands: INIT 0, WRITE_SWITCH_KEY 11, WRITE_SWITCH_INFO 13, and the relay
lifecycle — **SPP_SERVER_UUID_SYNC 70**, SPP_SERVER_REQUEST_CONNECT 71,
STATE_OPEN 72, STATE_CLOSE 73.

`DeviceInfo { 1:btMac, 2:companyId, 3:categoryId, 4:modelId, 5:name, 6:battery,
7:btStatus }`. `btStatus`: DEFAULT 0, BOND 1, … CONNECTED_ACL 4, CONNECTED_HFP
5, CONNECTED_A2DP 6, …

## RunAsOne session (ability auth) — external characteristic, plaintext

After the bond, the glasses stay on "Open MYVU AR App" until they get the
ability handshake. Two phases, **both required**:

- **AUTH (type 0)** — `build_ability_message`. AuthBean JSON advertises
  `["abilityRelay","abilityRelayBypass","abilityAir","abilityShare"]`,
  `version:"2.40.51"`, `weight:233333`, airMapping to
  `com.upuphone.star.launcher`.
- **AUTH_SUCCESS (type 12)** — without it the glasses ACK data but never engage
  the app layer.

StreamReq/AUTH class byte is `0x02`.

## RunAsOne relay (SuperMessage) — the layer that flips to "connected"

**TlvBox** is big-endian: `[tag:1][len:2 BE][value]`, ints fixed-width BE,
nested boxes serialized recursively. Tags: 100 MSG_TYPE, 101 MSG_ID, 103
NEED_CALLBACK, 105 MSG_BODY, 109 APP_UNITE_CODE, 112 CATEGORY, 113 PAYLOAD.
msgType: 3 SEND (data), 4 SEND_SUCCESS (ack), 6 OPEN_SUCCESS.

One frame:

```
0x01                                  # FRAME_PREFIX
TlvBox{ 112 category=3,
        113 payload = TlvBox{
            100 msgType, 101 msgId, 103 needCallback,
            109 appUniteCode, 105 msgBody } }
```

**Sequencing is load-bearing.** `msgId` starts at 1 and increments with no
gaps; the glasses track the last received id and buffer (never deliver) anything
that looks like an out-of-order jump — which is why replaying a capture's stale
high msgIds fails.

### StMessage envelope

An action is wrapped: `StMessage { 2:sourcePkg, 3:targetPkg, 4:action_json,
6:msgId }` (msgId base 5001). Default src/dst `com.upuphone.star.launcher`.
Inbound mic audio is binary field 5 (see code:109).

## Init burst

Even after the bond + ability handshake, the relay dispatcher stays half-asleep
until it sees a clean sequence of opening app messages. The SDK replays a
captured burst (`assets/myvu/captured_init.txt`) with fresh 1..N msgIds, paced
200 ms apart, dropping captured ACKs and stale-state messages
(`SyncOffSetTime`, `sync_clone_data`). Required on **every** transport. Override
the source via `MyvuConfig.initBurstSource`.

## Classic Bluetooth / RFCOMM relay

No app-layer crypto — BR/EDR link encryption covers it; it goes straight into
the ability handshake. Frame (confirmed byte-for-byte):

```
ea ca 93 53          # MAGIC
<length : 4 bytes BE>
00 02                # PREFIX
<payload>            # the same relay/StreamReq bytes as BLE, unfragmented
```

The relay channel is a **per-session random RFCOMM UUID**, synced over BLE via
cmd 70 and regenerated every session. It is resolved by SDP
(`createRfcommSocketToServiceRecord`). The glasses drop the relay when idle and
re-request via cmd 71; `RelaySupervisor` reconnects in place with a fresh
sequencer and replays the init burst.

## AI assistant (code map)

The glasses stream their mic continuously as **code:109** Opus frames (field 5 =
`[2-byte BE length][Opus frame]`, SILK wideband 16 kHz). Ordering the glasses
enforce with real timers:

```
code:4            session ack (arms an 8s timeout)
code:104 type:1   VAD start (first audio; the only thing that clears the timeout)
code:104 type:2   VAD end
code:101 type:0   growing caption partials
code:101 type:1   final caption
code:106 (7)      VR_PROCESSION — only AFTER the final caption
code:5            answer card / TTS text
code:6 type:1/2   TTS play start / end
code:107          idle / end of turn
```

Triggers from the glasses: **code:3 control:1** = AI button, **code:7** = wake
word ("Hey Aicy"); **control:0** = button release / page close (ends at the next
turn boundary, never mid-turn). `myvu-ai` implements this whole state machine;
recognition and answering are delegated to `SpeechToText` / `LanguageModel` /
`TtsEngine`.

## Deliberately not implemented

`do_recovery` (factory reset), `system_account`, `system_glass_active` /
`req_active_state`, `user_feedback` — dangerous or useless, omitted on purpose.
