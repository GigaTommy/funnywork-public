package com.funnywork;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/account/chats")
public class MobileChatsController {
 private final MobileChats chats;private final ModelInferenceProxy proxy;private final MobileChatHistory history;
 public MobileChatsController(MobileChats chats,ModelInferenceProxy proxy,MobileChatHistory history){this.chats=chats;this.proxy=proxy;this.history=history;}
 @GetMapping("/models") public Object models(@AuthenticationPrincipal Identity actor){return Map.of("items",chats.catalog(actor));}
 @GetMapping public Object list(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="false") boolean history,@RequestParam(defaultValue="false") boolean archived,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="") String after){return history?this.history.page(actor,archived,query,after):Map.of("items",chats.list(actor));}
 @PostMapping("/{id}/history") public Object updateHistory(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MobileChatHistory.Update body){return history.update(actor,id,body);}
 @GetMapping("/{id}/history") public Object historyMetadata(@AuthenticationPrincipal Identity actor,@PathVariable String id){return history.read(actor,id);}
 @GetMapping("/{id}") public Object snapshot(@AuthenticationPrincipal Identity actor,@PathVariable String id){return chats.snapshot(actor,id);}
 @PostMapping("/{id}/turns") public Object send(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MobileChats.Send body){return chats.send(actor,id,body);}
 @PostMapping("/{id}/turns/{turnId}/cancel") public Object cancel(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String turnId){chats.cancel(actor,id,turnId);return Map.of("ok",true);}
 @PostMapping("/{id}/turns/{turnId}/execute") public void execute(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String turnId,HttpServletRequest request,HttpServletResponse response){proxy.execute(actor,chats.ticket(actor,id,turnId),chats.payload(actor,id,turnId),request,response,chats.observer(turnId));}
}
