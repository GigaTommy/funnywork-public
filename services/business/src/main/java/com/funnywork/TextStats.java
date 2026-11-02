package com.funnywork;

import java.util.Map;

/** A deliberately deterministic first executor; this is not a model or a business connector. */
public final class TextStats {
    public static final String CAPABILITY="text.stats.v1";
    private TextStats() {}
    public static Map<String,Object> compute(String text) {
        return Map.of("sha256",AuthService.hash(text),"codePoints",text.codePointCount(0,text.length()),"lines",text.isEmpty()?0:text.split("\\R",-1).length);
    }
}
