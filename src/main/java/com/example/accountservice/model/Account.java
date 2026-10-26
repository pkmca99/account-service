package com.example.accountservice.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable account. Every change creates a new copy with version + 1,
 * which makes the in-memory store thread-safe and gives optimistic locking for free.
 */
public record Account(
        String accountNumber,
        String customerId,
        String customerName,
        String email,
        String mobileNumber,
        AccountType accountType,
        String branchAddress,
        String currency,
        BigDecimal balance,
        AccountStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public Account withDetails(String customerName, String email, String mobileNumber,
                               AccountType accountType, String branchAddress, Instant now) {
        return new Account(accountNumber, customerId, customerName, email, mobileNumber, accountType,
                branchAddress, currency, balance, status, createdAt, now, version + 1);
    }

    public Account withBalance(BigDecimal newBalance, Instant now) {
        return new Account(accountNumber, customerId, customerName, email, mobileNumber, accountType,
                branchAddress, currency, newBalance, status, createdAt, now, version + 1);
    }

    public Account withStatus(AccountStatus newStatus, Instant now) {
        return new Account(accountNumber, customerId, customerName, email, mobileNumber, accountType,
                branchAddress, currency, balance, newStatus, createdAt, now, version + 1);
    }
}
