/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 */

package com.amazon.sample.ui.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazon.sample.ui.client.orders.models.ExistingOrder;
import com.amazon.sample.ui.client.orders.models.OrderItem;
import com.amazon.sample.ui.services.catalog.CatalogService;
import com.amazon.sample.ui.services.catalog.model.Product;
import com.amazon.sample.ui.services.orders.OrdersService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import reactor.core.publisher.Mono;

class OrderManagementControllerTest {

  @Test
  void addsCatalogNamesWithoutFailingWhenAProductLookupIsUnavailable() {
    var ordersService = mock(OrdersService.class);
    var catalogService = mock(CatalogService.class);
    var product = new OrderItem();
    product.setProductId("1");
    var unavailableProduct = new OrderItem();
    unavailableProduct.setProductId("2");
    var order = new ExistingOrder();
    order.setItems(List.of(product, unavailableProduct));
    Principal principal = () -> "john@example.com";
    when(ordersService.list(principal.getName())).thenReturn(Mono.just(List.of(order)));
    when(catalogService.getProduct("1"))
      .thenReturn(Mono.just(new Product("1", "Coffee", "", 0, List.of())));
    when(catalogService.getProduct("2"))
      .thenThrow(new IllegalStateException("Catalog endpoint is not configured"));
    var model = new ConcurrentModel();

    var view = new OrderManagementController(ordersService, catalogService)
      .orders(model, principal)
      .block();

    assertThat(view).isEqualTo("order-management");
    assertThat(model.getAttribute("productNames")).isEqualTo(Map.of("1", "Coffee"));
    assertThat(model.getAttribute("ordersError")).isEqualTo(false);
    verify(catalogService).getProduct("1");
    verify(catalogService).getProduct("2");
  }
}
