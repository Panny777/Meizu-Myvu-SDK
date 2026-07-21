package dev.myvu.sdk.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the WMO -> glasses icon-code mapping. Only 1, 2 and 7 are attested from
 * the official app's own mock payload; the rest follow the standard Chinese/CMA
 * numbering those three land on, so treat them as good guesses.
 */
public class WeatherCodesTest {

    /** The three icon codes actually attested in the official app's own payload. */
    @Test
    public void verifiedIconCodes() {
        assertEquals("1", WeatherCodes.of(2).iconCode);   // partly cloudy -> 多云
        assertEquals("2", WeatherCodes.of(3).iconCode);   // overcast      -> 阴
        assertEquals("7", WeatherCodes.of(61).iconCode);  // slight rain   -> 小雨
    }

    @Test
    public void unknownWmoCodeFallsBackToSomethingSafe() {
        WeatherCodes.Condition c = WeatherCodes.of(12345);
        assertEquals("1", c.iconCode); // cloudy: visually neutral
        assertTrue(c.text.length() > 0);
    }

    /** Every mapped code must carry a non-empty label for the lens. */
    @Test
    public void everyMappedCodeHasText() {
        int[] wmo = { 0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67,
                71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99 };
        for (int code : wmo) {
            WeatherCodes.Condition c = WeatherCodes.of(code);
            assertTrue("wmo " + code + " has no icon", c.iconCode.length() > 0);
            assertTrue("wmo " + code + " has no text", c.text.length() > 0);
        }
    }
}
