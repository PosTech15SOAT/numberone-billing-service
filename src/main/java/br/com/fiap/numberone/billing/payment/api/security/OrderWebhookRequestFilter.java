package br.com.fiap.numberone.billing.payment.api.security;

import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.infrastructure.config.MercadoPagoWebhookProperties;
import br.com.fiap.numberone.billing.payment.infrastructure.webhook.InvalidOrderNotificationException;
import br.com.fiap.numberone.billing.payment.infrastructure.webhook.MercadoPagoSignatureValidator;
import br.com.fiap.numberone.billing.payment.infrastructure.webhook.OrderNotificationMapper;
import br.com.fiap.numberone.billing.payment.infrastructure.webhook.OrderWebhookIngress;
import br.com.fiap.numberone.billing.infrastructure.observability.HttpObservationFilter;
import br.com.fiap.numberone.billing.infrastructure.observability.ObservationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

import static br.com.fiap.numberone.billing.payment.infrastructure.webhook.InvalidOrderNotificationException.Reason.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OrderWebhookRequestFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderWebhookRequestFilter.class);
    public static final String PATH = "/api/public/pagamentos/notificacoes/mercado-pago";
    private static final int MAX_NOTIFICATION_BYTES = 65536;
    private static final String PROVIDER_ORDER_ID_LOG_KEY = "provider_order.id";

    private final MercadoPagoWebhookProperties properties;
    private final MercadoPagoSignatureValidator signatureValidator;
    private final OrderNotificationMapper notificationMapper = new OrderNotificationMapper();

    public OrderWebhookRequestFilter(MercadoPagoWebhookProperties properties, MercadoPagoSignatureValidator signatureValidator) {
        this.properties = properties;
        this.signatureValidator = signatureValidator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        OrderWebhookIngress ingress;
        try {
            ingress = readVerified(request);
        } catch (RuntimeException failure) {
            ingress = new OrderWebhookIngress(null, failure);
            recordRejection(request, failure);
        }
        request.setAttribute(OrderWebhookIngress.ATTRIBUTE, ingress);
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(withoutSensitiveContent(request), response);
    }

    private HttpServletRequest withoutSensitiveContent(HttpServletRequest request) {
        return new HttpServletRequestWrapper(request) {
            @Override public String getQueryString() { return null; }
            @Override public String getParameter(String name) { return null; }
            @Override public String[] getParameterValues(String name) { return null; }
            @Override public Map<String, String[]> getParameterMap() { return Map.of(); }
            @Override public Enumeration<String> getParameterNames() { return Collections.emptyEnumeration(); }
            @Override public String getHeader(String name) { return null; }
            @Override public Enumeration<String> getHeaders(String name) { return Collections.emptyEnumeration(); }
            @Override public Enumeration<String> getHeaderNames() { return Collections.emptyEnumeration(); }
            @Override public String getContentType() { return null; }
            @Override public int getContentLength() { return 0; }
            @Override public long getContentLengthLong() { return 0; }
            @Override public BufferedReader getReader() { return new BufferedReader(new StringReader("")); }
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { return -1; }
                    @Override public boolean isFinished() { return true; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        try { listener.onAllDataRead(); }
                        catch (IOException failure) { throw new IllegalStateException("Falha de leitura da notificação", failure); }
                    }
                };
            }
        };
    }

    private void recordRejection(HttpServletRequest request, RuntimeException failure) {
        String code = rejectionCode(failure);
        request.setAttribute(HttpObservationFilter.ERROR, code);
        ObservationLog.safely(() -> LOGGER.atWarn().addKeyValue("event.code", "PAYMENT_WEBHOOK_REJECTED")
                .addKeyValue("error.code", code)
                .addKeyValue("operation.stage", request.getAttribute(HttpObservationFilter.STAGE))
                .addKeyValue("error.type", failure.getClass().getName()).log("Notificação rejeitada na entrada"));
    }

    private String rejectionCode(RuntimeException failure) {
        if (failure instanceof InvalidOrderNotificationException invalid) {
            return invalid.getReason().name();
        }
        if (failure instanceof OrderLookupException lookup && lookup.getKind() == OrderLookupException.Kind.BLOCKED) {
            return "WEBHOOK_DISABLED";
        }
        return "WEBHOOK_PROCESSING_FAILED";
    }

    private OrderWebhookIngress readVerified(HttpServletRequest request) {
        stage(request, "CONFIGURATION");
        if (!properties.enabled()) throw new OrderLookupException(OrderLookupException.Kind.BLOCKED, null, null);
        stage(request, "ENVELOPE");
        validateEnvelope(request);
        stage(request, "SIGNATURE_HEADERS");
        String signature = singleValue(Collections.list(request.getHeaders("x-signature")).toArray(String[]::new), true, MISSING_SIGNATURE, REPEATED_SIGNATURE);
        String requestId = singleValue(Collections.list(request.getHeaders("x-request-id")).toArray(String[]::new), false, INVALID_NOTIFICATION, REPEATED_REQUEST_ID);
        stage(request, "ORDER_ID");
        String orderId = singleValue(request.getParameterValues("data.id"), true, MISSING_ORDER_ID, REPEATED_ORDER_ID);
        stage(request, "HMAC");
        ObservationLog.safely(() -> LOGGER.atDebug().addKeyValue("event.code", "PAYMENT_WEBHOOK_HMAC_STARTED")
                .addKeyValue("webhook.request_id_present", requestId != null && !requestId.isBlank())
                .log("Verificação HMAC iniciada; manifesto omitido"));
        MercadoPagoSignatureValidator.Strategy strategy = signatureValidator.validate(signature, requestId, orderId);
        recordSignatureVerified(orderId, strategy);
        ObservationLog.safely(() -> LOGGER.atDebug().addKeyValue("event.code", "PAYMENT_WEBHOOK_HMAC_VERIFIED")
                .log("Assinatura HMAC validada"));
        stage(request, "NOTIFICATION");
        validateNotification(request, orderId);
        stage(request, "VERIFIED");
        ObservationLog.safely(() -> LOGGER.atInfo().addKeyValue("event.code", "PAYMENT_WEBHOOK_VERIFIED")
                .addKeyValue(PROVIDER_ORDER_ID_LOG_KEY, orderId).log("Notificação verificada; aguardando consulta financeira"));
        return new OrderWebhookIngress(orderId, null);
    }

    private void validateEnvelope(HttpServletRequest request) {
        String contentType = request.getContentType();
        try {
            if (!request.getMethod().equals("POST") || contentType == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType))) throw new InvalidOrderNotificationException(400);
        } catch (IllegalArgumentException _) {
            throw new InvalidOrderNotificationException(400);
        }
    }

    private void recordSignatureVerified(String orderId, MercadoPagoSignatureValidator.Strategy strategy) {
        ObservationLog.safely(() -> LOGGER.atDebug().addKeyValue("event.code", "PAYMENT_WEBHOOK_SIGNATURE_VERIFIED")
                .addKeyValue(PROVIDER_ORDER_ID_LOG_KEY, orderId).addKeyValue("webhook.signature_strategy", strategy.name())
                .addKeyValue("webhook.hmac_result", "VALID").addKeyValue("webhook.environment", properties.environment())
                .log("Assinatura do webhook validada"));
        if (strategy == MercadoPagoSignatureValidator.Strategy.LOWERCASE_COMPATIBILITY) {
            ObservationLog.safely(() -> LOGGER.atInfo()
                    .addKeyValue("event.code", "PAYMENT_WEBHOOK_SIGNATURE_COMPATIBILITY_APPLIED")
                    .addKeyValue(PROVIDER_ORDER_ID_LOG_KEY, orderId).addKeyValue("webhook.signature_strategy", strategy.name())
                    .addKeyValue("webhook.hmac_result", "VALID").addKeyValue("webhook.environment", properties.environment())
                    .log("Compatibilidade de capitalização HMAC aplicada"));
        }
    }

    private void validateNotification(HttpServletRequest request, String orderId) {
        String type = singleValue(request.getParameterValues("type"), false, INVALID_NOTIFICATION, INVALID_NOTIFICATION);
        if (type != null && !type.equals("order")) throw new InvalidOrderNotificationException(400);
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_NOTIFICATION_BYTES + 1);
            if (body.length > MAX_NOTIFICATION_BYTES) throw new InvalidOrderNotificationException(413);
            notificationMapper.validate(body, orderId);
        } catch (IOException failure) {
            throw new IllegalStateException("Falha na leitura da notificação", failure);
        }
    }

    private void stage(HttpServletRequest request, String stage) {
        request.setAttribute(HttpObservationFilter.STAGE, stage);
    }

    private String singleValue(String[] values, boolean required, InvalidOrderNotificationException.Reason missing,
                          InvalidOrderNotificationException.Reason repeated) {
        if (values == null || values.length == 0) {
            if (required) throw new InvalidOrderNotificationException(401, missing);
            return null;
        }
        if (values.length != 1) throw new InvalidOrderNotificationException(401, repeated);
        return values[0];
    }
}
