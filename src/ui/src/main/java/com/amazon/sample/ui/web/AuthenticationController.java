package com.amazon.sample.ui.web;

import com.amazon.sample.ui.services.orders.OrdersService;
import com.amazon.sample.ui.web.payload.RegistrationRequest;
import com.amazon.sample.ui.web.util.RequiresCommonAttributes;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Controller
@RequiresCommonAttributes
public class AuthenticationController {

  private final OrdersService ordersService;
  private final ServerSecurityContextRepository securityContextRepository;

  public AuthenticationController(
    OrdersService ordersService,
    ServerSecurityContextRepository securityContextRepository
  ) {
    this.ordersService = ordersService;
    this.securityContextRepository = securityContextRepository;
  }

  @GetMapping("/login")
  public Mono<String> login(ServerWebExchange exchange, Model model) {
    return csrfToken(exchange)
      .doOnNext(token -> model.addAttribute("_csrf", token))
      .thenReturn("login");
  }

  @GetMapping("/register")
  public Mono<String> register(ServerWebExchange exchange, Model model) {
    model.addAttribute("registrationRequest", new RegistrationRequest());
    return csrfToken(exchange)
      .doOnNext(token -> model.addAttribute("_csrf", token))
      .thenReturn("register");
  }

  @PostMapping("/register")
  public Mono<String> register(
    @Valid @ModelAttribute("registrationRequest") RegistrationRequest request,
    BindingResult result,
    ServerWebExchange exchange,
    Model model
  ) {
    if (result.hasErrors()) {
      return Mono.just("register");
    }

    return ordersService
      .register(request.getEmail(), request.getPassword())
      .flatMap(user -> {
        var authentication = new UsernamePasswordAuthenticationToken(
          user.getEmail(),
          null,
          List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        return securityContextRepository.save(
          exchange,
          new SecurityContextImpl(authentication)
        );
      })
      .thenReturn("redirect:/demo/orders")
      .onErrorResume(error -> {
        if (error instanceof ResponseStatusException exception) {
          model.addAttribute(
            "registrationError",
            exception.getStatusCode() == HttpStatus.CONFLICT
              ? "This email is already registered."
              : "Unable to create an account."
          );
        } else {
          model.addAttribute("registrationError", "Unable to create an account.");
        }
        return Mono.just("register");
      });
  }

  @SuppressWarnings("unchecked")
  private Mono<CsrfToken> csrfToken(ServerWebExchange exchange) {
    return exchange.getAttributeOrDefault(
      CsrfToken.class.getName(),
      Mono.empty()
    );
  }
}
