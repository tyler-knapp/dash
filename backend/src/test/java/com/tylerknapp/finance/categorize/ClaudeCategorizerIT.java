package com.tylerknapp.finance.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Against the real Claude API. Runs only with ANTHROPIC_IT=true and ANTHROPIC_API_KEY set, so CI
 * without a key still passes. Sends the sandbox-like fixture (fake data) once (~a cent).
 *
 * <p>Run: {@code ANTHROPIC_IT=true ANTHROPIC_API_KEY=... ./gradlew test --tests '*ClaudeCategorizerIT'}
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_IT", matches = "true")
class ClaudeCategorizerIT {
    @Test
    void realModelAnswersEveryTransactionWithAValidCategory() throws Exception {
        JsonNode fx = new ObjectMapper().readTree(getClass().getResourceAsStream("/categorize/sandbox-like.json"));
        Map<String, String> acctNames = Map.of("chk", "Plaid Checking", "sav", "Plaid Saving", "cc", "Plaid Credit Card");
        Map<String, String> acctTypes = Map.of("chk", "depository", "sav", "depository", "cc", "credit");
        List<AiCategorizer.Input> inputs = new ArrayList<>();
        int n = 0;
        for (JsonNode t : fx.path("transactions")) {
            String a = t.path("account").asText();
            inputs.add(new AiCategorizer.Input("t" + (++n), t.path("merchant").isNull() ? null : t.path("merchant").asText(),
                    t.path("name").asText(), new BigDecimal(t.path("plaidAmount").asText()).negate(), acctNames.get(a), acctTypes.get(a),
                    t.path("pfc").get(0).asText(), t.path("pfc").get(1).asText(), t.path("pfc").get(2).asText()));
        }
        var ai = new ClaudeCategorizer(AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).build(),
                System.getenv().getOrDefault("AI_MODEL", "claude-opus-5-5"), true);

        List<AiCategorizer.Answer> answers = ai.categorize(inputs, CategorizationPipeline.SEED_EXAMPLES, PfcMapperTest.TAX);

        assertThat(answers).extracting(AiCategorizer.Answer::id).containsExactlyInAnyOrderElementsOf(inputs.stream().map(AiCategorizer.Input::id).toList());
        assertThat(answers).allSatisfy(x -> {
            assertThat(PfcMapperTest.TAX.isValid(x.category(), x.subcategory())).as(x.toString()).isTrue();
            assertThat(x.confidence()).isBetween(0.0, 1.0);
        });
        // Card payments (s03, s06) come back as transfers.
        assertThat(answers.stream().filter(x -> x.id().equals("t3") || x.id().equals("t6")))
                .allSatisfy(x -> assertThat(x.category()).isEqualTo("Transfer"));
    }
}
