package com.amazon.sample.orders.web;

import com.amazon.sample.orders.entities.UserEntity;
import com.amazon.sample.orders.services.UserService;
import com.amazon.sample.orders.web.payload.Credentials;
import com.amazon.sample.orders.web.payload.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  @PostMapping("/users")
  @ResponseStatus(HttpStatus.CREATED)
  public User register(@Valid @RequestBody Credentials credentials) {
    return toUser(userService.register(credentials.email(), credentials.password()));
  }

  @PostMapping("/auth/login")
  public User login(@Valid @RequestBody Credentials credentials) {
    return toUser(userService.authenticate(credentials.email(), credentials.password()));
  }

  private User toUser(UserEntity user) {
    return new User(user.getId(), user.getEmail());
  }
}
