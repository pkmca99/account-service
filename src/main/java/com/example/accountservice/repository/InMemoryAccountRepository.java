package com.example.accountservice.repository;

import com.example.accountservice.model.Account;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe in-memory store. Data is lost on restart and reloaded from the test data in application.yml. */
@Repository
public class InMemoryAccountRepository implements AccountRepository {

    private final ConcurrentMap<String, Account> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Account> findByAccountNumber(String accountNumber) {
        return Optional.ofNullable(store.get(accountNumber));
    }

    @Override
    public Collection<Account> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public boolean insert(Account account) {
        return store.putIfAbsent(account.accountNumber(), account) == null;
    }

    @Override
    public boolean update(Account expectedCurrent, Account updated) {
        // Atomic compare-and-swap: succeeds only if the stored object is still the one we read.
        return store.replace(expectedCurrent.accountNumber(), expectedCurrent, updated);
    }

    @Override
    public long count() {
        return store.size();
    }
}
