package com.tylerknapp.finance.categorize;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batch categorization with Claude through the official Anthropic Java SDK (SDK 3.x, API version
 * 2026-10-21). Structured output pins the reply to a JSON shape; the pipeline still validates
 * every answer against the taxonomy. Never logs transaction text or the API key.
 */
public class ClaudeCategorizer implements AiCategorizer {
    private static final Logger log = LoggerFactory.getLogger(ClaudeCategorizer.class);

    private final AnthropicClient client;
    private final String model;
    private final boolean serverFallbacks;
    private final ObjectMapper json = new ObjectMapper();

    public ClaudeCategorizer(AnthropicClient client, String model, boolean serverFallbacks) {
        this.client = client;
        this.model = model;
        this.serverFallbacks = serverFallbacks;
    }

    /** The reply shape, sent to the API as a JSON schema. */
    public record Reply(@JsonPropertyDescription("One entry per input transaction") List<Item> results) {}

    public record Item(
            @JsonPropertyDescription("The id of the input transaction") String id,
            @JsonPropertyDescription("Category name, exactly as listed") String category,
            @JsonPropertyDescription("Subcategory name, exactly as listed under that category") String subcategory,
            @JsonPropertyDescription("Probability from 0 to 1 that this is right") double confidence) {}

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Answer> categorize(List<Input> batch, List<Example> examples, Taxonomy taxonomy) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(systemPrompt(taxonomy, examples))
                .addUserMessage(userMessage(batch));
        if (serverFallbacks) {
            // If a safety classifier declines, the API re-runs the request on a fallback model it picks.
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        StructuredMessageCreateParams<Reply> params = b.outputConfig(StructuredOutputConfig.<Reply>builder()
                .effort(OutputConfig.Effort.LOW) // simple classification
                .format(Reply.class, JsonSchemaLocalValidation.YES)
                .build()).build();

        StructuredMessage<Reply> res;
        try {
            res = client.messages().create(params);
        } catch (UnauthorizedException e) {
            log.error("Claude categorization: ANTHROPIC_API_KEY was rejected (401)");
            throw e;
        } catch (RateLimitException e) {
            log.warn("Claude categorization: rate limited after retries");
            throw e;
        } catch (BadRequestException e) {
            log.error("Claude categorization: request rejected (400, {})", e.errorType().map(Object::toString).orElse("?"));
            throw e;
        } catch (AnthropicServiceException e) {
            log.warn("Claude categorization: API error {} ({})", e.statusCode(), e.errorType().map(Object::toString).orElse("?"));
            throw e;
        } catch (AnthropicIoException e) {
            log.warn("Claude categorization: network error ({})", e.getClass().getSimpleName());
            throw e;
        }

        StopReason stop = res.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            throw new IllegalStateException("Claude declined the categorization request");
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            throw new IllegalStateException("Claude's answer was cut off (max_tokens)");
        }
        List<Answer> out = new ArrayList<>();
        res.content().forEach(block -> block.text().ifPresent(t -> {
            Reply r = t.text();
            if (r != null && r.results() != null) {
                r.results().forEach(i -> out.add(new Answer(i.id(), i.category(), i.subcategory(), i.confidence())));
            }
        }));
        return out;
    }

    static String systemPrompt(Taxonomy taxonomy, List<Example> examples) {
        StringBuilder sb = new StringBuilder("""
                You categorize one person's bank and credit card transactions for their budget.
                Pick exactly one category and subcategory for every transaction, using only these pairs
                (written "Category › Subcategory"; answer with the two names separately, spelled exactly):
                """);
        taxonomy.pairs().forEach(p -> sb.append("- ").append(p).append('\n'));
        sb.append("""

                How to read a transaction:
                - amount: negative means money left the account (a purchase or payment); positive means money arrived
                  (pay, a refund, or a payment received on a credit card).
                - account / account_type: which of the person's accounts it's on ("credit" is a credit card).
                - pfc_*: Plaid's own guess. A useful hint, sometimes wrong or missing.

                Rules:
                - Transfer is for money moving between the person's own accounts: credit card payments (both the payment
                  leaving checking and the payment received on the card), moves between checking and savings, and
                  retirement contributions. A credit card payment is never spending.
                - A refund (positive amount from a store) goes in the category of the original purchase.
                - Income is pay, interest, and money from selling things (resale payouts from eBay, Mercari, StubHub...).
                - confidence is your honest probability of being right. Use less than 0.7 when the merchant sells many kinds
                  of things or the purpose is unclear (Amazon, Target, Venmo), so the person reviews it.
                - Answer once for every id you are given, using the same id.

                Examples of how this person categorizes:
                """);
        for (Example e : examples) {
            sb.append("- \"").append(e.merchant()).append("\" ").append(e.amount().toPlainString())
                    .append(e.accountName() == null ? "" : " on " + e.accountName())
                    .append(" → ").append(e.category()).append(" › ").append(e.subcategory())
                    .append(e.note() == null ? "" : " (" + e.note() + ")").append('\n');
        }
        return sb.toString();
    }

    String userMessage(List<Input> batch) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Input i : batch) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.id());
            m.put("merchant", i.merchant());
            m.put("raw_name", i.rawName());
            m.put("amount", i.amount());
            m.put("account", i.accountName());
            m.put("account_type", i.accountType());
            m.put("pfc_primary", i.pfcPrimary());
            m.put("pfc_detailed", i.pfcDetailed());
            m.put("pfc_confidence", i.pfcConfidence());
            rows.add(m);
        }
        try {
            return "Categorize these transactions:\n" + json.writeValueAsString(Map.of("transactions", rows));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
