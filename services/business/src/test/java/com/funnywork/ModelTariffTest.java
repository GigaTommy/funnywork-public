package com.funnywork;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class ModelTariffTest {
    long at(String value) { return Instant.parse(value).toEpochMilli(); }
    @Test void peakBoundariesHolidaysAndMakeupWeekends() {
        assertEquals("OFF_PEAK", ModelTariff.flash(at("2026-10-09T00:59:59Z")).period());
        assertEquals("PEAK", ModelTariff.flash(at("2026-10-09T01:00:00Z")).period());
        assertEquals("OFF_PEAK", ModelTariff.flash(at("2026-10-09T04:00:00Z")).period());
        assertEquals("PEAK", ModelTariff.flash(at("2026-10-09T06:00:00Z")).period());
        assertEquals("OFF_PEAK", ModelTariff.flash(at("2026-10-09T10:00:00Z")).period());
        assertEquals("OFF_PEAK", ModelTariff.flash(at("2026-10-01T01:00:00Z")).period());
        assertEquals("OFF_PEAK", ModelTariff.flash(at("2026-10-10T01:00:00Z")).period());
        assertThrows(ApiException.class, () -> ModelTariff.flash(at("2027-01-04T01:00:00Z")));
    }
    @Test void auditedPptUsageCostsAboutTwentyFourCents() {
        var rates=ModelTariff.flash(at("2026-10-09T15:10:00Z"));
        assertEquals(239748, ModelBudgets.cost(2171407,2107008,33302,rates.input(),rates.cached(),rates.output()));
        assertEquals(479495, ModelBudgets.cost(2171407,2107008,33302,2000000,40000,8000000));
        assertThrows(ArithmeticException.class, () -> ModelBudgets.cost(1,2,1,1,1,1));
    }
    @Test void tariffNeverAppliesToAProxyOrDifferentModel() {
        assertTrue(ModelTariff.directFlash("deepseek-flash","https://api.deepseek.com/anthropic"));
        assertFalse(ModelTariff.directFlash("deepseek-v4-pro","https://api.deepseek.com"));
        assertFalse(ModelTariff.directFlash("deepseek-flash","https://api.deepseek.com.example.com"));
        assertFalse(ModelTariff.directFlash("deepseek-flash","http://api.deepseek.com"));
    }
}
