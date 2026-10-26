package com.example.accountservice.repository;

import com.example.accountservice.model.Account;

import java.util.Collection;
import java.util.Optional;

/**
 * Storage abstraction. Today it is in memory; a JPA or JDBC implementation can replace it later
 * without changing the service or controller.
 */
public interface AccountRepository {

    Optional<Account> findByAccountNumber(String accountNumber);

    Collection<Account> findAll();

    /** Inserts a new account; returns false if the account number already exists. */
    boolean insert(Account account);

    /**
     * Replaces the account only if it is still the same version we read (optimistic locking).
     * Returns false if someone else changed it in the meantime.
     */
    boolean update(Account expectedCurrent, Account updated);

    long count();
}
