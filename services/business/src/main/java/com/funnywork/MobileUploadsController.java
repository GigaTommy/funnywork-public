package com.funnywork;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.Map;

/** Account-owned staging, separate from the desktop's immutable history references. */
@RestController @RequestMapping("/api/account/sync/sessions/{id}/uploads")
public class MobileUploadsController {
 private final DesktopAttachments attachments;
 public MobileUploadsController(DesktopAttachments attachments){this.attachments=attachments;}
 @PostMapping("/{hash}") public Map<String,Object> prepare(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String hash,@RequestBody DesktopAttachments.MobileFile body){return attachments.prepareMobile(actor,id,hash,body);}
 @GetMapping("/{hash}") public Map<String,Object> status(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String hash){return attachments.mobileStatus(actor,id,hash);}
 @PostMapping("/{hash}/chunks") public Map<String,Object> upload(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String hash,@RequestBody DesktopAttachments.MobileChunk body){return attachments.uploadMobile(actor,id,hash,body);}
 @DeleteMapping("/{hash}") public void discard(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String hash){attachments.discardMobile(actor,id,hash);}
}
