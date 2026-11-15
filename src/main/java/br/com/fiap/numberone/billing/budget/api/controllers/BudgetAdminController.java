package br.com.fiap.numberone.billing.budget.api.controllers;

import br.com.fiap.numberone.billing.budget.api.dtos.requests.CreateBudgetRequest;
import br.com.fiap.numberone.billing.budget.api.dtos.responses.BudgetDetailsResponse;
import br.com.fiap.numberone.billing.budget.api.dtos.responses.BudgetResponse;
import br.com.fiap.numberone.billing.budget.api.mappers.BudgetApiMapper;
import br.com.fiap.numberone.billing.budget.application.usecases.ApproveBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.CreateBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RegisterBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.RejectBudgetUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.ResendBudgetApprovalUseCase;
import br.com.fiap.numberone.billing.budget.application.usecases.SendBudgetForApprovalUseCase;
import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class BudgetAdminController {

    private final CreateBudgetUseCase createBudgetUseCase;
    private final GetBudgetUseCase getBudgetUseCase;
    private final SendBudgetForApprovalUseCase sendBudgetForApprovalUseCase;
    private final ResendBudgetApprovalUseCase resendBudgetApprovalUseCase;
    private final ApproveBudgetUseCase approveBudgetUseCase;
    private final RejectBudgetUseCase rejectBudgetUseCase;
    private final RegisterBudgetApprovalUseCase registerBudgetApprovalUseCase;
    private final BudgetApiMapper mapper;

    public BudgetAdminController(CreateBudgetUseCase createBudgetUseCase, GetBudgetUseCase getBudgetUseCase,
                                 SendBudgetForApprovalUseCase sendBudgetForApprovalUseCase,
                                 ResendBudgetApprovalUseCase resendBudgetApprovalUseCase,
                                 ApproveBudgetUseCase approveBudgetUseCase, RejectBudgetUseCase rejectBudgetUseCase,
                                 RegisterBudgetApprovalUseCase registerBudgetApprovalUseCase, BudgetApiMapper mapper) {
        this.createBudgetUseCase = createBudgetUseCase;
        this.getBudgetUseCase = getBudgetUseCase;
        this.sendBudgetForApprovalUseCase = sendBudgetForApprovalUseCase;
        this.resendBudgetApprovalUseCase = resendBudgetApprovalUseCase;
        this.approveBudgetUseCase = approveBudgetUseCase;
        this.rejectBudgetUseCase = rejectBudgetUseCase;
        this.registerBudgetApprovalUseCase = registerBudgetApprovalUseCase;
        this.mapper = mapper;
    }

    @PostMapping("/ordens-servico/{serviceOrderId}/orcamentos")
    public ResponseEntity<BudgetResponse> createBudget(@PathVariable UUID serviceOrderId,
                                                     @Valid @RequestBody CreateBudgetRequest request) {
        Budget budget = createBudgetUseCase.execute(serviceOrderId, request.quotedAmount());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/admin/orcamentos-ordem-servico/{id}")
                .buildAndExpand(budget.getBudgetId()).toUri();
        return ResponseEntity.created(location).body(mapper.toResponse(budget));
    }

    @GetMapping("/orcamentos-ordem-servico/{id}")
    public ResponseEntity<BudgetDetailsResponse> getBudget(@PathVariable("id") UUID budgetId) {
        return ResponseEntity.ok(mapper.toDetailsResponse(getBudgetUseCase.execute(budgetId)));
    }

    @PatchMapping("/orcamentos-ordem-servico/{id}/solicitar-aprovacao")
    public ResponseEntity<BudgetResponse> sendForApproval(@PathVariable("id") UUID budgetId) {
        return ResponseEntity.ok(mapper.toResponse(sendBudgetForApprovalUseCase.execute(budgetId)));
    }

    @PostMapping("/orcamentos-ordem-servico/{id}/reenviar-aprovacao")
    public ResponseEntity<BudgetResponse> resend(@PathVariable("id") UUID budgetId) {
        return ResponseEntity.ok(mapper.toResponse(resendBudgetApprovalUseCase.execute(budgetId)));
    }

    @PatchMapping("/orcamentos-ordem-servico/{id}/aprovar")
    public ResponseEntity<BudgetResponse> approve(@PathVariable("id") UUID budgetId) {
        return ResponseEntity.ok(mapper.toResponse(approveBudgetUseCase.execute(budgetId)));
    }

    @PatchMapping("/orcamentos-ordem-servico/{id}/rejeitar")
    public ResponseEntity<BudgetResponse> reject(@PathVariable("id") UUID budgetId) {
        return ResponseEntity.ok(mapper.toResponse(rejectBudgetUseCase.execute(budgetId)));
    }

    @PostMapping("/orcamentos-ordem-servico/{id}/registrar-aprovacao")
    public ResponseEntity<BudgetResponse> registerApproval(@PathVariable("id") UUID budgetId) {
        return ResponseEntity.ok(mapper.toResponse(registerBudgetApprovalUseCase.execute(budgetId)));
    }
}
