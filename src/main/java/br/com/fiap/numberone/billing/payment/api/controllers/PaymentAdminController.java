package br.com.fiap.numberone.billing.payment.api.controllers;

import br.com.fiap.numberone.billing.payment.api.dtos.ChargeCheckoutResponse;
import br.com.fiap.numberone.billing.payment.api.dtos.CheckoutResponse;
import br.com.fiap.numberone.billing.payment.api.exceptions.PaymentRequestException;
import br.com.fiap.numberone.billing.payment.api.mappers.PaymentApiMapper;
import br.com.fiap.numberone.billing.payment.application.usecases.CreateCheckoutUseCase;
import br.com.fiap.numberone.billing.payment.application.usecases.GetChargeCheckoutUseCase;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;

@RestController
@RequestMapping("/api/admin/ordens-servico/{serviceOrderId}/pagamentos/checkout")
public class PaymentAdminController {
    private final CreateCheckoutUseCase createCheckout;
    private final GetChargeCheckoutUseCase getCheckout;
    private final PaymentApiMapper mapper;

    public PaymentAdminController(CreateCheckoutUseCase createCheckout, GetChargeCheckoutUseCase getCheckout,
                                  PaymentApiMapper mapper) {
        this.createCheckout = createCheckout;
        this.getCheckout = getCheckout;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<CheckoutResponse> create(@PathVariable String serviceOrderId, HttpServletRequest request) {
        try {
            requireEmptyBody(request);
            CheckoutResponse response = mapper.toResponse(createCheckout.execute(mapper.requireServiceOrderId(serviceOrderId),
                    mapper.requireIdempotencyKey(Collections.list(request.getHeaders("Idempotency-Key")))));
            URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().build().toUri();
            return ResponseEntity.status(response.processing() ? 202 : 200).location(location)
                    .cacheControl(CacheControl.noStore()).body(response);
        } catch (Exception failure) {
            throw new PaymentRequestException(failure);
        }
    }

    @GetMapping
    public ResponseEntity<ChargeCheckoutResponse> get(@PathVariable String serviceOrderId) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(mapper.toResponse(getCheckout.execute(mapper.requireServiceOrderId(serviceOrderId))));
        } catch (RuntimeException failure) {
            throw new PaymentRequestException(failure);
        }
    }

    private void requireEmptyBody(HttpServletRequest request) throws IOException {
        if (request.getInputStream().read() != -1) {
            throw new InvalidPaymentDataException("Esta operação não recebe corpo; informe somente OS e chave idempotente");
        }
    }
}
