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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazon.sample.ui.client.orders.OrdersClient;
import com.amazon.sample.ui.client.orders.models.ExistingOrder;
import com.amazon.sample.ui.client.orders.orders.OrdersRequestBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class OrdersServiceTest {

  @Test
  void listsOrdersThroughGeneratedClient() {
    var client = mock(OrdersClient.class);
    var requestBuilder = mock(OrdersRequestBuilder.class);
    var order = new ExistingOrder();
    when(client.orders()).thenReturn(requestBuilder);
    when(requestBuilder.get()).thenReturn(List.of(order));

    StepVerifier.create(new OrdersService(client).list())
      .assertNext(orders -> assertThat(orders).containsExactly(order))
      .verifyComplete();

    verify(client).orders();
    verify(requestBuilder).get();
  }

  @Test
  void returnsErrorWhenOrdersEndpointIsNotConfigured() {
    StepVerifier.create(new OrdersService(null).list())
      .expectErrorSatisfies(error ->
        assertThat(error)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Orders endpoint is not configured")
      )
      .verify();
  }
}
