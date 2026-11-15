package br.com.fiap.numberone.billing.payment.infrastructure.rest;

import br.com.fiap.numberone.billing.payment.application.results.CheckoutOrder;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.dtos.MercadoPagoOrderRequest;
import br.com.fiap.numberone.billing.payment.infrastructure.rest.dtos.MercadoPagoOrderResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MercadoPagoOrderMapper {
    private final JsonMapper jsonMapper = new JsonMapper();
    private static final Set<String> CHECKOUT_HOSTS = Set.of("www.mercadopago.com.br", "mercadopago.com.br");

    public PaymentAttempt requireAttempt(Charge charge, UUID attemptId) {
        if (charge == null || charge.getStatus() != ChargeStatus.OPEN || charge.getAmount().signum() <= 0
                || charge.getAmount().scale() > 2 || !"BRL".equals(charge.getCurrency().getCurrencyCode())) {
            throw new InvalidPaymentDataException("Criação de checkout exige cobrança aberta, positiva, em BRL e com até duas casas");
        }
        PaymentAttempt attempt = charge.getAttempts().stream().filter(candidate -> candidate.getAttemptId().equals(attemptId))
                .findFirst().orElseThrow(() -> new InvalidPaymentDataException("Tentativa não pertence à cobrança"));
        if (attempt.getStatus() != PaymentAttemptStatus.PENDING || attempt.getProviderOrderId() != null
                || attempt.getProviderPaymentId() != null) {
            throw new InvalidPaymentDataException("Criação de checkout exige intenção pendente sem operação externa conhecida");
        }
        return attempt;
    }

    public byte[] toRequest(Charge charge, PaymentAttempt attempt) {
        String amount = charge.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        MercadoPagoOrderRequest request = new MercadoPagoOrderRequest("online", "manual", amount,
                attempt.getAttemptId().toString(), List.of(new MercadoPagoOrderRequest.Item("Cobrança de ordem de serviço", 1, amount)));
        return jsonMapper.writeValueAsBytes(request);
    }

    public CheckoutOrder toResult(byte[] body, Charge charge, PaymentAttempt attempt) {
        try {
            JsonNode document = jsonMapper.readTree(body);
            if (document == null || !document.isObject()) {
                throw new IllegalArgumentException("Resposta de order deve ser um objeto JSON");
            }
            for (String field : List.of("id", "checkout_url", "status", "currency", "total_amount", "external_reference")) {
                JsonNode value = document.get(field);
                if (value != null && !value.isNull() && !value.isString()) {
                    throw new IllegalArgumentException("Campo de texto da resposta de order possui tipo inválido");
                }
            }
            MercadoPagoOrderResponse response = jsonMapper.treeToValue(document, MercadoPagoOrderResponse.class);
            if (response == null || !validText(response.id(), 255) || !validText(response.status(), 64)) {
                throw new IllegalArgumentException("Resposta de order sem identidade ou status válido");
            }
            URI checkoutUrl = checkoutUrl(response.checkoutUrl());
            if (response.currency() != null && !charge.getCurrency().getCurrencyCode().equals(response.currency())) {
                throw new IllegalArgumentException("Moeda da order diverge da cobrança");
            }
            if (response.totalAmount() != null && (!response.totalAmount().matches("[0-9]+(?:\\.[0-9]+)?")
                    || new BigDecimal(response.totalAmount()).compareTo(charge.getAmount()) != 0)) {
                throw new IllegalArgumentException("Valor da order diverge da cobrança");
            }
            if (response.externalReference() != null && !attempt.getAttemptId().toString().equals(response.externalReference())) {
                throw new IllegalArgumentException("Referência da order diverge da tentativa");
            }
            return new CheckoutOrder(response.id(), checkoutUrl, response.status());
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("JSON de resposta da order inválido", exception);
        }
    }

    private static URI checkoutUrl(String value) {
        if (value == null || value.length() > 2048) {
            throw new IllegalArgumentException("Resposta de order sem URL de checkout válida");
        }
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || !CHECKOUT_HOSTS.contains(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("URL de checkout não pertence ao destino HTTPS autorizado para BRL");
        }
        return uri;
    }

    private static boolean validText(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }
}
