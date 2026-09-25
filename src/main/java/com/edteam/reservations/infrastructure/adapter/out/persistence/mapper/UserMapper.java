package com.edteam.reservations.infrastructure.adapter.out.persistence.mapper;

import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.User;
import com.edteam.reservations.domain.model.UserId;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.UserJpaEntity;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Traduce usuarios entre el dominio y JPA.
 *
 * <p>Todavía no hay casos de uso sobre el usuario —la reserva sólo lo
 * referencia por id—, así que este mapper existe para completar la
 * correspondencia con el modelo de datos y para que el alta de usuarios tenga
 * dónde apoyarse cuando llegue.
 */
@Component
public class UserMapper {

    public User toDomain(UserJpaEntity entity) {
        return new User(
                Optional.of(UserId.of(entity.getId())),
                Email.of(entity.getEmail()),
                entity.getFirstName(),
                entity.getLastName(),
                entity.getRegisteredAt());
    }

    /** Crea la entidad para un usuario nuevo; el id lo asigna la base. */
    public UserJpaEntity toNewEntity(User user) {
        return new UserJpaEntity(null, user.email().value(), user.firstName(), user.lastName(), user.registeredAt());
    }
}
