package com.edteam.reservations.infrastructure.adapter.out.persistence.repository;

import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.PassengerJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistencia de pasajeros.
 *
 * <p>Ya no hay {@code findByDocumentNumber} ni {@code insertIfAbsent}: la
 * deduplicación global por documento se eliminó (T-06), y además dejó de ser
 * posible —el documento se guarda cifrado con IV aleatorio, así que dos
 * escrituras del mismo valor producen columnas distintas—. Cada reserva
 * escribe sus propios pasajeros.
 */
public interface PassengerJpaRepository extends JpaRepository<PassengerJpaEntity, Long> {}
