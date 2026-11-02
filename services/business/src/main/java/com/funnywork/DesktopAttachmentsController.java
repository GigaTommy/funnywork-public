package com.funnywork;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.*;
@RestController @RequestMapping("/api/account/sync/sessions/{id}/attachments")
public class DesktopAttachmentsController {
 private final DesktopAttachments attachments;
 public DesktopAttachmentsController(DesktopAttachments attachments){this.attachments=attachments;}
 @GetMapping public Map<String,Object> manifest(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="") String after,@RequestParam(required=false) String hash){return attachments.manifest(actor,id,after,hash);}
 @PostMapping("/{hash}") public Map<String,Object> upload(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String hash,@RequestBody DesktopAttachments.Chunk body){return attachments.upload(actor,id,hash,body);}
 @GetMapping("/{hash}") public Map<String,Object> download(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String hash,@RequestParam(defaultValue="0") long offset){return attachments.download(actor,id,hash,offset);}
}
