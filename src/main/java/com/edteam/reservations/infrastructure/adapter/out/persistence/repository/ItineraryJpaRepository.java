package com.edteam.reservations.infrastructure.adapter.out.persistence.repository;

import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ItineraryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acceso a la tabla {@code itinerario}.
 *
 * <p>No hay búsqueda por contenido: el modelo de datos no le pone clave natural
 * al itinerario, justamente porque la misma combinación de tramos puede
 * venderse a distinto precio. Cada reserva nueva crea su itinerario, aunque
 * reutilice los segmentos.
 */
public interface ItineraryJpaRepository extends JpaRepository<ItineraryJpaEntity, Long> {}
