package com.example.accountservice.dto;

import com.example.accountservice.model.AccountType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank @Pattern(regexp = "^CUST-\\d{4,10}$", message = "must look like CUST-1001") String customerId,
        @NotBlank @Size(min = 2, max = 100) String customerName,
        @NotBlank @Email @Size(max = 100) String email,
        @NotBlank @Pattern(regexp = "^\\d{10}$", message = "must be 10 digits") String mobileNumber,
        @NotNull AccountType accountType,
        @NotBlank @Size(max = 200) String branchAddress,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO code such as USD") String currency,
        @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 2) BigDecimal openingBalance) {
}
