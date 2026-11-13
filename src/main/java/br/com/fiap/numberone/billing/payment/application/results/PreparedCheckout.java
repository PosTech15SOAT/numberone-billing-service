package br.com.fiap.numberone.billing.payment.application.results;

import br.com.fiap.numberone.billing.payment.domain.entities.Charge;

import java.util.UUID;

/** Snapshot confirmado; acquired pertence somente à invocação que criou a reserva. */
public record PreparedCheckout(Charge charge, UUID attemptId, boolean acquired) {
}
