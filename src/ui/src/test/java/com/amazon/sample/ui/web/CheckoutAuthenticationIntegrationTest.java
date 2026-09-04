package com.amazon.sample.ui.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazon.sample.ui.client.orders.models.User;
import com.amazon.sample.ui.services.checkout.CheckoutService;
import com.amazon.sample.ui.services.checkout.model.ShippingAddress;
import com.amazon.sample.ui.services.orders.OrdersService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration"
)
@AutoConfigureWebTestClient
class CheckoutAuthenticationIntegrationTest {

  private static final String PRODUCT_ID = "cc789f85-1476-452a-8100-9e74502198e0";
  private static final String EMAIL = "user@example.com";
  private static final Pattern CSRF_TOKEN = Pattern.compile(
    "(?s)name=\\\"_csrf\\\".*?value=\\\"([^\\\"]+)\\\""
  );

  @Autowired
  private WebTestClient webClient;

  @MockitoBean
  private OrdersService ordersService;

  @MockitoSpyBean
  private CheckoutService checkoutService;

  @BeforeEach
  void setUp() {
    when(ordersService.authenticate(eq(EMAIL), anyString())).thenReturn(
      Mono.just(user(EMAIL))
    );
    when(ordersService.register(eq(EMAIL), anyString())).thenReturn(
      Mono.just(user(EMAIL))
    );
  }

  @Test
  void keepsCatalogAndCartPublicAndBlocksUnauthenticatedCheckout() {
    Browser browser = browserAfter(get("/catalog", Browser.empty()).expectStatus().isOk().expectBody(String.class).returnResult());
    assertThat(browser.cartSession()).isNotBlank();

    get("/cart", browser).expectStatus().isOk();
    browser = browserAfter(post("/cart", browser, form("productId", PRODUCT_ID, "quantity", "1"))
      .expectStatus().is3xxRedirection()
      .expectBody(String.class).returnResult(), browser);
    get("/cart", browser)
      .expectStatus().isOk()
      .expectBody(String.class)
      .value(body -> assertThat(body).contains("Temporal Tickstopper"));

    browser = browserAfter(get("/checkout", browser)
      .expectStatus().is3xxRedirection()
      .expectHeader().valueEquals(HttpHeaders.LOCATION, "/login")
      .expectBody(String.class).returnResult(), browser);

    post("/checkout", browser, shippingForm("spoof@example.com")).expectStatus().isForbidden();
    post("/checkout/delivery", browser, form("token", "standard")).expectStatus().isForbidden();
    post("/checkout/payment", browser, form("cardHolder", "User")).expectStatus().isForbidden();
    get("/proxy/checkout/example", browser).expectStatus().is3xxRedirection();
    get("/demo/orders", Browser.empty()).expectStatus().is3xxRedirection();
  }

  @Test
  void returnsToCheckoutAfterLoginAndPreservesCartSession() {
    Browser browser = addCartItem(Browser.empty());
    String originalCartSession = browser.cartSession();

    browser = requestCheckout(browser);
    browser = login(browser, "/checkout");

    assertThat(browser.cartSession()).isEqualTo(originalCartSession);
    get("/checkout", browser).expectStatus().isOk();
    get("/cart", browser)
      .expectStatus().isOk()
      .expectBody(String.class)
      .value(body -> assertThat(body).contains("Temporal Tickstopper"));
  }

  @Test
  void sendsRegularLoginToOrdersAndRegistrationBackToSavedCheckout() {
    Browser regularLogin = browserAfter(get("/login", Browser.empty()).expectStatus().isOk().expectBody(String.class).returnResult());
    login(regularLogin, "/demo/orders");

    Browser registration = requestCheckout(Browser.empty());
    EntityExchangeResult<String> registerPage = get("/register", registration)
      .expectStatus().isOk()
      .expectBody(String.class)
      .returnResult();
    registration = browserAfter(registerPage, registration);

    registration = browserAfter(post(
      "/register",
      registration,
      form("email", EMAIL, "password", "password", "_csrf", csrf(registerPage.getResponseBody()))
    )
      .expectStatus().is3xxRedirection()
      .expectHeader().valueEquals(HttpHeaders.LOCATION, "/checkout")
      .expectBody(String.class).returnResult(), registration);
    get("/checkout", registration).expectStatus().isOk();
  }

  @Test
  void requiresCsrfForCheckoutPostAndUsesPrincipalEmail() {
    Browser browser = login(requestCheckout(Browser.empty()), "/checkout");
    EntityExchangeResult<String> checkoutPage = get("/checkout", browser)
      .expectStatus().isOk()
      .expectBody(String.class)
      .returnResult();
    browser = browserAfter(checkoutPage, browser);

    post("/checkout", browser, shippingForm("spoof@example.com")).expectStatus().isForbidden();
    post(
      "/checkout",
      browser,
      formWithCsrf(shippingForm("spoof@example.com"), csrf(checkoutPage.getResponseBody()))
    )
      .expectStatus().isOk()
      .expectBody(String.class)
      .value(body -> assertThat(body).contains("Standard"));

    ArgumentCaptor<ShippingAddress> address = ArgumentCaptor.forClass(ShippingAddress.class);
    verify(checkoutService).shipping(eq(browser.cartSession()), address.capture());
    assertThat(address.getValue().getEmail()).isEqualTo(EMAIL);
  }

  private Browser addCartItem(Browser browser) {
    Browser catalog = browserAfter(get("/catalog", browser).expectStatus().isOk().expectBody(String.class).returnResult(), browser);
    return browserAfter(post("/cart", catalog, form("productId", PRODUCT_ID, "quantity", "1"))
      .expectStatus().is3xxRedirection()
      .expectBody(String.class).returnResult(), catalog);
  }

  private Browser requestCheckout(Browser browser) {
    return browserAfter(get("/checkout", browser)
      .expectStatus().is3xxRedirection()
      .expectHeader().valueEquals(HttpHeaders.LOCATION, "/login")
      .expectBody(String.class).returnResult(), browser);
  }

  private Browser login(Browser browser, String destination) {
    EntityExchangeResult<String> loginPage = get("/login", browser)
      .expectStatus().isOk()
      .expectBody(String.class)
      .returnResult();
    browser = browserAfter(loginPage, browser);
    return browserAfter(post(
      "/login",
      browser,
      form("username", EMAIL, "password", "password", "_csrf", csrf(loginPage.getResponseBody()))
    )
      .expectStatus().is3xxRedirection()
      .expectHeader().valueEquals(HttpHeaders.LOCATION, destination)
      .expectBody(String.class).returnResult(), browser);
  }

  private WebTestClient.ResponseSpec get(String path, Browser browser) {
    return cookies(webClient.get().uri(path), browser).exchange();
  }

  private WebTestClient.ResponseSpec post(String path, Browser browser, MultiValueMap<String, String> form) {
    return cookies(
      webClient.post().uri(path).contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(BodyInserters.fromFormData(form)),
      browser
    ).exchange();
  }

  private WebTestClient.RequestHeadersSpec<?> cookies(
    WebTestClient.RequestHeadersSpec<?> request,
    Browser browser
  ) {
    if (browser.webSession() != null) {
      request.cookie("SESSION", browser.webSession());
    }
    if (browser.cartSession() != null) {
      request.cookie("SESSIONID", browser.cartSession());
    }
    return request;
  }

  private Browser browserAfter(EntityExchangeResult<String> response) {
    return browserAfter(response, Browser.empty());
  }

  private Browser browserAfter(EntityExchangeResult<String> response, Browser browser) {
    String webSession = response.getResponseCookies().getFirst("SESSION") == null
      ? browser.webSession()
      : response.getResponseCookies().getFirst("SESSION").getValue();
    String cartSession = response.getResponseCookies().getFirst("SESSIONID") == null
      ? browser.cartSession()
      : response.getResponseCookies().getFirst("SESSIONID").getValue();
    return new Browser(webSession, cartSession);
  }

  private MultiValueMap<String, String> shippingForm(String email) {
    return form(
      "firstName", "Test",
      "lastName", "User",
      "address1", "1 Main Street",
      "city", "Anytown",
      "state", "CA",
      "zipCode", "12345",
      "email", email
    );
  }

  private MultiValueMap<String, String> formWithCsrf(MultiValueMap<String, String> form, String token) {
    form.add("_csrf", token);
    return form;
  }

  private MultiValueMap<String, String> form(String... values) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    for (int i = 0; i < values.length; i += 2) {
      form.add(values[i], values[i + 1]);
    }
    return form;
  }

  private String csrf(String html) {
    Matcher matcher = CSRF_TOKEN.matcher(html);
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private User user(String email) {
    User user = new User();
    user.setEmail(email);
    return user;
  }

  private record Browser(String webSession, String cartSession) {
    private static Browser empty() {
      return new Browser(null, null);
    }
  }
}
