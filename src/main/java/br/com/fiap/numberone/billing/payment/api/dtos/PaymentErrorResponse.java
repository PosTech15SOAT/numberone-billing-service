package br.com.fiap.numberone.billing.payment.api.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentErrorResponse(
        int status,
        String codigo,
        String mensagem,
        UUID idCobranca,
        UUID idTentativa) {
    public PaymentErrorResponse(int status, String codigo, String mensagem) {
        this(status, codigo, mensagem, null, null);
    }
}
