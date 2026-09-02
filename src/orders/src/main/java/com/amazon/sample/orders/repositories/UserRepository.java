package com.amazon.sample.orders.repositories;

import com.amazon.sample.orders.entities.UserEntity;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends CrudRepository<UserEntity, String> {
  Optional<UserEntity> findByEmail(String email);
}
