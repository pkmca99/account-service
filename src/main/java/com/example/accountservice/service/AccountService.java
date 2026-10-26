package com.example.accountservice.service;

import com.example.accountservice.dto.AmountRequest;
import com.example.accountservice.dto.CreateAccountRequest;
import com.example.accountservice.dto.UpdateAccountRequest;
import com.example.accountservice.exception.BusinessRuleException;
import com.example.accountservice.exception.ConcurrentUpdateException;
import com.example.accountservice.exception.NotFoundException;
import com.example.accountservice.model.Account;
import com.example.accountservice.model.AccountStatus;
import com.example.accountservice.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;

/** Business rules for accounts. Every rule is checked here, whatever the caller sends. */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final int MAX_RETRIES = 3;

    private final AccountRepository repository;
    private final Clock clock;

    public AccountService(AccountRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<Account> list(String customerId, AccountStatus status) {
        log.debug("Listing accounts customerId={} status={}", customerId, status);
        return repository.findAll().stream()
                .filter(a -> customerId == null || a.customerId().equals(customerId))
                .filter(a -> status == null || a.status() == status)
                .sorted(Comparator.comparing(Account::accountNumber))
                .toList();
    }

    public Account get(String accountNumber) {
        log.debug("Fetching account {}", accountNumber);
        return repository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new NotFoundException("Account " + accountNumber + " not found"));
    }

    public Account create(CreateAccountRequest r) {
        boolean duplicate = repository.findAll().stream().anyMatch(a ->
                a.customerId().equals(r.customerId()) && a.accountType() == r.accountType()
                        && a.status() != AccountStatus.CLOSED);
        if (duplicate) {
            log.warn("Rejected create: customer {} already has an open {} account", r.customerId(), r.accountType());
            throw new BusinessRuleException("DUPLICATE_ACCOUNT",
                    "Customer " + r.customerId() + " already has an open " + r.accountType() + " account");
        }
        Instant now = Instant.now(clock);
        for (int i = 0; i < 5; i++) {
            Account account = new Account(newAccountNumber(), r.customerId(), r.customerName().strip(),
                    r.email().strip().toLowerCase(), r.mobileNumber(), r.accountType(), r.branchAddress().strip(),
                    r.currency(), r.openingBalance(), AccountStatus.ACTIVE, now, now, 0);
            if (repository.insert(account)) {
                log.info("Account {} created for customer {} (type={}, currency={}, openingBalance={})",
                        account.accountNumber(), r.customerId(), r.accountType(), r.currency(), r.openingBalance());
                return account;
            }
        }
        log.error("Could not generate a unique account number for customer {} after 5 attempts", r.customerId());
        throw new IllegalStateException("Could not generate a unique account number");
    }

    /** Update contact details. expectedVersion comes from the If-Match header (optional). */
    public Account update(String accountNumber, UpdateAccountRequest r, Long expectedVersion) {
        Account current = get(accountNumber);
        checkVersion(current, expectedVersion);
        requireNotClosed(current);
        Account updated = current.withDetails(r.customerName().strip(), r.email().strip().toLowerCase(),
                r.mobileNumber(), r.accountType(), r.branchAddress().strip(), Instant.now(clock));
        if (!repository.update(current, updated)) {
            log.warn("Update of account {} lost a race with another request", accountNumber);
            throw new ConcurrentUpdateException("Account " + accountNumber + " was changed by someone else", false);
        }
        log.info("Account {} details updated (version {} -> {})", accountNumber, current.version(), updated.version());
        return updated;
    }

    public Account deposit(String accountNumber, AmountRequest r) {
        Account updated = changeBalance(accountNumber, a -> {
            requireActive(a);
            return a.withBalance(a.balance().add(r.amount()), Instant.now(clock));
        });
        log.info("Pradeep Deposited {} {} to account {} (version {})",
                r.amount(), updated.currency(), accountNumber, updated.version());
        return updated;
    }

    public Account withdraw(String accountNumber, AmountRequest r) {
        Account updated = changeBalance(accountNumber, a -> {
            requireActive(a);
            if (a.balance().compareTo(r.amount()) < 0) {
                throw new BusinessRuleException("INSUFFICIENT_FUNDS",
                        "Balance " + a.balance() + " " + a.currency() + " is less than " + r.amount());
            }
            return a.withBalance(a.balance().subtract(r.amount()), Instant.now(clock));
        });
        log.info("Withdrew {} {} from account {} (version {})",
                r.amount(), updated.currency(), accountNumber, updated.version());
        return updated;
    }

    public Account freeze(String accountNumber) {
        Account updated = changeBalance(accountNumber, a -> {
            requireActive(a);
            return a.withStatus(AccountStatus.FROZEN, Instant.now(clock));
        });
        log.info("Account {} frozen", accountNumber);
        return updated;
    }

    public Account unfreeze(String accountNumber) {
        Account updated = changeBalance(accountNumber, a -> {
            if (a.status() != AccountStatus.FROZEN) {
                throw new BusinessRuleException("NOT_FROZEN", "Account " + accountNumber + " is not frozen");
            }
            return a.withStatus(AccountStatus.ACTIVE, Instant.now(clock));
        });
        log.info("Account {} unfrozen", accountNumber);
        return updated;
    }

    /** Close an account. Accounts are never deleted; the balance must be zero first. */
    public Account close(String accountNumber) {
        Account updated = changeBalance(accountNumber, a -> {
            requireNotClosed(a);
            if (a.balance().compareTo(BigDecimal.ZERO) != 0) {
                throw new BusinessRuleException("BALANCE_NOT_ZERO",
                        "Withdraw the remaining balance before closing the account");
            }
            return a.withStatus(AccountStatus.CLOSED, Instant.now(clock));
        });
        log.info("Account {} closed", accountNumber);
        return updated;
    }

    // ----- helpers -----

    /** Read, apply the change, compare-and-swap; retry a few times if another request won the race. */
    private Account changeBalance(String accountNumber, UnaryOperator<Account> change) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Account current = get(accountNumber);
            Account updated = change.apply(current);
            if (repository.update(current, updated)) {
                return updated;
            }
            log.debug("Account {} changed concurrently, retrying (attempt {}/{})", accountNumber, attempt, MAX_RETRIES);
        }
        log.warn("Account {} still busy after {} attempts, giving up", accountNumber, MAX_RETRIES);
        throw new ConcurrentUpdateException("Account " + accountNumber + " is busy, please retry", false);
    }

    private static void checkVersion(Account current, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw new ConcurrentUpdateException("Account " + current.accountNumber() + " has changed (version "
                    + current.version() + ", you had " + expectedVersion + "). Refresh and try again.", true);
        }
    }

    private static void requireActive(Account a) {
        if (a.status() != AccountStatus.ACTIVE) {
            throw new BusinessRuleException("ACCOUNT_NOT_ACTIVE", "Account " + a.accountNumber() + " is " + a.status());
        }
    }

    private static void requireNotClosed(Account a) {
        if (a.status() == AccountStatus.CLOSED) {
            throw new BusinessRuleException("ACCOUNT_CLOSED", "Account " + a.accountNumber() + " is closed");
        }
    }

    private static String newAccountNumber() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
    }
}
