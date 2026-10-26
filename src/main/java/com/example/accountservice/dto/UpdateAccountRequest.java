package com.example.accountservice.dto;

import com.example.accountservice.model.AccountType;
import jakarta.validation.constraints.*;

/** Fields a customer may change. Account number, customer ID, currency and balance cannot be changed here. */
public record UpdateAccountRequest(
        @NotBlank @Size(min = 2, max = 100) String customerName,
        @NotBlank @Email @Size(max = 100) String email,
        @NotBlank @Pattern(regexp = "^\\d{10}$", message = "must be 10 digits") String mobileNumber,
        @NotNull AccountType accountType,
        @NotBlank @Size(max = 200) String branchAddress) {
}
