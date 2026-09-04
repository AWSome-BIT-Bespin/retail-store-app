package com.amazon.sample.ui.config;

import com.amazon.sample.ui.services.orders.OrdersService;
import java.net.URI;
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
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.savedrequest.ServerRequestCache;
import org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  @Bean
  public ServerSecurityContextRepository securityContextRepository() {
    return new WebSessionServerSecurityContextRepository();
  }

  @Bean
  public ServerRequestCache checkoutRequestCache() {
    var requestCache = new WebSessionServerRequestCache();
    requestCache.setSaveRequestMatcher(
      ServerWebExchangeMatchers.pathMatchers(
        HttpMethod.GET,
        "/checkout",
        "/checkout/**"
      )
    );
    return requestCache;
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
    ServerSecurityContextRepository securityContextRepository,
    ServerRequestCache checkoutRequestCache
  ) {
    var authenticationSuccessHandler =
      new RedirectServerAuthenticationSuccessHandler("/demo/orders");
    authenticationSuccessHandler.setRequestCache(checkoutRequestCache);
    var logoutSuccessHandler = new RedirectServerLogoutSuccessHandler();
    logoutSuccessHandler.setLogoutSuccessUrl(URI.create("/"));

    return http
      .authenticationManager(authenticationManager)
      .securityContextRepository(securityContextRepository)
      .csrf(csrf ->
        csrf.requireCsrfProtectionMatcher(
          ServerWebExchangeMatchers.pathMatchers(
            HttpMethod.POST,
            "/login",
            "/register",
            "/logout",
            "/checkout",
            "/checkout/**"
          )
        )
      )
      .requestCache(requestCache ->
        requestCache.requestCache(checkoutRequestCache)
      )
      .authorizeExchange(exchange ->
        exchange
          .pathMatchers(
            "/demo/orders",
            "/checkout",
            "/checkout/**",
            "/proxy/checkout/**"
          )
          .authenticated()
          .anyExchange()
          .permitAll()
      )
      .formLogin(form ->
        form
          .loginPage("/login")
          .authenticationSuccessHandler(authenticationSuccessHandler)
      )
      .logout(logout ->
        logout.logoutSuccessHandler(logoutSuccessHandler)
      )
      .build();
  }
}
