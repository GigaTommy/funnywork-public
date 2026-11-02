package com.funnywork;

import java.net.URI;
import java.time.*;
import java.util.Set;

/** Versioned supplier tariff; rates are micro-CNY per million tokens. */
final class ModelTariff {
    static final String DEEPSEEK_FLASH = "DEEPSEEK_FLASH_CNY_20260910";
    // State Council 2026 holiday notice. DeepSeek weekends stay off-peak even on makeup workdays.
    private static final Set<String> HOLIDAYS = Set.of(
        "2026-01-01","2026-01-02","2026-01-03",
        "2026-02-15","2026-02-16","2026-02-17","2026-02-18","2026-02-19","2026-02-20","2026-02-21","2026-02-22","2026-02-23",
        "2026-04-04","2026-04-05","2026-04-06",
        "2026-05-01","2026-05-02","2026-05-03","2026-05-04","2026-05-05",
        "2026-06-19","2026-06-20","2026-06-21",
        "2026-09-25","2026-09-26","2026-09-27",
        "2026-10-01","2026-10-02","2026-10-03","2026-10-04","2026-10-05","2026-10-06","2026-10-07");
    record Rates(long input, long cached, long output, String period) {}
    static boolean directFlash(String model, String baseUrl) {
        if (!"deepseek-flash".equals(model)) return false;
        try {
            URI u = URI.create(baseUrl);
            return "https".equals(u.getScheme()) && "api.deepseek.com".equals(u.getHost())
                && (u.getPort() == -1 || u.getPort() == 443) && u.getUserInfo() == null
                && u.getQuery() == null && u.getFragment() == null
                && Set.of("", "/", "/v1", "/v1/", "/anthropic", "/anthropic/").contains(u.getPath());
        } catch (IllegalArgumentException e) { return false; }
    }
    static Rates flash(long timestamp) {
        var t = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("Asia/Shanghai"));
        // Do not invent a holiday calendar or silently charge future peak rates.
        if (t.getYear() != 2026 || t.toLocalDate().isBefore(LocalDate.of(2026, 9, 10)))
            throw new ApiException(503, "MODEL_TARIFF_REVIEW_REQUIRED", "Supplier tariff/calendar requires review");
        int h = t.getHour();
        boolean peak = t.getDayOfWeek().getValue() <= 5 && !HOLIDAYS.contains(t.toLocalDate().toString())
            && (h >= 9 && h < 12 || h >= 14 && h < 18);
        return peak ? new Rates(2000000, 40000, 8000000, "PEAK")
            : new Rates(1000000, 20000, 4000000, "OFF_PEAK");
    }
    private ModelTariff() {}
}
