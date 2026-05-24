package fake.user;

import java.util.Optional;
import java.util.UUID;

/**
 * JPA-style repository for User records. Backed by Postgres in
 * production; tests pull this in via the H2 fixture.
 */
public interface UserRepository {

    Optional<User> findByEmail(String email);

    Optional<User> findById(UUID id);

    User save(User user);

    boolean existsByEmail(String email);

    long countAll();
}
