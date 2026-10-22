package com.tylerknapp.finance.categorize;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Plaid personal finance category (PFC) → our taxonomy (SPEC §7 step 3).
 *
 * <p>Rules match on keywords in the PFC name rather than exact strings, because new Plaid accounts
 * only get the v2 taxonomy, whose full list wasn't reachable while this was built (plaid.com is
 * blocked from the dev environment). Keywords cover the v1 names and the v2 names seen in Plaid's
 * API spec. Unmapped names are logged by the pipeline so the list can be extended after the first
 * real sync.
 */
public final class PfcMapper {
    private PfcMapper() {}

    public record Mapping(String category, String subcategory) {}

    record Rule(Pattern pattern, Mapping mapping) {
        static Rule of(String regex, String category, String subcategory) {
            return new Rule(Pattern.compile(regex), new Mapping(category, subcategory));
        }
    }

    /** Clear cases, used on their own when Plaid is confident (>= 0.85). Checked against the detailed name. */
    static final List<Rule> SPECIFIC = List.of(
            Rule.of("GROCER", "Food", "Groceries"),
            Rule.of("COFFEE", "Food", "Coffee"),
            Rule.of("RESTAURANT|FAST_FOOD|DINING", "Food", "Restaurants"),
            Rule.of("GAS_AND_ELECTRIC|ELECTRICITY|ELECTRIC_UTIL", "Housing", "Electric"),
            Rule.of("^TRANSPORTATION_GAS|GAS_STATION|FUEL", "Transportation", "Gas"),
            Rule.of("PARKING|TOLL", "Transportation", "Parking"),
            Rule.of("AUTOMOTIVE|CAR_REPAIR|VEHICLE_MAINT|CAR_SERVICE", "Transportation", "Maintenance"),
            Rule.of("RENT_AND_UTILITIES_RENT|^RENT$|_RENT$", "Housing", "Rent"),
            Rule.of("INTERNET|CABLE", "Housing", "Internet"),
            Rule.of("TV_AND_MOVIES|MUSIC_AND_AUDIO|STREAMING", "Subscriptions", "Streaming"),
            Rule.of("GYM|FITNESS", "Health & Fitness", "Gym"),
            Rule.of("PHARMAC|^MEDICAL|DOCTOR|DENTAL|EYE_CARE|HOSPITAL", "Health & Fitness", "Medical"),
            Rule.of("CLOTHING|APPAREL", "Lifestyle", "Clothing"),
            Rule.of("HAIR|BARBER|SALON", "Lifestyle", "Haircuts"),
            Rule.of("FLIGHT|AIRLINE|LODGING|HOTEL|RENTAL_CAR", "Lifestyle", "Travel"),
            Rule.of("SPORTING_EVENTS|AMUSEMENT|LIVE_EVENT|CONCERT", "Lifestyle", "Events"),
            Rule.of("GIFT", "Lifestyle", "Gifts"),
            Rule.of("WAGES|SALARY|PAYROLL", "Income", "Salary"),
            Rule.of("INTEREST_EARNED|DIVIDEND", "Income", "Interest"));

    /** Coarse guesses by primary category, used only as a low-confidence fallback (always flagged for review). */
    static final List<Rule> PRIMARY_FALLBACK = List.of(
            Rule.of("^FOOD", "Food", "Restaurants"),
            Rule.of("^ENTERTAINMENT", "Lifestyle", "Events"),
            Rule.of("^TRAVEL", "Lifestyle", "Travel"),
            Rule.of("^TRANSPORTATION", "Transportation", "Parking"),
            Rule.of("^RENT_AND_UTILITIES", "Housing", "Electric"),
            Rule.of("^MEDICAL", "Health & Fitness", "Medical"),
            Rule.of("^PERSONAL_CARE", "Lifestyle", "Haircuts"),
            Rule.of("^INCOME|EARNED_INCOME", "Income", "Reselling Sales"),
            Rule.of("^LOAN|^TRANSFER", "Transfer", "Account Transfer"));

    /** Last resort for anything else (general merchandise, services, fees, home, government...). */
    static final Mapping MONEY_OUT_DEFAULT = new Mapping("Lifestyle", "Gifts");
    static final Mapping MONEY_IN_DEFAULT = new Mapping("Income", "Reselling Sales");

    /** SPEC §7: VERY_HIGH 0.95, HIGH 0.85, MEDIUM 0.65, LOW 0.4; anything else 0. */
    public static BigDecimal confidence(String level) {
        if (level == null) {
            return BigDecimal.ZERO;
        }
        return switch (level.toUpperCase(Locale.ROOT)) {
            case "VERY_HIGH" -> new BigDecimal("0.95");
            case "HIGH" -> new BigDecimal("0.85");
            case "MEDIUM" -> new BigDecimal("0.65");
            case "LOW" -> new BigDecimal("0.4");
            default -> BigDecimal.ZERO;
        };
    }

    /** A clear-case mapping for this PFC, if there is one. */
    public static Optional<Mapping> specific(String primary, String detailed) {
        String name = detailed != null ? detailed : primary;
        if (name == null) {
            return Optional.empty();
        }
        return SPECIFIC.stream().filter(r -> r.pattern().matcher(name).find()).map(Rule::mapping).findFirst();
    }

    /**
     * Best guess for the fallback path: the clear-case mapping, else the primary's default, else a
     * default by direction. Empty only when Plaid sent no category at all.
     */
    public static Optional<Mapping> fallback(String primary, String detailed, BigDecimal appAmount) {
        if (primary == null && detailed == null) {
            return Optional.empty();
        }
        Optional<Mapping> m = specific(primary, detailed);
        if (m.isPresent()) {
            return m;
        }
        String p = primary != null ? primary : detailed;
        return Optional.of(PRIMARY_FALLBACK.stream().filter(r -> r.pattern().matcher(p).find()).map(Rule::mapping)
                .findFirst().orElse(appAmount != null && appAmount.signum() > 0 ? MONEY_IN_DEFAULT : MONEY_OUT_DEFAULT));
    }
}
