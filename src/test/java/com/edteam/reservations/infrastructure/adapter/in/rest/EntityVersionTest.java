package com.edteam.reservations.infrastructure.adapter.in.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EntityVersion")
class EntityVersionTest {

    @Nested
    @DisplayName("If-Match")
    class IfMatch {

        @Test
        @DisplayName("acepta la forma fuerte y la débil")
        void acceptsBothForms() {
            assertThat(EntityVersion.parseIfMatch("\"7\"")).isEqualTo(7L);
            assertThat(EntityVersion.parseIfMatch("W/\"7\"")).isEqualTo(7L);
        }

        @Test
        @DisplayName("rechaza lo que no es un ETag de esta API, comodín incluido")
        void rejectsAnythingElse() {
            assertThatThrownBy(() -> EntityVersion.parseIfMatch("*"))
                    .isInstanceOf(EntityVersion.InvalidIfMatchException.class);
            assertThatThrownBy(() -> EntityVersion.parseIfMatch("\"-1\""))
                    .isInstanceOf(EntityVersion.InvalidIfMatchException.class);
        }
    }

    @Nested
    @DisplayName("If-None-Match")
    class IfNoneMatch {

        @Test
        @DisplayName("coincide con la versión que el cliente ya tiene")
        void matchesTheSameVersion() {
            assertThat(EntityVersion.matchesIfNoneMatch("\"7\"", 7L)).isTrue();
            assertThat(EntityVersion.matchesIfNoneMatch("W/\"7\"", 7L)).isTrue();
        }

        @Test
        @DisplayName("no coincide con otra versión: ahí hay que mandar la representación completa")
        void doesNotMatchAnotherVersion() {
            assertThat(EntityVersion.matchesIfNoneMatch("\"7\"", 8L)).isFalse();
        }

        @Test
        @DisplayName("acepta la lista separada por comas")
        void acceptsAList() {
            assertThat(EntityVersion.matchesIfNoneMatch("\"5\", \"7\", W/\"9\"", 7L)).isTrue();
            assertThat(EntityVersion.matchesIfNoneMatch("\"5\", \"6\"", 7L)).isFalse();
        }

        @Test
        @DisplayName("el comodín significa 'cualquier representación que exista'")
        void wildcardAlwaysMatches() {
            assertThat(EntityVersion.matchesIfNoneMatch("*", 7L)).isTrue();
        }

        @Test
        @DisplayName("ausente o malformado no es un error: devuelve la representación completa")
        void tolerantWithGarbage() {
            // A diferencia de If-Match, acá un valor que no se entiende no
            // puede ser un 400: el header sólo pregunta "¿cambió?".
            assertThat(EntityVersion.matchesIfNoneMatch(null, 7L)).isFalse();
            assertThat(EntityVersion.matchesIfNoneMatch("   ", 7L)).isFalse();
            assertThat(EntityVersion.matchesIfNoneMatch("basura", 7L)).isFalse();
        }
    }
}
