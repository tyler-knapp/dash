package com.tylerknapp.finance.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The request content (no network): taxonomy, rules and examples in the prompt; ids and fields in the user message. */
class ClaudeCategorizerTest {
    @Test
    void systemPromptListsEveryPairAndTheExamples() {
        String p = ClaudeCategorizer.systemPrompt(PfcMapperTest.TAX, CategorizationPipeline.SEED_EXAMPLES);
        PfcMapperTest.TAX.pairs().forEach(pair -> assertThat(p).contains("- " + pair + "\n"));
        assertThat(p).contains("A credit card payment is never spending");
        assertThat(p).contains("\"Kroger Fuel #512\" -48.20 on Huntington Voice Rewards → Transportation › Gas");
        assertThat(p).contains("less than 0.7");
    }

    @Test
    void userMessageCarriesOnlyTheNeededFields() {
        String m = new ClaudeCategorizer(null, "claude-opus-5-5", true).userMessage(List.of(new AiCategorizer.Input("t1", "Uber", "UBER 072515",
                new BigDecimal("-6.33"), "Plaid Credit Card", "credit", "TRANSPORTATION", "TRANSPORTATION_TAXIS_AND_RIDE_SHARES", "VERY_HIGH")));
        assertThat(m).contains("\"id\":\"t1\"").contains("\"amount\":-6.33").contains("\"account_type\":\"credit\"")
                .contains("\"pfc_detailed\":\"TRANSPORTATION_TAXIS_AND_RIDE_SHARES\"");
    }
}
