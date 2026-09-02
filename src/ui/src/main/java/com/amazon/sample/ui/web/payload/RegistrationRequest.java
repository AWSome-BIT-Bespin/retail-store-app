package com.amazon.sample.ui.web.payload;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RegistrationRequest {
  @NotBlank(message = "Email is required")
  @Email(message = "Email invalid")
  private String email;

  @NotBlank(message = "Password is required")
  private String password;
}
