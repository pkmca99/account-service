package com.example.accountservice.qualitygate;

/**
 * TEMPORARY - only exists to make the SonarQube quality gate FAIL, to prove that a failed gate
 * stops the pipeline before the image is built and deployed. Delete this file after the test.
 *
 * It fails the gate on new code in two ways:
 *  - Reliability: real bugs (String compared with ==, integer division)
 *  - Coverage: 20+ new lines with no unit tests (Sonar only checks coverage when 20+ lines are new)
 */
public final class QualityGateFailureDemo {

    private QualityGateFailureDemo() {
    }

    // Bug java:S4973 - strings compared with == instead of equals()
    public static boolean isSavings(String accountType) {
        return accountType == "SAVINGS";
    }

    // Bug java:S2184 - integer division: 50 / 100 is 0, so this always returns 0 for 0..99
    public static double percentage(int part) {
        double ratio = part / 100;
        return ratio * 100;
    }

    // Bug java:S4973 again, on a different input
    public static boolean isActive(String status) {
        return status == "ACTIVE";
    }

    // Untested code, only here to push new-code coverage below the gate's threshold
    public static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "****";
        }
        String lastFour = accountNumber.substring(accountNumber.length() - 4);
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < accountNumber.length() - 4; i++) {
            masked.append('*');
        }
        return masked.append(lastFour).toString();
    }

    public static String describeBalance(double balance) {
        if (balance < 0) {
            return "overdrawn";
        } else if (balance == 0) {
            return "empty";
        } else if (balance < 1000) {
            return "low";
        } else if (balance < 100000) {
            return "normal";
        }
        return "high";
    }

    public static int countDigits(String value) {
        int digits = 0;
        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) {
                digits++;
            }
        }
        return digits;
    }
}
