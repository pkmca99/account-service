package com.example.accountservice.service;

import com.example.accountservice.dto.AmountRequest;
import com.example.accountservice.dto.CreateAccountRequest;
import com.example.accountservice.dto.UpdateAccountRequest;
import com.example.accountservice.exception.BusinessRuleException;
import com.example.accountservice.exception.ConcurrentUpdateException;
import com.example.accountservice.exception.NotFoundException;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void unknownAccountIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.get("9999999999"));
        assertThrows(NotFoundException.class, () ->
                service.deposit("9999999999", new AmountRequest(BigDecimal.ONE, null)));
    }

    @Test
    void depositSetsUpdatedAtFromTheClock() {
        Account after = service.deposit(account.accountNumber(), new AmountRequest(new BigDecimal("0.01"), null));
        assertEquals(new BigDecimal("100.01"), after.balance());
        assertEquals(Instant.parse("2026-10-25T10:00:00Z"), after.updatedAt());
        assertEquals(account.createdAt(), after.createdAt());
    }

    @Test
    void unfreezeMakesTheAccountActiveAgain() {
        service.freeze(account.accountNumber());
        Account after = service.unfreeze(account.accountNumber());
        assertEquals(AccountStatus.ACTIVE, after.status());
        assertEquals(new BigDecimal("101.00"),
                service.deposit(account.accountNumber(), new AmountRequest(BigDecimal.ONE, null)).balance());
    }

    @Test
    void unfreezeNeedsAFrozenAccount() {
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.unfreeze(account.accountNumber()));
        assertEquals("NOT_FROZEN", ex.getCode());
    }

    @Test
    void closedAccountCannotBeUpdatedOrUsed() {
        service.withdraw(account.accountNumber(), new AmountRequest(new BigDecimal("100.00"), null));
        service.close(account.accountNumber());

        UpdateAccountRequest update = new UpdateAccountRequest("New Name", "new@example.com", "9000000002",
                AccountType.SAVINGS, "2 Test Street");
        assertEquals("ACCOUNT_CLOSED", assertThrows(BusinessRuleException.class,
                () -> service.update(account.accountNumber(), update, null)).getCode());
        assertEquals("ACCOUNT_NOT_ACTIVE", assertThrows(BusinessRuleException.class,
                () -> service.deposit(account.accountNumber(), new AmountRequest(BigDecimal.ONE, null))).getCode());
        assertEquals("ACCOUNT_CLOSED", assertThrows(BusinessRuleException.class,
                () -> service.close(account.accountNumber())).getCode());
    }

    @Test
    void closedAccountAllowsANewOneOfTheSameType() {
        service.withdraw(account.accountNumber(), new AmountRequest(new BigDecimal("100.00"), null));
        service.close(account.accountNumber());
        Account second = service.create(new CreateAccountRequest("CUST-2001", "Test User", "t@example.com",
                "9000000001", AccountType.SAVINGS, "1 Test Street", "USD", BigDecimal.ZERO));
        assertNotEquals(account.accountNumber(), second.accountNumber());
    }

    @Test
    void listFiltersByCustomerAndStatus() {
        Account current = service.create(new CreateAccountRequest("CUST-2001", "Test User", "t@example.com",
                "9000000001", AccountType.CURRENT, "1 Test Street", "USD", BigDecimal.ZERO));
        service.create(new CreateAccountRequest("CUST-3001", "Other User", "o@example.com",
                "9000000003", AccountType.SAVINGS, "3 Test Street", "USD", BigDecimal.ZERO));
        service.freeze(current.accountNumber());

        assertEquals(3, service.list(null, null).size());
        assertEquals(2, service.list("CUST-2001", null).size());
        assertEquals(List.of(current.accountNumber()),
                service.list("CUST-2001", AccountStatus.FROZEN).stream().map(Account::accountNumber).toList());
    }

    @Test
    void concurrentChangeIsRetried() {
        // The first compare-and-swap loses (as if another request changed the account in between);
        // the retry reads the account again and succeeds.
        AtomicInteger updates = new AtomicInteger();
        InMemoryAccountRepository repository = new InMemoryAccountRepository() {
            @Override
            public boolean update(Account expectedCurrent, Account updated) {
                return updates.incrementAndGet() > 1 && super.update(expectedCurrent, updated);
            }
        };
        AccountService racing = new AccountService(repository,
                Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC));
        Account created = racing.create(new CreateAccountRequest("CUST-2001", "Test User", "t@example.com",
                "9000000001", AccountType.SAVINGS, "1 Test Street", "USD", new BigDecimal("100.00")));

        Account after = racing.deposit(created.accountNumber(), new AmountRequest(new BigDecimal("5.00"), null));
        assertEquals(new BigDecimal("105.00"), after.balance());
        assertEquals(2, updates.get());
    }

    @Test
    void givesUpAfterThreeLostRaces() {
        InMemoryAccountRepository repository = new InMemoryAccountRepository() {
            @Override
            public boolean update(Account expectedCurrent, Account updated) {
                return false;   // every compare-and-swap loses
            }
        };
        AccountService busy = new AccountService(repository,
                Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC));
        Account created = busy.create(new CreateAccountRequest("CUST-2001", "Test User", "t@example.com",
                "9000000001", AccountType.SAVINGS, "1 Test Street", "USD", BigDecimal.ZERO));

        ConcurrentUpdateException ex = assertThrows(ConcurrentUpdateException.class,
                () -> busy.deposit(created.accountNumber(), new AmountRequest(BigDecimal.ONE, null)));
        assertFalse(ex.isPreconditionFailed());
    }
}
