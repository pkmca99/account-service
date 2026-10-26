# account-service

Account microservice with **no database**: accounts live in a thread-safe in-memory store and
5 test accounts are loaded from `application.yml` at startup. Spring Boot 4.1, Java 25.

## Folder structure

```
account-service/
├── docker-compose.yml
├── Dockerfile                    multi-stage: Maven + JDK 25 -> JRE 25, non-root user
├── pom.xml
└── src/
    ├── main/java/com/example/accountservice/
    │   ├── AccountServiceApplication.java
    │   ├── config/        SeedProperties (test data binding), TestDataLoader, ClockConfig
    │   ├── controller/    AccountController (REST, ETag / If-Match)
    │   ├── dto/           CreateAccountRequest, UpdateAccountRequest, AmountRequest (Bean Validation)
    │   ├── exception/     GlobalExceptionHandler (RFC 7807) and business exceptions
    │   ├── model/         Account (immutable record), AccountType, AccountStatus
    │   ├── repository/    AccountRepository interface + InMemoryAccountRepository
    │   └── service/       AccountService (business rules)
    ├── main/resources/application.yml     includes the test accounts
    └── test/java/.../AccountServiceTest.java
```

## Run

```powershell
cd account-service
docker compose up -d --build
docker compose logs -f account-service     # look for "Loaded 5 test accounts"
```

Or in IntelliJ: run `AccountServiceApplication` (port 8080).

- API: http://localhost:8080/api/v1/accounts
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health

Data is in memory, so a restart resets it to the test accounts.

## Test accounts

| Account | Customer | Type | Balance |
|---|---|---|---|
| 1000000001 | CUST-1001 Amit Sharma | SAVINGS | 15,250.75 USD |
| 1000000002 | CUST-1001 Amit Sharma | CURRENT | 2,400.00 USD |
| 1000000003 | CUST-1002 Priya Patel | SAVINGS | 98,000.00 USD |
| 1000000004 | CUST-1003 John Miller | SAVINGS | 520.10 GBP |
| 1000000005 | CUST-1004 Sara Lee | CURRENT | 0.00 USD |

## API

| Method and path | What it does |
|---|---|
| GET /api/v1/accounts?customerId=&status= | List accounts, optional filters |
| GET /api/v1/accounts/{accountNumber} | One account; ETag header = version |
| POST /api/v1/accounts | Open an account (201 + Location header) |
| PUT /api/v1/accounts/{accountNumber} | Update contact details; send If-Match to avoid lost updates |
| POST /api/v1/accounts/{accountNumber}/deposit | Add money |
| POST /api/v1/accounts/{accountNumber}/withdraw | Take money out (422 if not enough balance) |
| POST /api/v1/accounts/{accountNumber}/freeze, /unfreeze | Block or unblock transactions |
| DELETE /api/v1/accounts/{accountNumber} | Close the account (balance must be zero; never physically deleted) |

## Try it (PowerShell)

```powershell
curl.exe http://localhost:8080/api/v1/accounts
curl.exe -i http://localhost:8080/api/v1/accounts/1000000001

curl.exe -X POST http://localhost:8080/api/v1/accounts -H "Content-Type: application/json" -d '{\"customerId\":\"CUST-1005\",\"customerName\":\"Ravi Kumar\",\"email\":\"ravi@example.com\",\"mobileNumber\":\"9811122233\",\"accountType\":\"SAVINGS\",\"branchAddress\":\"5 Park Avenue, New York\",\"currency\":\"USD\",\"openingBalance\":1000}'

curl.exe -X POST http://localhost:8080/api/v1/accounts/1000000001/withdraw -H "Content-Type: application/json" -d '{\"amount\":250.75,\"reference\":\"ATM\"}'

curl.exe -X PUT http://localhost:8080/api/v1/accounts/1000000003 -H "Content-Type: application/json" -H "If-Match: \"0\"" -d '{\"customerName\":\"Priya Patel\",\"email\":\"priya.p@example.com\",\"mobileNumber\":\"9123456780\",\"accountType\":\"SAVINGS\",\"branchAddress\":\"45 Lake Shore Drive, Chicago\"}'
```

## Design notes

- **Swappable storage:** the service depends on the `AccountRepository` interface. Adding a database later
  means a new implementation (JPA) only; controller and service do not change.
- **Thread safety without locks:** `Account` is immutable; every change is a compare-and-swap
  (`ConcurrentHashMap.replace(key, old, new)`), retried a few times if two requests collide.
- **Optimistic locking:** each change increments `version`; GET returns it as the ETag, and a PUT with a
  stale `If-Match` gets 412.
- **Server-side validation:** Bean Validation on every request plus business rules in the service
  (one open account per customer and type, no overdraft, frozen/closed accounts cannot transact).
- **Errors:** RFC 7807 problem details with a `code` field; no stack traces.
