package com.amazon.sample.orders.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "users")
public class UserEntity {

  @Id
  private String id;

  private String email;
  private String passwordHash;

  public UserEntity() {}

  public UserEntity(String id, String email, String passwordHash) {
    this.id = id;
    this.email = email;
    this.passwordHash = passwordHash;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }
}
