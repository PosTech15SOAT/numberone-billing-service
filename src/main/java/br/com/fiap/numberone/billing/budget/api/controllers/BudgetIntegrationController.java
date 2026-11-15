package br.com.fiap.numberone.billing.budget.api.controllers;

import br.com.fiap.numberone.billing.budget.api.dtos.responses.BudgetHistoryResponse;
import br.com.fiap.numberone.billing.budget.api.mappers.BudgetApiMapper;
import br.com.fiap.numberone.billing.budget.application.usecases.GetBudgetHistoryUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/ordens-servico")
public class BudgetIntegrationController {

    private final GetBudgetHistoryUseCase getBudgetHistoryUseCase;
    private final BudgetApiMapper mapper;

    public BudgetIntegrationController(GetBudgetHistoryUseCase getBudgetHistoryUseCase, BudgetApiMapper mapper) {
        this.getBudgetHistoryUseCase = getBudgetHistoryUseCase;
        this.mapper = mapper;
    }

    @GetMapping("/{serviceOrderId}/orcamentos")
    public ResponseEntity<BudgetHistoryResponse> getBudgetHistory(@PathVariable UUID serviceOrderId) {
        return ResponseEntity.ok(mapper.toHistoryResponse(getBudgetHistoryUseCase.execute(serviceOrderId)));
    }
}
