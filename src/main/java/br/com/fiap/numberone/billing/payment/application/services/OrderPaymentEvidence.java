package br.com.fiap.numberone.billing.payment.application.services;

import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;
import br.com.fiap.numberone.billing.payment.application.results.ProviderOrderSnapshot;
import br.com.fiap.numberone.billing.payment.application.results.ProviderPaymentSnapshot;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;

import java.math.BigDecimal;

/** Política conservadora do MVP, compartilhada pela observação e pela revalidação sob lock. */
public final class OrderPaymentEvidence {
    private OrderPaymentEvidence() { }

    public static OrderObservation observe(Charge charge, String providerOrderId, ProviderOrderSnapshot snapshot) {
        if (!isValidIdentifier(providerOrderId)) throw inconsistent();
        PaymentAttempt attempt = charge.getAttempts().stream()
                .filter(candidate -> providerOrderId.equals(candidate.getProviderOrderId()))
                .findFirst().orElseThrow(OrderPaymentEvidence::inconsistent);
        if (!matchesOrderIdentity(providerOrderId, attempt, snapshot)
                || !matchesChargeCurrency(charge, snapshot)
                || !matchesChargeAmount(charge, snapshot)
                || !matchesPaymentIdentity(attempt, snapshot)) throw inconsistent();
        charge.requirePayableAmount();
        OrderObservation.Situation situation = switch (snapshot.status() == null ? "" : snapshot.status()) {
            case "created", "processing", "action_required" -> OrderObservation.Situation.PENDING;
            case "failed" -> OrderObservation.Situation.REJECTED;
            case "canceled" -> OrderObservation.Situation.CANCELED;
            case "refunded" -> OrderObservation.Situation.REFUNDED;
            case "processed" -> accreditedInFull(snapshot) ? OrderObservation.Situation.APPROVED : OrderObservation.Situation.INDETERMINATE;
            default -> OrderObservation.Situation.INDETERMINATE;
        };
        return new OrderObservation(providerOrderId, charge.getChargeId(), attempt.getAttemptId(), charge.getServiceOrderId(), situation, snapshot);
    }

    public static String requireApproved(Charge charge, OrderObservation observation) {
        if (observation == null || observation.situation() != OrderObservation.Situation.APPROVED
                || !charge.getChargeId().equals(observation.chargeId())
                || !charge.getServiceOrderId().equals(observation.serviceOrderId())) throw inconsistent();
        OrderObservation checked = observe(charge, observation.providerOrderId(), observation.snapshot());
        if (!checked.attemptId().equals(observation.attemptId()) || checked.situation() != OrderObservation.Situation.APPROVED) throw inconsistent();
        return checked.snapshot().payments().getFirst().providerPaymentId();
    }

    private static boolean matchesOrderIdentity(String providerOrderId, PaymentAttempt attempt, ProviderOrderSnapshot snapshot) {
        return snapshot != null && providerOrderId.equals(snapshot.providerOrderId())
                && attempt.getAttemptId().toString().equals(snapshot.externalReference());
    }

    private static boolean matchesChargeCurrency(Charge charge, ProviderOrderSnapshot snapshot) {
        return "BRL".equals(snapshot.currency()) && charge.getCurrency().getCurrencyCode().equals(snapshot.currency());
    }

    private static boolean matchesChargeAmount(Charge charge, ProviderOrderSnapshot snapshot) {
        return snapshot.totalAmount() != null && charge.getAmount().compareTo(snapshot.totalAmount()) == 0;
    }

    private static boolean matchesPaymentIdentity(PaymentAttempt attempt, ProviderOrderSnapshot snapshot) {
        return snapshot.payments().stream().noneMatch(payment -> payment == null || !isValidIdentifier(payment.providerPaymentId()))
                && (attempt.getProviderPaymentId() == null || snapshot.payments().stream()
                    .anyMatch(payment -> attempt.getProviderPaymentId().equals(payment.providerPaymentId())));
    }

    private static boolean accreditedInFull(ProviderOrderSnapshot order) {
        if (!isAccreditedOrder(order)
                || !isValidMoney(order.totalAmount()) || !covers(order.totalPaidAmount(), order.totalAmount())
                || order.payments().size() != 1) return false;
        ProviderPaymentSnapshot payment = order.payments().getFirst();
        return isAccreditedPayment(payment)
                && equalMoney(payment.amount(), order.totalAmount()) && covers(payment.paidAmount(), payment.amount())
                // Uma transação: os dois totais pagos precisam ser coerentes. Excedente não é classificado como juros.
                && equalMoney(payment.paidAmount(), order.totalPaidAmount());
    }

    private static boolean isAccreditedOrder(ProviderOrderSnapshot order) {
        return "accredited".equals(order.statusDetail()) && !order.hasRefundsOrChargebacks();
    }

    private static boolean isAccreditedPayment(ProviderPaymentSnapshot payment) {
        return "processed".equals(payment.status()) && "accredited".equals(payment.statusDetail());
    }

    private static boolean covers(BigDecimal paid, BigDecimal principal) {
        return isValidMoney(paid) && paid.compareTo(principal) >= 0;
    }

    private static boolean equalMoney(BigDecimal amount, BigDecimal expected) {
        return isValidMoney(amount) && amount.compareTo(expected) == 0;
    }
    private static boolean isValidMoney(BigDecimal amount) { return amount != null && amount.signum() > 0 && amount.scale() <= 2; }
    private static boolean isValidIdentifier(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,255}"); }
    private static OrderLookupException inconsistent() { return new OrderLookupException(OrderLookupException.Kind.INVALID_RESPONSE, null, null); }
}
