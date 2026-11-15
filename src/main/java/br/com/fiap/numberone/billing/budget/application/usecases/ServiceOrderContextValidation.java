package br.com.fiap.numberone.billing.budget.application.usecases;

import br.com.fiap.numberone.billing.budget.domain.entities.Budget;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ObsoleteBudgetRevisionException;
import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;

import java.util.UUID;

final class ServiceOrderContextValidation {

    private ServiceOrderContextValidation() { }

    static void requireQuotedComposition(Budget budget, ServiceOrderQuotationContext current) {
        var composition = budget.getComposition();
        if (composition != null && composition.compositionVersion() != null
                && !composition.compositionVersion().equals(current.compositionVersion())) {
            throw new ObsoleteBudgetRevisionException();
        }
    }

    static void requireMatching(ServiceOrderQuotationContext context, UUID serviceOrderId) {
        if (context == null || !serviceOrderId.equals(context.serviceOrderId())) {
            throw new InvalidBudgetDataException("O contexto técnico não corresponde à ordem de serviço solicitada");
        }
    }
}
