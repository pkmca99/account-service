package com.example.accountservice.config;

import com.example.accountservice.model.Account;
import com.example.accountservice.model.AccountStatus;
import com.example.accountservice.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Loads the test accounts into the in-memory store when the service starts. */
@Component
public class TestDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TestDataLoader.class);

    private final SeedProperties seed;
    private final AccountRepository repository;
    private final Clock clock;

    public TestDataLoader(SeedProperties seed, AccountRepository repository, Clock clock) {
        this.seed = seed;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seed.enabled()) {
            log.info("Test data loading is disabled");
            return;
        }
        Instant now = Instant.now(clock);
        for (SeedProperties.SeedAccount s : seed.data()) {
            repository.insert(new Account(s.accountNumber(), s.customerId(), s.customerName(), s.email(),
                    s.mobileNumber(), s.accountType(), s.branchAddress(), s.currency(), s.balance(),
                    AccountStatus.ACTIVE, now, now, 0));
        }
        log.info("Loaded {} test accounts", repository.count());
    }
}
