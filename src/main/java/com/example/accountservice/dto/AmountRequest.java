package com.example.accountservice.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AmountRequest(
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @Size(max = 140) String reference) {
}
