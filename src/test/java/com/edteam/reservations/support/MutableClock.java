package com.edteam.reservations.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Reloj de test que se puede adelantar a voluntad.
 *
 * <p>Necesario para verificar comportamiento dependiente del tiempo —el
 * vencimiento del cache de aeropuertos, por ejemplo— sin poner {@code sleep}
 * en los tests.
 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public static MutableClock at(Instant instant) {
        return new MutableClock(instant, ZoneId.of("UTC"));
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }
}
