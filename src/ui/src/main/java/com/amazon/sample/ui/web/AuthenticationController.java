package com.amazon.sample.ui.web;

import com.amazon.sample.ui.services.orders.OrdersService;
import com.amazon.sample.ui.web.payload.RegistrationRequest;
import com.amazon.sample.ui.web.util.RequiresCommonAttributes;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.ServerRequestCache;
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
  private final ServerRequestCache checkoutRequestCache;

  public AuthenticationController(
    OrdersService ordersService,
    ServerSecurityContextRepository securityContextRepository,
    ServerRequestCache checkoutRequestCache
  ) {
    this.ordersService = ordersService;
    this.securityContextRepository = securityContextRepository;
    this.checkoutRequestCache = checkoutRequestCache;
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
      .then(redirectAfterAuthentication(exchange))
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

  private Mono<String> redirectAfterAuthentication(ServerWebExchange exchange) {
    return checkoutRequestCache
      .getRedirectUri(exchange)
      .filter(this::isCheckoutPath)
      .map(this::redirectView)
      .defaultIfEmpty("redirect:/demo/orders");
  }

  private boolean isCheckoutPath(URI redirectUri) {
    String path = redirectUri.getRawPath();
    return "/checkout".equals(path) ||
    (path != null && path.startsWith("/checkout/"));
  }

  private String redirectView(URI redirectUri) {
    String query = redirectUri.getRawQuery();
    return "redirect:" + redirectUri.getRawPath() +
    (query == null ? "" : "?" + query);
  }
}
