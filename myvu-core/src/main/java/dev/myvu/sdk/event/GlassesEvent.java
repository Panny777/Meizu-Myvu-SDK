package dev.myvu.sdk.event;

import org.json.JSONObject;

/**
 * A glasses-initiated event surfaced through
 * {@code MyvuClient.Listener.onEvent}.
 *
 * Everything the glasses send that is not consumed internally (ACKs, audio
 * frames, protocol replies the SDK answers itself) is surfaced either as a
 * typed subtype here or -- when the shape is not yet catalogued -- as
 * {@link Unknown}. The raw body of every non-audio message additionally
 * reaches {@code Listener.onRawInbound}.
 */
public abstract class GlassesEvent {

    GlassesEvent() {}

    /**
     * The AI hardware button (code 3) or wake word (code 7) fired.
     *
     * A {@code payload} with {@code control:0} is the button RELEASE / page
     * close: it arrives moments after the press and must not abort a
     * conversation turn already in flight -- consumers should treat it as
     * "end at the next turn boundary" (the myvu-ai module does).
     */
    public static final class AiTrigger extends GlassesEvent {
        public final int code;
        /** May be null; carries e.g. the "control" field. */
        public final JSONObject payload;

        public AiTrigger(int code, JSONObject payload) {
            this.code = code;
            this.payload = payload;
        }
    }

    /**
     * The glasses asked for a fresh weather push ({@code syncWeather}).
     *
     * They re-ask periodically and whenever their panel is opened. Answer it by
     * sending a new {@code Weather} payload -- the myvu-weather module's
     * WeatherSync does this for you.
     */
    public static final class WeatherRequested extends GlassesEvent {
    }

    /** An asynchronous reply to a {@code query(subAction)} call. */
    public static final class QueryReply extends GlassesEvent {
        public final String subAction;
        public final JSONObject data;

        public QueryReply(String subAction, JSONObject data) {
            this.subAction = subAction;
            this.data = data;
        }
    }

    /** An inbound JSON object the SDK does not (yet) parse into a type. */
    public static final class Unknown extends GlassesEvent {
        public final String rawJson;

        public Unknown(String rawJson) {
            this.rawJson = rawJson;
        }
    }
}
