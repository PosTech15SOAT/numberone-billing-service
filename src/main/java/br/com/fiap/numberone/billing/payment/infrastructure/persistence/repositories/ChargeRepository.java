package br.com.fiap.numberone.billing.payment.infrastructure.persistence.repositories;

import br.com.fiap.numberone.billing.payment.infrastructure.persistence.entities.ChargeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChargeRepository extends JpaRepository<ChargeEntity, UUID> {
}
