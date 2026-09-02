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

package com.amazon.sample.ui.web;

import com.amazon.sample.ui.client.orders.models.OrderItem;
import com.amazon.sample.ui.services.catalog.CatalogService;
import com.amazon.sample.ui.services.orders.OrdersService;
import com.amazon.sample.ui.web.util.RequiresCommonAttributes;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Controller
@RequestMapping("/demo/orders")
@RequiresCommonAttributes
@Slf4j
public class OrderManagementController {

  private final OrdersService ordersService;
  private final CatalogService catalogService;

  public OrderManagementController(
    OrdersService ordersService,
    CatalogService catalogService
  ) {
    this.ordersService = ordersService;
    this.catalogService = catalogService;
  }

  @GetMapping
  public Mono<String> orders(Model model) {
    model.addAttribute("orders", List.of());
    model.addAttribute("productNames", Map.of());
    model.addAttribute("ordersError", false);

    return ordersService
      .list()
      .flatMap(orders -> {
        model.addAttribute("orders", orders);
        return Flux.fromIterable(orders)
          .flatMapIterable(order ->
            order.getItems() == null ? List.<OrderItem>of() : order.getItems()
          )
          .map(OrderItem::getProductId)
          .filter(productId -> productId != null && !productId.isBlank())
          .distinct()
          .flatMap(productId ->
            catalogService
              .getProduct(productId)
              .filter(product -> product.getName() != null && !product.getName().isBlank())
              .map(product -> Map.entry(productId, product.getName()))
              .onErrorResume(error -> Mono.empty())
          )
          .collectMap(Map.Entry::getKey, Map.Entry::getValue);
      })
      .doOnNext(productNames -> model.addAttribute("productNames", productNames))
      .onErrorResume(error -> {
        log.warn("Unable to load orders for order management page", error);
        model.addAttribute("ordersError", true);
        return Mono.empty();
      })
      .thenReturn("order-management");
  }
}
