package br.com.fiap.numberone.billing.budget.api.dtos.responses;

import java.util.List;

public record ErrorResponse(int status, String message, List<String> errors) {
}
