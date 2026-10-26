package com.example.accountservice.controller;

import com.example.accountservice.dto.AmountRequest;
import com.example.accountservice.dto.CreateAccountRequest;
import com.example.accountservice.dto.UpdateAccountRequest;
import com.example.accountservice.model.Account;
import com.example.accountservice.model.AccountStatus;
import com.example.accountservice.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping(path = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class AccountController {

    private static final String ACC_NO = "^\\d{10}$";

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);
    /** GET /api/v1/accounts?customerId=CUST-1001&status=ACTIVE */
    @GetMapping
    public List<Account> list(
            @RequestParam(required = false) @Pattern(regexp = "^CUST-\\d{4,10}$") String customerId,
            @RequestParam(required = false) AccountStatus status) {
        log.info("Pradeep listed accounts (customerId={}, status={})", customerId, status);
        return service.list(customerId, status);
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<Account> get(@PathVariable @Pattern(regexp = ACC_NO) String accountNumber) {
        return withETag(service.get(accountNumber));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Account> create(@Valid @RequestBody CreateAccountRequest request) {
        Account created = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{accountNumber}").buildAndExpand(created.accountNumber()).toUri();
        return ResponseEntity.created(location).eTag(etag(created)).body(created);
    }

    /** Send If-Match with the version from the last GET to avoid overwriting someone else's change. */
    @PutMapping(path = "/{accountNumber}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Account> update(@PathVariable @Pattern(regexp = ACC_NO) String accountNumber,
                                          @Valid @RequestBody UpdateAccountRequest request,
                                          @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withETag(service.update(accountNumber, request, version(ifMatch)));
    }

    @PostMapping(path = "/{accountNumber}/deposit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Account> deposit(@PathVariable @Pattern(regexp = ACC_NO) String accountNumber,
                                           @Valid @RequestBody AmountRequest request) {
        return withETag(service.deposit(accountNumber, request));
    }

    @PostMapping(path = "/{accountNumber}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Account> withdraw(@PathVariable @Pattern(regexp = ACC_NO) String accountNumber,
                                            @Valid @RequestBody AmountRequest request) {
        return withETag(service.withdraw(accountNumber, request));
    }

    @PostMapping("/{accountNumber}/freeze")
    public ResponseEntity<Account> freeze(@PathVariable @Pattern(regexp = ACC_NO) String accountNumber) {
        return withETag(service.freeze(accountNumber));
    }

    @PostMapping("/{accountNumber}/unfreeze")
    public ResponseEntity<Account> unfreeze(@PathVariable @Pattern(regexp = ACC_NO) String accountNumber) {
        return withETag(service.unfreeze(accountNumber));
    }

    /** Closing is a status change, not a delete: account history is kept. */
    @DeleteMapping("/{accountNumber}")
    public ResponseEntity<Account> close(@PathVariable @Pattern(regexp = ACC_NO) String accountNumber) {
        return withETag(service.close(accountNumber));
    }

    // ----- helpers -----

    private static ResponseEntity<Account> withETag(Account account) {
        return ResponseEntity.ok().eTag(etag(account)).body(account);
    }

    private static String etag(Account account) {
        return "\"" + account.version() + "\"";
    }

    private static Long version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim());   // bad value -> 400
    }
}
