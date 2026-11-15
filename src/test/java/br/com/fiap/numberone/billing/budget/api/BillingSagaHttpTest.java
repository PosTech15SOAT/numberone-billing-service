package br.com.fiap.numberone.billing.budget.api;

import br.com.fiap.numberone.billing.budget.api.controllers.BillingSagaController;
import br.com.fiap.numberone.billing.budget.application.gateways.BillingSagaStore;
import br.com.fiap.numberone.billing.budget.application.usecases.CloseBillingContext;
import br.com.fiap.numberone.billing.infrastructure.observability.HttpObservationFilter;
import br.com.fiap.numberone.billing.infrastructure.rest.BillingSagaCorrelation;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BillingSagaHttpTest {
    @Test void shouldAcceptSagaRequestWithoutBearerAndRequireCorrelation() throws Exception {
        var charges = mock(ChargeGateway.class);
        var order = UUID.randomUUID();
        var correlation = UUID.randomUUID();
        when(charges.findByServiceOrderId(order)).thenReturn(Optional.empty());
        var mvc = MockMvcBuilders.standaloneSetup(new BillingSagaController(mock(BillingSagaStore.class), charges,
                        mock(CloseBillingContext.class)))
                .addInterceptors(new BillingSagaCorrelation()).addFilters(new HttpObservationFilter()).build();
        String path = "/internal/payments/service-orders/" + order + "/settlement";
        mvc.perform(get(path).header("X-Correlation-Id", correlation.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.serviceOrderId").value(order.toString()));
        mvc.perform(get(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get(path).header("X-Correlation-Id", "invalid"))
                .andExpect(status().isBadRequest());
        verify(charges, times(1)).findByServiceOrderId(order);
    }
}
