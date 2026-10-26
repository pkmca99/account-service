package com.example.accountservice.config;

import com.example.accountservice.model.AccountType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/** Test accounts loaded at startup, defined under accounts.seed in application.yml. */
@ConfigurationProperties(prefix = "accounts.seed")
public record SeedProperties(boolean enabled, List<SeedAccount> data) {

    public SeedProperties {
        data = data == null ? List.of() : List.copyOf(data);
    }

    public record SeedAccount(String accountNumber, String customerId, String customerName, String email,
                              String mobileNumber, AccountType accountType, String branchAddress,
                              String currency, BigDecimal balance) {
    }
}
