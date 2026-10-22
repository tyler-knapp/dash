package com.tylerknapp.finance.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PfcMapperTest {
    static final Taxonomy TAX = Taxonomy.of(
            Map.of("Housing", "spend", "Food", "spend", "Transportation", "spend", "Health & Fitness", "spend",
                    "Subscriptions", "spend", "Lifestyle", "spend", "Side Hustles", "spend", "Income", "income", "Transfer", "transfer"),
            Map.of("Housing", List.of("Rent", "Electric", "Internet", "Renters Insurance"),
                    "Food", List.of("Groceries", "Restaurants", "Coffee"),
                    "Transportation", List.of("Gas", "Car Insurance", "Maintenance", "Parking"),
                    "Health & Fitness", List.of("Gym", "Race Fees", "Running Gear", "Supplements", "Medical"),
                    "Subscriptions", List.of("Software & AI", "Streaming", "Research Tools"),
                    "Lifestyle", List.of("Clothing", "Haircuts", "Events", "Travel", "Gifts"),
                    "Side Hustles", List.of("Reselling Inventory", "YouTube Gear"),
                    "Income", List.of("Salary", "Interest", "Reselling Sales"),
                    "Transfer", List.of("Card Payment", "Account Transfer", "Retirement Contribution")));

    static String m(String primary, String detailed) {
        return PfcMapper.specific(primary, detailed).map(x -> x.category() + " › " + x.subcategory()).orElse("-");
    }

    @Test
    void clearCasesFromV1Names() {
        assertThat(m("FOOD_AND_DRINK", "FOOD_AND_DRINK_GROCERIES")).isEqualTo("Food › Groceries");
        assertThat(m("FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE")).isEqualTo("Food › Coffee");
        assertThat(m("FOOD_AND_DRINK", "FOOD_AND_DRINK_RESTAURANT")).isEqualTo("Food › Restaurants");
        assertThat(m("FOOD_AND_DRINK", "FOOD_AND_DRINK_FAST_FOOD")).isEqualTo("Food › Restaurants");
        assertThat(m("TRANSPORTATION", "TRANSPORTATION_GAS")).isEqualTo("Transportation › Gas");
        assertThat(m("TRANSPORTATION", "TRANSPORTATION_PARKING")).isEqualTo("Transportation › Parking");
        assertThat(m("TRANSPORTATION", "TRANSPORTATION_TOLLS")).isEqualTo("Transportation › Parking");
        assertThat(m("GENERAL_SERVICES", "GENERAL_SERVICES_AUTOMOTIVE")).isEqualTo("Transportation › Maintenance");
        assertThat(m("RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT")).isEqualTo("Housing › Rent");
        assertThat(m("RENT_AND_UTILITIES", "RENT_AND_UTILITIES_GAS_AND_ELECTRICITY")).isEqualTo("Housing › Electric");
        assertThat(m("RENT_AND_UTILITIES", "RENT_AND_UTILITIES_INTERNET_AND_CABLE")).isEqualTo("Housing › Internet");
        assertThat(m("ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES")).isEqualTo("Subscriptions › Streaming");
        assertThat(m("ENTERTAINMENT", "ENTERTAINMENT_MUSIC_AND_AUDIO")).isEqualTo("Subscriptions › Streaming");
        assertThat(m("PERSONAL_CARE", "PERSONAL_CARE_GYMS_AND_FITNESS_CENTERS")).isEqualTo("Health & Fitness › Gym");
        assertThat(m("MEDICAL", "MEDICAL_PHARMACIES_AND_SUPPLEMENTS")).isEqualTo("Health & Fitness › Medical");
        assertThat(m("MEDICAL", "MEDICAL_DENTAL_CARE")).isEqualTo("Health & Fitness › Medical");
        assertThat(m("GENERAL_MERCHANDISE", "GENERAL_MERCHANDISE_CLOTHING_AND_ACCESSORIES")).isEqualTo("Lifestyle › Clothing");
        assertThat(m("PERSONAL_CARE", "PERSONAL_CARE_HAIR_AND_BEAUTY")).isEqualTo("Lifestyle › Haircuts");
        assertThat(m("TRAVEL", "TRAVEL_FLIGHTS")).isEqualTo("Lifestyle › Travel");
        assertThat(m("TRAVEL", "TRAVEL_LODGING")).isEqualTo("Lifestyle › Travel");
        assertThat(m("ENTERTAINMENT", "ENTERTAINMENT_SPORTING_EVENTS_AMUSEMENT_PARKS_AND_MUSEUMS")).isEqualTo("Lifestyle › Events");
        assertThat(m("GENERAL_MERCHANDISE", "GENERAL_MERCHANDISE_GIFTS_AND_NOVELTIES")).isEqualTo("Lifestyle › Gifts");
        assertThat(m("INCOME", "INCOME_WAGES")).isEqualTo("Income › Salary");
        assertThat(m("INCOME", "INCOME_INTEREST_EARNED")).isEqualTo("Income › Interest");
        assertThat(m("INCOME", "INCOME_DIVIDENDS")).isEqualTo("Income › Interest");
    }

    @Test
    void namesFromPlaidsV2SpecAndKeywordVariants() {
        assertThat(m("FOOD_RETAIL", "FOOD_RETAIL_GROCERIES")).isEqualTo("Food › Groceries");
        assertThat(m("DINING", "DINING_DINING")).isEqualTo("Food › Restaurants");
        assertThat(m("INCOME", "INCOME_SALARY")).isEqualTo("Income › Salary");
        assertThat(m("TRANSPORTATION", "TRANSPORTATION_FUEL")).isEqualTo("Transportation › Gas");
    }

    @Test
    void ambiguousOnesAreNotClearCases() {
        assertThat(m("GENERAL_MERCHANDISE", "GENERAL_MERCHANDISE_SUPERSTORES")).isEqualTo("-");
        assertThat(m("GENERAL_MERCHANDISE", "GENERAL_MERCHANDISE_ONLINE_MARKETPLACES")).isEqualTo("-");
        assertThat(m("GENERAL_SERVICES", "GENERAL_SERVICES_INSURANCE")).isEqualTo("-");
        assertThat(m("TRANSPORTATION", "TRANSPORTATION_TAXIS_AND_RIDE_SHARES")).isEqualTo("-");
        assertThat(m(null, null)).isEqualTo("-");
    }

    @Test
    void everyMappingIsInTheTaxonomy() {
        PfcMapper.SPECIFIC.forEach(r -> assertThat(TAX.isValid(r.mapping().category(), r.mapping().subcategory())).as(r.toString()).isTrue());
        PfcMapper.PRIMARY_FALLBACK.forEach(r -> assertThat(TAX.isValid(r.mapping().category(), r.mapping().subcategory())).as(r.toString()).isTrue());
        assertThat(TAX.isValid(PfcMapper.MONEY_OUT_DEFAULT.category(), PfcMapper.MONEY_OUT_DEFAULT.subcategory())).isTrue();
        assertThat(TAX.isValid(PfcMapper.MONEY_IN_DEFAULT.category(), PfcMapper.MONEY_IN_DEFAULT.subcategory())).isTrue();
    }

    @Test
    void fallbackAlwaysGivesAValidCategoryWhenPlaidSentOne() {
        for (String p : List.of("BANK_FEES", "ENTERTAINMENT", "FOOD_AND_DRINK", "GENERAL_MERCHANDISE", "HOME_IMPROVEMENT", "MEDICAL",
                "PERSONAL_CARE", "GENERAL_SERVICES", "GOVERNMENT_AND_NON_PROFIT", "TRANSPORTATION", "TRAVEL", "RENT_AND_UTILITIES",
                "INCOME", "TRANSFER_IN", "TRANSFER_OUT", "LOAN_PAYMENTS", "LOAN_DISBURSEMENTS", "SOMETHING_NEW_IN_V3")) {
            for (String amt : List.of("-10", "10")) {
                var f = PfcMapper.fallback(p, p + "_OTHER", new BigDecimal(amt));
                assertThat(f).as(p).isPresent();
                assertThat(TAX.isValid(f.get().category(), f.get().subcategory())).as(p).isTrue();
            }
        }
        assertThat(PfcMapper.fallback(null, null, BigDecimal.ONE)).isEmpty();
    }

    @Test
    void confidenceLevelsFromTheSpec() {
        assertThat(PfcMapper.confidence("VERY_HIGH")).isEqualByComparingTo("0.95");
        assertThat(PfcMapper.confidence("HIGH")).isEqualByComparingTo("0.85");
        assertThat(PfcMapper.confidence("MEDIUM")).isEqualByComparingTo("0.65");
        assertThat(PfcMapper.confidence("LOW")).isEqualByComparingTo("0.4");
        assertThat(PfcMapper.confidence("UNKNOWN")).isEqualByComparingTo("0");
        assertThat(PfcMapper.confidence(null)).isEqualByComparingTo("0");
    }
}
