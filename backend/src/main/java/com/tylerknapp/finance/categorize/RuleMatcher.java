package com.tylerknapp.finance.categorize;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** User rules from category_rule, by priority (SPEC §7 step 1). Rules are created in Phase 7; until then the table is empty. */
@Component
public class RuleMatcher {
    private final JdbcTemplate jdbc;

    public RuleMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Rule(String matchField, String matchType, String pattern, UUID accountId, String category, String subcategory) {}

    public List<Rule> load() {
        return jdbc.query("select * from category_rule order by priority, id", (rs, i) -> new Rule(rs.getString("match_field"),
                rs.getString("match_type"), rs.getString("pattern"), rs.getObject("account_id", UUID.class),
                rs.getString("category"), rs.getString("subcategory")));
    }

    public static Optional<Rule> match(List<Rule> rules, String merchant, String rawName, UUID accountId, String accountName) {
        for (Rule r : rules) {
            if (r.accountId() != null && !r.accountId().equals(accountId)) {
                continue;
            }
            String value = switch (r.matchField()) {
                case "merchant" -> merchant;
                case "raw_name" -> rawName;
                case "account" -> accountName;
                default -> null;
            };
            if (value != null && matches(r.matchType(), r.pattern(), value)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    static boolean matches(String type, String pattern, String value) {
        String v = value.toLowerCase(Locale.ROOT);
        String p = pattern.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "exact" -> v.equals(p);
            case "contains" -> v.contains(p);
            case "regex" -> {
                try {
                    yield Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(value).find();
                } catch (PatternSyntaxException e) {
                    yield false;
                }
            }
            default -> false;
        };
    }
}
