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

package com.amazon.sample.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazon.sample.orders.entities.OrderEntity;
import com.amazon.sample.orders.entities.OrderItemEntity;
import com.amazon.sample.orders.entities.ShippingAddressEntity;
import com.amazon.sample.orders.repositories.OrderRepository;
import com.amazon.sample.orders.repositories.UserRepository;
import com.amazon.sample.orders.services.OrderService;
import com.amazon.sample.orders.services.UserService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OrdersApplicationTests {

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private UserService userService;

  @Autowired
  private OrderService orderService;

  @Test
  void contextLoads() {}

  @Test
  void preservesProductIdWhenSavingOrderItems() {
    var productId = "cc789f85-1476-452a-8100-9e74502198e0";
    var order = orderRepository.save(
      new OrderEntity(
        Set.of(new OrderItemEntity(productId, 1, 10, 10)),
        new ShippingAddressEntity(
          "John",
          "Doe",
          "john@example.com",
          "100 Main Street",
          "",
          "Anytown",
          "11111",
          "CA"
        )
      )
    );

    assertThat(orderRepository.findById(order.getId()).orElseThrow().getItems())
      .extracting(OrderItemEntity::getProductId)
      .containsExactly(productId);
  }

  @Test
  void storesHashedCredentialsAndFindsOrdersByCustomerEmail() {
    var user = userService.register("John@Example.com", "demo");
    var order = orderRepository.save(
      new OrderEntity(
        Set.of(new OrderItemEntity("product-id", 1, 10, 10)),
        new ShippingAddressEntity(
          "John",
          "Doe",
          "john@example.com",
          "100 Main Street",
          "",
          "Anytown",
          "11111",
          "CA"
        )
      )
    );

    assertThat(userRepository.findById(user.getId()).orElseThrow().getPasswordHash())
      .isNotEqualTo("demo");
    assertThat(userService.authenticate("JOHN@example.com", "demo").getEmail())
      .isEqualTo("john@example.com");
    assertThat(orderService.listByCustomerEmail("JOHN@example.com"))
      .extracting(OrderEntity::getId)
      .containsExactly(order.getId());
  }
}
