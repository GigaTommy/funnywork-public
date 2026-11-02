package com.funnywork;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.*;
@RestController
@RequestMapping("/api/account/chats/{chat}/desktop-tasks")
public class MobileChatResultLinksController {
    private final MobileChatResultLinks links;
    public MobileChatResultLinksController(MobileChatResultLinks links){this.links=links;}
    @GetMapping public List<Map<String,Object>> list(@AuthenticationPrincipal Identity actor,@PathVariable String chat){return links.list(actor,chat);}
    @PostMapping public Map<String,Object> link(@AuthenticationPrincipal Identity actor,@PathVariable String chat,@RequestBody MobileChatResultLinks.Link body){links.link(actor,chat,body);return Map.of("ok",true);}
}
