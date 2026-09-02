package com.amazon.sample.ui.config;

import com.amazon.sample.ui.services.orders.OrdersService;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  @Bean
  public ServerSecurityContextRepository securityContextRepository() {
    return new WebSessionServerSecurityContextRepository();
  }

  @Bean
  public ReactiveAuthenticationManager authenticationManager(
    OrdersService ordersService
  ) {
    return authentication -> {
      String password = authentication.getCredentials() == null
        ? ""
        : authentication.getCredentials().toString();

      return ordersService
        .authenticate(authentication.getName(), password)
        .map(user ->
          new UsernamePasswordAuthenticationToken(
            user.getEmail(),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
          )
        );
    };
  }

  @Bean
  public SecurityWebFilterChain securityWebFilterChain(
    ServerHttpSecurity http,
    ReactiveAuthenticationManager authenticationManager,
    ServerSecurityContextRepository securityContextRepository
  ) {
    return http
      .authenticationManager(authenticationManager)
      .securityContextRepository(securityContextRepository)
      .csrf(csrf ->
        csrf.requireCsrfProtectionMatcher(
          ServerWebExchangeMatchers.pathMatchers(
            HttpMethod.POST,
            "/login",
            "/register",
            "/logout"
          )
        )
      )
      .authorizeExchange(exchange ->
        exchange
          .pathMatchers("/demo/orders")
          .authenticated()
          .anyExchange()
          .permitAll()
      )
      .formLogin(form ->
        form
          .loginPage("/login")
          .authenticationSuccessHandler(
            new RedirectServerAuthenticationSuccessHandler("/demo/orders")
          )
      )
      .build();
  }
}
