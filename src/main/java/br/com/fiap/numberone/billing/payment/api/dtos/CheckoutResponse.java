package br.com.fiap.numberone.billing.payment.api.dtos;

import java.util.UUID;

public record CheckoutResponse(
        UUID idCobranca,
        UUID idTentativa,
        String idOrdemProvedor,
        String urlCheckout,
        String status,
        String situacao) {
    public boolean processing() {
        return situacao.equals("EM_PROCESSAMENTO") || situacao.equals("RESULTADO_DESCONHECIDO");
    }

    @Override
    public String toString() {
        return "CheckoutResponse[idCobranca=" + idCobranca + ", idTentativa=" + idTentativa + ", situacao=" + situacao + "]";
    }
}
