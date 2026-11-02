package com.funnywork;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MobileModelThinkingTest {
    @Test void everyPublishedDeepSeekLevelUsesItsProtocolField() {
        for(String model:List.of("deepseek-flash","deepseek-v4-pro"))
            for(String protocol:List.of("MESSAGES","CHAT_COMPLETIONS"))
                for(String effort:MobileModelThinking.LEVELS) {
                    var route=Map.<String,Object>of("model",model,"protocol",protocol,"capabilities",Map.of("reasoning",true));
                    var body=new LinkedHashMap<String,Object>();MobileModelThinking.apply(body,route,effort);
                    assertEquals(Map.of("type",effort.equals("off")?"disabled":"enabled"),body.get("thinking"));
                    assertEquals(!effort.equals("off")&&protocol.equals("MESSAGES"),body.containsKey("output_config"));
                    assertEquals(!effort.equals("off")&&protocol.equals("CHAT_COMPLETIONS"),body.containsKey("reasoning_effort"));
                    if(!effort.equals("off"))assertEquals(protocol.equals("MESSAGES")?Map.of("effort",effort):effort,body.get(protocol.equals("MESSAGES")?"output_config":"reasoning_effort"));
                }
    }
    @Test void missingSelectionPreservesLegacyRequestsAndUnsupportedChoicesAreRejected() {
        var route=Map.<String,Object>of("model","qwen","protocol","CHAT_COMPLETIONS","capabilities",Map.of("reasoning",true));
        var body=new LinkedHashMap<String,Object>();MobileModelThinking.apply(body,route,null);assertTrue(body.isEmpty());
        assertThrows(ApiException.class,()->MobileModelThinking.apply(body,route,"high"));
        var disabled=Map.<String,Object>of("model","deepseek-flash","protocol","MESSAGES","capabilities",Map.of("reasoning",false));
        assertThrows(ApiException.class,()->MobileModelThinking.apply(body,disabled,"high"));
    }
}
