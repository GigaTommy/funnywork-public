package com.funnywork;

/** Request defaults are separate from model capabilities and monetary allowances. */
public final class ModelDefaults {
    private ModelDefaults() {}
    public static final int OUTPUT_TOKENS = 65536;
    public static final int MAX_OUTPUT_TOKENS = 262144;
    public static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    public static int contextTokens(String model) {
        return switch (model) {
            case "deepseek-flash", "deepseek-v4-pro" -> 1000000;
            default -> 131072;
        };
    }
}
