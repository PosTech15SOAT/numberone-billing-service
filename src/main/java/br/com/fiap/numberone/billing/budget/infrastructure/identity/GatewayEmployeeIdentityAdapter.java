package br.com.fiap.numberone.billing.budget.infrastructure.identity;

import br.com.fiap.numberone.billing.budget.application.exceptions.EmployeeIdentityUnavailableException;
import br.com.fiap.numberone.billing.budget.application.gateways.EmployeeIdentityGateway;
import br.com.fiap.numberone.billing.budget.infrastructure.config.EmployeeIdentityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/** Lê somente contexto de auditoria da aprovação presencial; não autentica nem autoriza requisições. */
@Component
@RequestScope
public class GatewayEmployeeIdentityAdapter implements EmployeeIdentityGateway {

    private final HttpServletRequest request;
    private final EmployeeIdentityProperties properties;

    public GatewayEmployeeIdentityAdapter(HttpServletRequest request, EmployeeIdentityProperties properties) {
        this.request = request;
        this.properties = properties;
    }

    @Override
    public String currentEmployeeId() {
        // Valida apenas a integridade do dado que será persistido como auditoria.
        var values = request.getHeaders(properties.subjectHeader());
        if (values == null || !values.hasMoreElements()) throw new EmployeeIdentityUnavailableException();
        String subject = values.nextElement();
        if (values.hasMoreElements() || subject == null || subject.isBlank() || subject.length() > 255
                || !subject.equals(subject.strip()) || subject.chars().anyMatch(Character::isISOControl)) {
            throw new EmployeeIdentityUnavailableException();
        }
        return subject;
    }
}
