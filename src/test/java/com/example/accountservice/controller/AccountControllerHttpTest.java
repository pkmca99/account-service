package com.example.accountservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Calls the real HTTP API (full Spring context on a random port): status codes, ETag/If-Match,
 * request validation and the problem-details error format.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=WARN", "logging.level.org.springframework.web=WARN",
                "logging.level.com.example.accountservice=WARN"})
class AccountControllerHttpTest {

    // Each test opens accounts for its own customer, so tests don't hit the duplicate-account rule.
    private static final AtomicInteger CUSTOMER = new AtomicInteger(7000);

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Test
    void createReturns201WithLocationAndVersionZeroETag() throws Exception {
        HttpResponse<String> res = post("/api/v1/accounts", newAccountJson("100.00"));

        assertEquals(201, res.statusCode());
        String accountNumber = field(res.body(), "accountNumber");
        assertTrue(res.headers().firstValue("Location").orElseThrow().endsWith("/api/v1/accounts/" + accountNumber));
        assertEquals("\"0\"", res.headers().firstValue("ETag").orElseThrow());
        assertEquals("ACTIVE", field(res.body(), "status"));
    }

    @Test
    void depositReturnsTheNewBalanceAndNextVersion() throws Exception {
        String accountNumber = createAccount("100.00");

        HttpResponse<String> res = post("/api/v1/accounts/" + accountNumber + "/deposit", "{\"amount\": 50.25}");

        assertEquals(200, res.statusCode());
        assertEquals("\"1\"", res.headers().firstValue("ETag").orElseThrow());
        assertTrue(res.body().contains("\"balance\":150.25"), res.body());
    }

    @Test
    void unknownAccountIs404() throws Exception {
        HttpResponse<String> res = get("/api/v1/accounts/9999999999");

        assertEquals(404, res.statusCode());
        assertEquals("NOT_FOUND", field(res.body(), "code"));
    }

    @Test
    void malformedAccountNumberIs400() throws Exception {
        HttpResponse<String> res = get("/api/v1/accounts/12ab");

        assertEquals(400, res.statusCode());
        assertEquals("BAD_REQUEST", field(res.body(), "code"));
    }

    @Test
    void withdrawingTooMuchIs422() throws Exception {
        String accountNumber = createAccount("10.00");

        HttpResponse<String> res = post("/api/v1/accounts/" + accountNumber + "/withdraw", "{\"amount\": 10.01}");

        assertEquals(422, res.statusCode());
        assertEquals("INSUFFICIENT_FUNDS", field(res.body(), "code"));
    }

    @Test
    void invalidBodyIs400WithTheFieldNames() throws Exception {
        String body = """
                {"customerId": "C1", "customerName": "Test User", "email": "not-an-email",
                 "mobileNumber": "123", "accountType": "SAVINGS", "branchAddress": "1 Test Street",
                 "currency": "USD", "openingBalance": 0}
                """;

        HttpResponse<String> res = post("/api/v1/accounts", body);

        assertEquals(400, res.statusCode());
        assertEquals("VALIDATION_FAILED", field(res.body(), "code"));
        assertTrue(res.body().contains("\"customerId\""), res.body());
        assertTrue(res.body().contains("\"email\""), res.body());
        assertTrue(res.body().contains("\"mobileNumber\""), res.body());
        assertFalse(res.body().contains("not-an-email"), "rejected values must not be echoed back");
    }

    @Test
    void negativeAmountIs400() throws Exception {
        String accountNumber = createAccount("10.00");

        HttpResponse<String> res = post("/api/v1/accounts/" + accountNumber + "/deposit", "{\"amount\": -5}");

        assertEquals(400, res.statusCode());
    }

    @Test
    void staleIfMatchIs412() throws Exception {
        String accountNumber = createAccount("10.00");
        post("/api/v1/accounts/" + accountNumber + "/deposit", "{\"amount\": 1}");          // version 0 -> 1
        String update = """
                {"customerName": "New Name", "email": "new@example.com", "mobileNumber": "9000000002",
                 "accountType": "SAVINGS", "branchAddress": "2 Test Street"}
                """;

        HttpResponse<String> res = send(HttpRequest.newBuilder(uri("/api/v1/accounts/" + accountNumber))
                .header("Content-Type", "application/json")
                .header("If-Match", "\"0\"")
                .PUT(HttpRequest.BodyPublishers.ofString(update)));

        assertEquals(412, res.statusCode());
        assertEquals("VERSION_MISMATCH", field(res.body(), "code"));
    }

    // ----- helpers -----

    private String createAccount(String openingBalance) throws Exception {
        HttpResponse<String> res = post("/api/v1/accounts", newAccountJson(openingBalance));
        assertEquals(201, res.statusCode(), res.body());
        return field(res.body(), "accountNumber");
    }

    private static String newAccountJson(String openingBalance) {
        return """
                {"customerId": "CUST-%d", "customerName": "Test User", "email": "test@example.com",
                 "mobileNumber": "9000000001", "accountType": "SAVINGS", "branchAddress": "1 Test Street",
                 "currency": "USD", "openingBalance": %s}
                """.formatted(CUSTOMER.incrementAndGet(), openingBalance);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.header("Accept", "application/json, application/problem+json").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** Value of a top-level string field in a JSON body, e.g. "code". */
    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertTrue(m.find(), () -> "no \"" + name + "\" in " + json);
        return m.group(1);
    }
}
