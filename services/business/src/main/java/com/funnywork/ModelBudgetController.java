package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ModelBudgetController {
    private final ModelBudgets budgets;
    public ModelBudgetController(ModelBudgets budgets){this.budgets=budgets;}
    @GetMapping("/api/account/inference/budget") public Object mine(@AuthenticationPrincipal Identity actor){return budgets.mine(actor);}
    @GetMapping("/api/v1/model-middleware/budgets/global") public Object global(@AuthenticationPrincipal Identity actor){return budgets.global(actor);}
    @PutMapping("/api/v1/model-middleware/budgets/global") public Object global(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ModelBudgets.GlobalPolicy body){return budgets.saveGlobal(actor,body);}
    @GetMapping("/api/v1/model-middleware/budgets/deployments") public Object deployments(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return budgets.pricingDeployments(actor,after,limit);}
    @GetMapping("/api/v1/model-middleware/budgets/users") public Object users(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return budgets.members(actor,after,limit);}
    @GetMapping("/api/v1/model-middleware/budgets/users/{owner}") public Object user(@AuthenticationPrincipal Identity actor,@PathVariable String owner){return budgets.readUser(actor,owner);}
    @PutMapping("/api/v1/model-middleware/budgets/users/{owner}") public Object user(@AuthenticationPrincipal Identity actor,@PathVariable String owner,@Valid @RequestBody ModelBudgets.UserPolicy body){return budgets.saveUser(actor,owner,body);}
    @GetMapping("/api/v1/model-middleware/budgets/prices/{deployment}") public Object price(@AuthenticationPrincipal Identity actor,@PathVariable String deployment){return budgets.price(actor,deployment);}
    @PutMapping("/api/v1/model-middleware/budgets/prices/{deployment}") public Object price(@AuthenticationPrincipal Identity actor,@PathVariable String deployment,@Valid @RequestBody ModelBudgets.Price body){return budgets.savePrice(actor,deployment,body);}
    @PostMapping("/api/v1/model-middleware/budgets/supplier-corrections") public Object correct(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ModelBudgets.SupplierCorrection body){return budgets.correctSupplierCharges(actor,body);}
    @PostMapping("/api/account/inference/budget/claim") public Object claim(@AuthenticationPrincipal Identity actor){return budgets.claim(actor);}
    @GetMapping("/api/v1/model-middleware/budgets/promotion") public Object promotion(@AuthenticationPrincipal Identity actor){return budgets.promotion(actor);}
    @PutMapping("/api/v1/model-middleware/budgets/promotion") public Object promotion(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ModelBudgets.PromotionPolicy body){return budgets.savePromotion(actor,body);}
    @PostMapping("/api/v1/model-middleware/budgets/users/{owner}/topups") public Object topup(@AuthenticationPrincipal Identity actor,@PathVariable String owner,@Valid @RequestBody ModelBudgets.Topup body){return budgets.topup(actor,owner,body);}
    @GetMapping("/api/v1/model-middleware/budgets/users/{owner}/topups/{requestId}") public Object topupReceipt(@AuthenticationPrincipal Identity actor,@PathVariable String owner,@PathVariable String requestId){return budgets.topupReceipt(actor,owner,requestId);}
}
