package br.com.fiap.numberone.billing.budget.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("billing.identity")
public record EmployeeIdentityProperties(String subjectHeader) {

    public EmployeeIdentityProperties {
        if (subjectHeader == null || !subjectHeader.matches("[A-Za-z][A-Za-z0-9-]*")) {
            throw new IllegalArgumentException("O nome do header de identidade do funcionário está inválido");
        }
    }
}
