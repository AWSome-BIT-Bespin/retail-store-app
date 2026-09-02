/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.amazon.sample.ui.services.orders;

import com.amazon.sample.ui.client.orders.OrdersClient;
import com.amazon.sample.ui.client.orders.models.Credentials;
import com.amazon.sample.ui.client.orders.models.ExistingOrder;
import com.amazon.sample.ui.client.orders.models.User;
import com.amazon.sample.ui.client.orders.models.UserRegistration;
import java.util.List;
import reactor.core.publisher.Mono;

public class OrdersService {

  private final OrdersClient ordersClient;

  public OrdersService(OrdersClient ordersClient) {
    this.ordersClient = ordersClient;
  }

  public Mono<List<ExistingOrder>> list() {
    return Mono.fromCallable(() -> {
      return client().orders().get();
    });
  }

  public Mono<List<ExistingOrder>> list(String customerEmail) {
    return Mono.fromCallable(() ->
      client().orders().get(config ->
          config.queryParameters.customerEmail = customerEmail
        )
    );
  }

  public Mono<User> register(String email, String password) {
    return Mono.fromCallable(() -> {
      var registration = new UserRegistration();
      registration.setEmail(email);
      registration.setPassword(password);

      return client().users().post(registration);
    });
  }

  public Mono<User> authenticate(String email, String password) {
    return Mono.fromCallable(() -> {
      var credentials = new Credentials();
      credentials.setEmail(email);
      credentials.setPassword(password);

      return client().auth().login().post(credentials);
    });
  }

  private OrdersClient client() {
    if (ordersClient == null) {
      throw new IllegalStateException("Orders endpoint is not configured");
    }

    return ordersClient;
  }
}
