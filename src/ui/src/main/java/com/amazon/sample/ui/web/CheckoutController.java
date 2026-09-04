/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.amazon.sample.ui.web;

import com.amazon.sample.ui.services.checkout.CheckoutService;
import com.amazon.sample.ui.services.checkout.model.Checkout;
import com.amazon.sample.ui.services.checkout.model.ShippingAddress;
import com.amazon.sample.ui.web.payload.CheckoutDeliveryMethodRequest;
import com.amazon.sample.ui.web.payload.PaymentDetailsRequest;
import com.amazon.sample.ui.web.payload.ShippingAddressRequest;
import com.amazon.sample.ui.web.util.RequiresCommonAttributes;
import com.amazon.sample.ui.web.util.SessionIDUtil;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Controller
@RequestMapping("/checkout")
@Slf4j
@RequiresCommonAttributes
public class CheckoutController {

  private CheckoutService checkoutService;

  public CheckoutController(@Autowired CheckoutService checkoutService) {
    this.checkoutService = checkoutService;
  }

  @GetMapping
  public Mono<String> checkout(ServerWebExchange exchange, Model model) {
    return showShipping(new ShippingAddressRequest(), exchange, model);
  }

  private Mono<String> showShipping(
    ShippingAddressRequest shippingAddressRequest,
    ServerWebExchange exchange,
    Model model
  ) {
    String sessionId = SessionIDUtil.getSessionId(exchange.getRequest());

    model.addAttribute("shippingAddressRequest", shippingAddressRequest);

    Mono<String> shippingView = exchange
      .getPrincipal()
      .doOnNext(principal -> shippingAddressRequest.setEmail(principal.getName()))
      .hasElement()
      .doOnNext(authenticated ->
        model.addAttribute("isAuthenticated", authenticated)
      )
      .then(this.checkoutService.create(sessionId))
      .doOnNext(o -> {
        model.addAttribute("checkout", o);
      })
      .thenReturn("checkout-shipping");
    return addCsrfToken(exchange, model).then(shippingView);
  }

  @PostMapping
  public Mono<String> handleShipping(
    @Valid @ModelAttribute(
      "shippingAddressRequest"
    ) ShippingAddressRequest shippingAddressRequest,
    BindingResult result,
    ServerWebExchange exchange,
    Model model
  ) {
    if (result.hasErrors()) {
      return showShipping(shippingAddressRequest, exchange, model);
    }

    ShippingAddress address = new ShippingAddress();
    address.setFirstName(shippingAddressRequest.getFirstName());
    address.setLastName(shippingAddressRequest.getLastName());
    address.setAddress1(shippingAddressRequest.getAddress1());
    address.setAddress2(shippingAddressRequest.getAddress2());
    address.setCity(shippingAddressRequest.getCity());
    address.setState(shippingAddressRequest.getState());
    address.setZip(shippingAddressRequest.getZipCode());
    return exchange
      .getPrincipal()
      .map(principal -> principal.getName())
      .defaultIfEmpty(shippingAddressRequest.getEmail())
      .flatMap(email -> {
        address.setEmail(email);
        String sessionId = SessionIDUtil.getSessionId(exchange.getRequest());

        return this.checkoutService.shipping(sessionId, address).flatMap(c -> {
            String defaultToken = null;

            if (c.getShippingOptions().size() > 0) {
              defaultToken = c.getShippingOptions().get(0).getToken();
            }
            return this.showDelivery(
                c,
                new CheckoutDeliveryMethodRequest(defaultToken),
                exchange,
                model
              );
          });
      });
  }

  public Mono<String> showDelivery(
    Checkout checkout,
    CheckoutDeliveryMethodRequest checkoutDeliveryMethodRequest,
    ServerWebExchange exchange,
    Model model
  ) {
    model.addAttribute(
      "checkoutDeliveryMethodRequest",
      checkoutDeliveryMethodRequest
    );
    model.addAttribute("checkout", checkout);

    return addCsrfToken(exchange, model).thenReturn("checkout-delivery");
  }

  @PostMapping("/delivery")
  public Mono<String> handleDelivery(
    @Valid @ModelAttribute(
      "checkoutDeliveryMethodRequest"
    ) CheckoutDeliveryMethodRequest checkoutDeliveryMethodRequest,
    BindingResult result,
    ServerWebExchange exchange,
    Model model
  ) {
    String sessionId = SessionIDUtil.getSessionId(exchange.getRequest());

    if (result.hasErrors()) {
      return this.checkoutService.get(sessionId).flatMap(c ->
          showDelivery(c, checkoutDeliveryMethodRequest, exchange, model)
        );
    }

    model.addAttribute("paymentDetailsRequest", checkoutDeliveryMethodRequest);

    return this.checkoutService.delivery(
        sessionId,
        checkoutDeliveryMethodRequest.getToken()
      ).flatMap(c ->
        this.showPayment(c, new PaymentDetailsRequest(), exchange, model)
      );
  }

  public Mono<String> showPayment(
    Checkout checkout,
    PaymentDetailsRequest paymentDetailsRequest,
    ServerWebExchange exchange,
    Model model
  ) {
    model.addAttribute("paymentDetailsRequest", paymentDetailsRequest);
    model.addAttribute("checkout", checkout);

    return addCsrfToken(exchange, model).thenReturn("checkout-payment");
  }

  @PostMapping("/payment")
  public Mono<String> handlePayment(
    @Valid @ModelAttribute(
      "paymentDetailsRequest"
    ) PaymentDetailsRequest paymentDetailsRequest,
    BindingResult result,
    ServerWebExchange exchange,
    Model model
  ) {
    String sessionId = SessionIDUtil.getSessionId(exchange.getRequest());

    if (result.hasErrors()) {
      return this.checkoutService.get(sessionId).flatMap(c ->
          showPayment(c, paymentDetailsRequest, exchange, model)
        );
    }

    return this.checkoutService.submit(sessionId)
      .doOnNext(o -> {
        model.addAttribute("summary", o);
      })
      .thenReturn("order");
  }

  @SuppressWarnings("unchecked")
  private Mono<CsrfToken> csrfToken(ServerWebExchange exchange) {
    return exchange.getAttributeOrDefault(
      CsrfToken.class.getName(),
      Mono.empty()
    );
  }

  private Mono<Void> addCsrfToken(ServerWebExchange exchange, Model model) {
    return csrfToken(exchange)
      .doOnNext(token -> model.addAttribute("_csrf", token))
      .then();
  }

}
