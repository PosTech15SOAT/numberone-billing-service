package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.payment.application.results.ProviderOrderSnapshot;
import br.com.fiap.numberone.billing.payment.application.results.ProviderPaymentSnapshot;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.dtos.MercadoPagoOrderDetailsResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MercadoPagoOrderDetailsMapper {
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public ProviderOrderSnapshot toSnapshot(String requestedId, byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            validateOrderFields(root);
            JsonNode transactions = validatedTransactions(root.get("transactions"));
            List<ProviderPaymentSnapshot> payments = toPayments(transactions);
            boolean adjustments = hasAdjustments(transactions);
            MercadoPagoOrderDetailsResponse data = json.treeToValue(root, MercadoPagoOrderDetailsResponse.class);
            validateOrder(requestedId, data);
            return new ProviderOrderSnapshot(data.id(), data.status(), data.statusDetail(), money(data.totalAmount(), true),
                    data.totalPaidAmount() == null ? null : money(data.totalPaidAmount(), false), data.currency(), data.externalReference(), payments, adjustments);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Resposta JSON da consulta de order inválida", exception);
        }
    }

    private void validateOrderFields(JsonNode root) {
        if (root == null || !root.isObject()) throw invalid();
        if (root.has("live_mode") && (!root.path("live_mode").isBoolean() || root.path("live_mode").asBoolean())) throw invalid();
        requireTexts(root, List.of("id", "type", "processing_mode", "status", "total_amount", "currency", "external_reference"), true);
        requireTexts(root, List.of("status_detail", "total_paid_amount"), false);
    }

    private JsonNode validatedTransactions(JsonNode transactions) {
        if (transactions == null || transactions.isNull()) return null;
        if (!transactions.isObject()) throw invalid();
        for (String field : List.of("payments", "refunds", "chargebacks")) {
            JsonNode entries = transactions.get(field);
            if (entries != null && !entries.isNull() && !entries.isArray()) throw invalid();
        }
        return transactions;
    }

    private List<ProviderPaymentSnapshot> toPayments(JsonNode transactions) {
        List<ProviderPaymentSnapshot> payments = new ArrayList<>();
        JsonNode entries = transactions == null ? null : transactions.get("payments");
        if (entries == null || !entries.isArray()) return payments;
        Set<String> paymentIds = new HashSet<>();
        for (JsonNode payment : entries) {
            payments.add(toPayment(payment, paymentIds));
        }
        return payments;
    }

    private ProviderPaymentSnapshot toPayment(JsonNode payment, Set<String> paymentIds) {
        if (!payment.isObject()) throw invalid();
        requireTexts(payment, List.of("id", "status", "status_detail", "amount"), true);
        requireTexts(payment, List.of("paid_amount"), false);
        MercadoPagoOrderDetailsResponse.Payment data = json.treeToValue(payment, MercadoPagoOrderDetailsResponse.Payment.class);
        if (!identifier(data.id()) || !paymentIds.add(data.id()) || !state(data.status()) || !state(data.statusDetail())) throw invalid();
        return new ProviderPaymentSnapshot(data.id(), data.status(), data.statusDetail(),
                money(data.amount(), true), data.paidAmount() == null ? null : money(data.paidAmount(), false));
    }

    private boolean hasAdjustments(JsonNode transactions) {
        if (transactions == null) return false;
        return hasEntries(transactions.get("refunds")) || hasEntries(transactions.get("chargebacks"));
    }

    private boolean hasEntries(JsonNode entries) {
        return entries != null && entries.isArray() && !entries.isEmpty();
    }

    private void validateOrder(String requestedId, MercadoPagoOrderDetailsResponse data) {
        if (!requestedId.equals(data.id()) || !identifier(data.id()) || !"online".equals(data.type())
                || !"manual".equals(data.processingMode()) || !state(data.status())
                || !(state(data.statusDetail()) || ("created".equals(data.status()) && data.statusDetail() == null))
                || !"BRL".equals(data.currency()) || !data.externalReference().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid();
    }

    private void requireTexts(JsonNode node, List<String> fields, boolean required) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if ((required && (value == null || value.isNull())) || (value != null && !value.isNull() && !value.isString())) throw invalid();
        }
    }

    private BigDecimal money(String value, boolean positive) {
        if (value == null || !value.matches("[0-9]{1,24}(?:\\.[0-9]{1,2})?")) throw invalid();
        BigDecimal amount = new BigDecimal(value);
        if (positive && amount.signum() <= 0) throw invalid();
        return amount;
    }

    private boolean state(String value) {
        return value != null && value.matches("[a-z][a-z0-9_]{0,63}");
    }

    private boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,255}");
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Dados da consulta oficial de order inválidos");
    }
}
