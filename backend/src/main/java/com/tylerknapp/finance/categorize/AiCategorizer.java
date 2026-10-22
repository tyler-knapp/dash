package com.tylerknapp.finance.categorize;

import java.math.BigDecimal;
import java.util.List;

/**
 * The model step (SPEC §7 step 4). Implementations return whatever the model said, unvalidated;
 * the pipeline checks every answer against the taxonomy and falls back on anything wrong.
 */
public interface AiCategorizer {

    boolean enabled();

    /** @throws RuntimeException on any failure; the caller falls back for the whole batch */
    List<Answer> categorize(List<Input> batch, List<Example> examples, Taxonomy taxonomy);

    /** {@code amount} is app sign (negative = money out). */
    record Input(String id, String merchant, String rawName, BigDecimal amount, String accountName, String accountType,
                 String pfcPrimary, String pfcDetailed, String pfcConfidence) {}

    record Example(String merchant, BigDecimal amount, String accountName, String category, String subcategory, String note) {}

    record Answer(String id, String category, String subcategory, Double confidence) {}
}
