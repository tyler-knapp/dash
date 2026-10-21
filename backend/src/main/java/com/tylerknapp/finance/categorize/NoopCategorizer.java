package com.tylerknapp.finance.categorize;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Placeholder until Phase 4: leaves new transactions uncategorized. */
@Component
public class NoopCategorizer implements Categorizer {
    @Override
    public Map<String, Assignment> categorize(List<Candidate> candidates) {
        return Map.of();
    }
}
