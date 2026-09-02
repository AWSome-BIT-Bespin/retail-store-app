package com.amazon.sample.orders.services;

import com.amazon.sample.orders.entities.UserEntity;
import com.amazon.sample.orders.repositories.UserRepository;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {

  private final UserRepository repository;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  public UserService(UserRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public UserEntity register(String email, String password) {
    String normalizedEmail = normalizeEmail(email);

    if (repository.findByEmail(normalizedEmail).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
    }

    try {
      return repository.save(
        new UserEntity(
          null,
          normalizedEmail,
          passwordEncoder.encode(password)
        )
      );
    } catch (DuplicateKeyException exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
    }
  }

  public UserEntity authenticate(String email, String password) {
    return repository
      .findByEmail(normalizeEmail(email))
      .filter(user -> passwordEncoder.matches(password, user.getPasswordHash()))
      .orElseThrow(() ->
        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
      );
  }

  private String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }
}
