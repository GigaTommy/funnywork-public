package com.funnywork;

import java.util.*;

/** Mobile choices and wire parameters for the published DeepSeek Flash/Pro models. */
final class MobileModelThinking {
    private MobileModelThinking() {}
    static final List<String> LEVELS=List.of("off","low","high","max");
    static boolean supported(String model,Object capabilities){
        return Set.of("deepseek-flash","deepseek-v4-pro").contains(model)
            && capabilities instanceof Map<?,?> values && Boolean.TRUE.equals(values.get("reasoning"));
    }
    static void apply(Map<String,Object> request,Map<String,Object> route,String effort){
        if(effort==null)return; // Older clients retain their previous provider default.
        if(!LEVELS.contains(effort)||!supported((String)route.get("model"),route.get("capabilities")))
            throw new ApiException(400,"CHAT_THINKING_UNAVAILABLE","Select an available thinking level");
        boolean enabled=!"off".equals(effort);
        request.put("thinking",Map.of("type",enabled?"enabled":"disabled"));
        if(enabled)request.put("MESSAGES".equals(route.get("protocol"))?"output_config":"reasoning_effort",
            "MESSAGES".equals(route.get("protocol"))?Map.of("effort",effort):effort);
    }
}
