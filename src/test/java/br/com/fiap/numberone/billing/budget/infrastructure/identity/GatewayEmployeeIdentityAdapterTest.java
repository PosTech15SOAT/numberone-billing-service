package br.com.fiap.numberone.billing.budget.infrastructure.identity;

import br.com.fiap.numberone.billing.budget.application.exceptions.EmployeeIdentityUnavailableException;
import br.com.fiap.numberone.billing.budget.infrastructure.config.EmployeeIdentityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.*;

class GatewayEmployeeIdentityAdapterTest {

    @Test
    void shouldReadOnlyTheConfiguredAuditContextHeader() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Authenticated-Subject", "ignored");
        request.addHeader("X-Platform-Employee", "550e8400-e29b-41d4-a716-446655440000");
        var adapter = new GatewayEmployeeIdentityAdapter(request, new EmployeeIdentityProperties("X-Platform-Employee"));
        assertThat(adapter.currentEmployeeId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " subject", "subject ", "subject\ninvalid"})
    void shouldRejectMissingOrMalformedAuditContext(String subject) {
        var request = new MockHttpServletRequest();
        if (subject != null) request.addHeader("X-Authenticated-Subject", subject);
        var adapter = new GatewayEmployeeIdentityAdapter(request, new EmployeeIdentityProperties("X-Authenticated-Subject"));
        assertThatThrownBy(adapter::currentEmployeeId).isInstanceOf(EmployeeIdentityUnavailableException.class);
    }

    @Test
    void shouldRejectAmbiguousOrOversizedAuditContext() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Authenticated-Subject", "one");
        request.addHeader("X-Authenticated-Subject", "two");
        var adapter = new GatewayEmployeeIdentityAdapter(request, new EmployeeIdentityProperties("X-Authenticated-Subject"));
        assertThatThrownBy(adapter::currentEmployeeId).isInstanceOf(EmployeeIdentityUnavailableException.class);
        request.removeHeader("X-Authenticated-Subject");
        request.addHeader("X-Authenticated-Subject", "x".repeat(256));
        assertThatThrownBy(adapter::currentEmployeeId).isInstanceOf(EmployeeIdentityUnavailableException.class);
    }
}
