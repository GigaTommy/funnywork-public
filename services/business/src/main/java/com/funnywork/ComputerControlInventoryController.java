package com.funnywork;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/computer-controls")
public class ComputerControlInventoryController {
    private final ComputerControlInventory inventory;
    public ComputerControlInventoryController(ComputerControlInventory inventory){this.inventory=inventory;}
    @GetMapping public Map<String,Object> page(@AuthenticationPrincipal Identity actor,
        @RequestParam(defaultValue="") String nodeId,@RequestParam(defaultValue="") String after,
        @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="false") boolean expiredOnly){
        return inventory.page(actor,nodeId,after,limit,expiredOnly);
    }
}
