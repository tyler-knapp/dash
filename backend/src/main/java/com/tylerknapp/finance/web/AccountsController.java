package com.tylerknapp.finance.web;

import com.tylerknapp.finance.config.AppProperties;
import com.tylerknapp.finance.store.AccountRepository;
import com.tylerknapp.finance.store.BalanceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Accounts (SPEC §8): list, manual accounts and their balances, and rename / color / hide for any account. */
@RestController
public class AccountsController {
    private final JdbcTemplate jdbc;
    private final BalanceRepository balances;
    private final Clock clock;
    private final AppProperties props;

    public AccountsController(JdbcTemplate jdbc, BalanceRepository balances, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.balances = balances;
        this.clock = clock;
        this.props = props;
    }

    public record AccountRow(UUID id, UUID itemId, String name, String institutionName, String mask, String type, String subtype,
                             String role, String displayColor, boolean hidden, boolean isManual, boolean isSample,
                             BigDecimal currentBalance, LocalDate balanceAsOf) {}

    @GetMapping("/api/accounts")
    public List<AccountRow> accounts() {
        return jdbc.query(SELECT + " order by a.is_sample, a.role, a.created_at, a.name", AccountsController::map);
    }

    public record NewManualAccount(@NotBlank @Size(max = 100) String name,
                                   @NotNull @Pattern(regexp = "depository|credit|investment|loan|other") String type,
                                   @Size(max = 50) String subtype,
                                   @Pattern(regexp = "ledger|balance_only") String role) {}

    /** A manual account for anything that won't link (e.g. a former employer's 401(k)). Balances are entered by hand. */
    @PostMapping("/api/accounts/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountRow createManual(@Valid @RequestBody NewManualAccount body) {
        Integer n = jdbc.queryForObject("select count(*) from account", Integer.class);
        String color = AccountRepository.PALETTE.get((n == null ? 0 : n) % AccountRepository.PALETTE.size());
        UUID id = jdbc.queryForObject("""
                insert into account (name, type, subtype, role, display_color, is_manual)
                values (?, ?, ?, ?, ?, true) returning id""", UUID.class,
                body.name().trim(), body.type(), blankToNull(body.subtype()),
                body.role() == null ? "balance_only" : body.role(), color);
        return find(id);
    }

    public record AccountPatch(@Size(min = 1, max = 100) String name,
                               @Pattern(regexp = "#[0-9a-fA-F]{6}", message = "must be a color like #1f6b5c") String displayColor,
                               Boolean hidden) {}

    @PatchMapping("/api/accounts/{id}")
    @Transactional
    public AccountRow patch(@PathVariable UUID id, @Valid @RequestBody AccountPatch body) {
        find(id); // 404 if missing
        if (body.name() != null) {
            if (body.name().isBlank()) {
                throw new IllegalArgumentException("name can't be blank");
            }
            jdbc.update("update account set name = ? where id = ?", body.name().trim(), id);
        }
        if (body.displayColor() != null) {
            jdbc.update("update account set display_color = ? where id = ?", body.displayColor().toLowerCase(), id);
        }
        if (body.hidden() != null) {
            jdbc.update("update account set hidden = ? where id = ?", body.hidden(), id);
        }
        return find(id);
    }

    public record ManualBalance(@NotNull LocalDate asOf,
                                @NotNull @DecimalMin("-999999999999") @DecimalMax("999999999999") @Digits(integer = 12, fraction = 2) BigDecimal current) {}

    /** Enter a balance for a manual account, in app sign (what you owe is negative). One per day; a second entry replaces it. */
    @PostMapping("/api/accounts/{id}/balance")
    @Transactional
    public AccountRow enterBalance(@PathVariable UUID id, @Valid @RequestBody ManualBalance body) {
        AccountRow a = find(id);
        if (!a.isManual()) {
            throw new IllegalArgumentException("Balances for linked accounts come from the bank; only manual accounts take entered balances");
        }
        LocalDate today = LocalDate.now(clock.withZone(props.timeZone()));
        if (body.asOf().isAfter(today)) {
            throw new IllegalArgumentException("asOf can't be in the future");
        }
        if (body.asOf().isBefore(LocalDate.of(1990, 1, 1))) {
            throw new IllegalArgumentException("asOf is too far in the past");
        }
        balances.upsert(id, body.asOf(), body.current(), null, "manual");
        return find(id);
    }

    private AccountRow find(UUID id) {
        return jdbc.query(SELECT + " where a.id = ?", AccountsController::map, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No account with id " + id));
    }

    private static final String SELECT = """
            select a.*, i.institution_name, b.current, b.as_of
            from account a
            left join item i on i.id = a.item_id
            left join lateral (select current, as_of from balance_snapshot s
                               where s.account_id = a.id order by as_of desc limit 1) b on true
            """;

    private static AccountRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new AccountRow(rs.getObject("id", UUID.class), rs.getObject("item_id", UUID.class), rs.getString("name"), rs.getString("institution_name"),
                rs.getString("mask"), rs.getString("type"), rs.getString("subtype"), rs.getString("role"),
                rs.getString("display_color"), rs.getBoolean("hidden"), rs.getBoolean("is_manual"), rs.getBoolean("is_sample"),
                rs.getBigDecimal("current"), rs.getObject("as_of", LocalDate.class));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
