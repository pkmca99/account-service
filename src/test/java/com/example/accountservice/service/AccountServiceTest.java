package com.example.accountservice.service;

import com.example.accountservice.dto.AmountRequest;
import com.example.accountservice.dto.CreateAccountRequest;
import com.example.accountservice.dto.UpdateAccountRequest;
import com.example.accountservice.exception.BusinessRuleException;
import com.example.accountservice.exception.ConcurrentUpdateException;
import com.example.accountservice.model.Account;
import com.example.accountservice.model.AccountStatus;
import com.example.accountservice.model.AccountType;
import com.example.accountservice.repository.InMemoryAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class AccountServiceTest {

    private AccountService service;
    private Account account;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);
        service = new AccountService(new InMemoryAccountRepository(), clock);
        account = service.create(new CreateAccountRequest("CUST-2001", "Test User", "Test@Example.com",
                "9000000001", AccountType.SAVINGS, "1 Test Street", "USD", new BigDecimal("100.00")));
    }

    @Test
    void createsAnActiveAccountWithVersionZero() {
        assertEquals(AccountStatus.ACTIVE, account.status());
        assertEquals(0, account.version());
        assertEquals("test@example.com", account.email());
        assertTrue(account.accountNumber().matches("\\d{10}"));
    }

    @Test
    void rejectsASecondOpenAccountOfTheSameType() {
        CreateAccountRequest again = new CreateAccountRequest("CUST-2001", "Test User", "t@example.com",
                "9000000001", AccountType.SAVINGS, "1 Test Street", "USD", BigDecimal.ZERO);
        BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.create(again));
        assertEquals("DUPLICATE_ACCOUNT", ex.getCode());
    }

    @Test
    void depositAndWithdrawChangeTheBalance() {
        service.deposit(account.accountNumber(), new AmountRequest(new BigDecimal("50.00"), "cash"));
        Account after = service.withdraw(account.accountNumber(), new AmountRequest(new BigDecimal("30.00"), null));
        assertEquals(new BigDecimal("120.00"), after.balance());
        assertEquals(2, after.version());
    }

    @Test
    void cannotWithdrawMoreThanTheBalance() {
        BusinessRuleException ex = assertThrows(BusinessRuleException.class, () ->
                service.withdraw(account.accountNumber(), new AmountRequest(new BigDecimal("100.01"), null)));
        assertEquals("INSUFFICIENT_FUNDS", ex.getCode());
    }

    @Test
    void frozenAccountCannotTransact() {
        service.freeze(account.accountNumber());
        assertThrows(BusinessRuleException.class, () ->
                service.deposit(account.accountNumber(), new AmountRequest(BigDecimal.ONE, null)));
    }

    @Test
    void closeNeedsAZeroBalance() {
        assertThrows(BusinessRuleException.class, () -> service.close(account.accountNumber()));
        service.withdraw(account.accountNumber(), new AmountRequest(new BigDecimal("100.00"), null));
        assertEquals(AccountStatus.CLOSED, service.close(account.accountNumber()).status());
    }

    @Test
    void staleIfMatchVersionIsRejected() {
        UpdateAccountRequest update = new UpdateAccountRequest("New Name", "new@example.com", "9000000002",
                AccountType.SAVINGS, "2 Test Street");
        service.update(account.accountNumber(), update, 0L);                 // version 0 -> 1
        ConcurrentUpdateException ex = assertThrows(ConcurrentUpdateException.class,
                () -> service.update(account.accountNumber(), update, 0L));   // stale
        assertTrue(ex.isPreconditionFailed());
    }
}
