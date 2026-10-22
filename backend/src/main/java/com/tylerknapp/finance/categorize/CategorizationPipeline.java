package com.tylerknapp.finance.categorize;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SPEC §7, first match wins:
 * <ol>
 *   <li>User rules (category_rule) → source rule, confidence 1.0.</li>
 *   <li>Transfers: Plaid transfer / card-payment categories and card-payment wording (source plaid),
 *       then pairing equal and opposite amounts across own accounts within 3 days (source rule, 0.9).</li>
 *   <li>Plaid category map for clear cases, only when Plaid's confidence is at least 0.85 (source plaid).</li>
 *   <li>Claude, in batches of up to 50 (source ai). Any invalid answer, or a failed call, falls back to
 *       the Plaid map at confidence 0.3. Without an API key, step 4 is skipped and the same fallback applies.</li>
 * </ol>
 * Anything under 0.7 and not reviewed shows the Review tag in the ledger.
 */
@Component
public class CategorizationPipeline implements Categorizer {
    private static final Logger log = LoggerFactory.getLogger(CategorizationPipeline.class);
    static final BigDecimal PFC_THRESHOLD = new BigDecimal("0.85");
    static final BigDecimal FALLBACK_CONFIDENCE = new BigDecimal("0.3");
    static final int MAX_USER_EXAMPLES = 20;

    private final TaxonomyRepository taxonomy;
    private final RuleMatcher rules;
    private final TransferMatcher transfers;
    private final AiCategorizer ai;
    private final JdbcTemplate jdbc;
    private final int batchSize;

    public CategorizationPipeline(TaxonomyRepository taxonomy, RuleMatcher rules, TransferMatcher transfers,
                                  AiCategorizer ai, JdbcTemplate jdbc, @Value("${app.ai.batch-size}") int batchSize) {
        this.taxonomy = taxonomy;
        this.rules = rules;
        this.transfers = transfers;
        this.ai = ai;
        this.jdbc = jdbc;
        this.batchSize = Math.max(1, Math.min(batchSize, 50));
    }

    @Override
    public Map<String, Assignment> categorize(List<Candidate> candidates) {
        Map<String, Assignment> out = new LinkedHashMap<>();
        if (candidates.isEmpty()) {
            return out;
        }
        Taxonomy tax = taxonomy.load();

        // 1. User rules.
        List<RuleMatcher.Rule> userRules = rules.load();
        for (Candidate c : candidates) {
            RuleMatcher.match(userRules, c.merchantName(), c.rawName(), c.accountId(), c.accountName())
                    .filter(r -> tax.isValid(r.category(), r.subcategory()))
                    .ifPresent(r -> out.put(c.plaidTransactionId(), new Assignment(r.category(), r.subcategory(), "rule", BigDecimal.ONE)));
        }

        // 2a. Transfers Plaid or the wording can identify on their own.
        Set<String> cardNames = cardNames();
        for (Candidate c : candidates) {
            if (out.containsKey(c.plaidTransactionId())) {
                continue;
            }
            TransferDetector.detect(c.pfcPrimary(), c.pfcDetailed(), c.pfcConfidence(), text(c), c.amount(), c.accountType(), cardNames)
                    .ifPresent(d -> out.put(c.plaidTransactionId(),
                            new Assignment(TransferDetector.TRANSFER, d.subcategory(), "plaid", d.confidence())));
        }
        // 2b. Pairs across own accounts (also updates a stored other side).
        List<TransferMatcher.Side> sides = new ArrayList<>();
        for (Candidate c : candidates) {
            if (out.containsKey(c.plaidTransactionId()) && "rule".equals(out.get(c.plaidTransactionId()).source())) {
                continue;
            }
            sides.add(new TransferMatcher.Side(c.plaidTransactionId(), c.accountId(), c.accountType(), c.accountRole(),
                    c.date(), c.amount(), c.pfcPrimary(), c.pfcConfidence()));
        }
        transfers.pair(sides).forEach((id, sub) -> out.putIfAbsent(id,
                new Assignment(TransferDetector.TRANSFER, sub, "rule", TransferMatcher.PAIR_CONFIDENCE)));

        // 3. Plaid's category where it's a clear case and Plaid is confident.
        List<Candidate> rest = new ArrayList<>();
        for (Candidate c : candidates) {
            if (out.containsKey(c.plaidTransactionId())) {
                continue;
            }
            BigDecimal conf = PfcMapper.confidence(c.pfcConfidence());
            Optional<PfcMapper.Mapping> m = PfcMapper.specific(c.pfcPrimary(), c.pfcDetailed());
            if (m.isPresent() && conf.compareTo(PFC_THRESHOLD) >= 0) {
                out.put(c.plaidTransactionId(), new Assignment(m.get().category(), m.get().subcategory(), "plaid", conf));
            } else {
                rest.add(c);
            }
        }

        // 4. Claude for everything else, with a safe fallback.
        if (!rest.isEmpty()) {
            if (ai.enabled()) {
                List<AiCategorizer.Example> examples = examples();
                for (int i = 0; i < rest.size(); i += batchSize) {
                    List<Candidate> batch = rest.subList(i, Math.min(i + batchSize, rest.size()));
                    out.putAll(viaAi(batch, examples, tax));
                }
            } else {
                rest.forEach(c -> fallback(c, false).ifPresent(a -> out.put(c.plaidTransactionId(), a)));
            }
        }
        logUnmapped(candidates);
        return out;
    }

    private Map<String, Assignment> viaAi(List<Candidate> batch, List<AiCategorizer.Example> examples, Taxonomy tax) {
        // Short ids keep the prompt small and keep Plaid ids out of it.
        Map<String, Candidate> byShortId = new LinkedHashMap<>();
        List<AiCategorizer.Input> inputs = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            Candidate c = batch.get(i);
            String id = "t" + (i + 1);
            byShortId.put(id, c);
            inputs.add(new AiCategorizer.Input(id, c.merchantName(), c.rawName(), c.amount(), c.accountName(), c.accountType(),
                    c.pfcPrimary(), c.pfcDetailed(), c.pfcConfidence()));
        }
        Map<String, Assignment> out = new HashMap<>();
        List<AiCategorizer.Answer> answers;
        try {
            answers = ai.categorize(inputs, examples, tax);
        } catch (RuntimeException e) {
            log.warn("AI categorization failed for a batch of {}; using Plaid categories at low confidence ({})",
                    batch.size(), e.getClass().getSimpleName());
            answers = List.of();
        }
        int invalid = 0;
        // An id answered more than once is ambiguous: every answer for it is discarded.
        Map<String, Integer> answerCount = new HashMap<>();
        answers.forEach(a -> { if (a != null && a.id() != null) answerCount.merge(a.id(), 1, Integer::sum); });
        Set<String> answered = new HashSet<>();
        for (AiCategorizer.Answer a : answers) {
            Candidate c = a == null ? null : byShortId.get(a.id());
            if (c == null || answerCount.get(a.id()) > 1) {
                invalid++;
                continue;
            }
            if (!tax.isValid(a.category(), a.subcategory()) || a.confidence() == null || a.confidence().isNaN()
                    || a.confidence() < 0 || a.confidence() > 1) {
                invalid++;
                continue; // falls back below
            }
            answered.add(a.id());
            out.put(c.plaidTransactionId(), new Assignment(a.category(), a.subcategory(), "ai",
                    BigDecimal.valueOf(a.confidence()).setScale(3, java.math.RoundingMode.HALF_UP)));
        }
        int fellBack = 0;
        for (var e : byShortId.entrySet()) {
            if (!answered.contains(e.getKey())) {
                fellBack++;
                fallback(e.getValue(), true).ifPresent(a -> out.put(e.getValue().plaidTransactionId(), a));
            }
        }
        if (invalid > 0 || fellBack > 0) {
            log.info("AI categorization: {} invalid answers, {} of {} transactions fell back to Plaid categories",
                    invalid, fellBack, batch.size());
        }
        return out;
    }

    /**
     * Plaid-map fallback. After a bad or missing model answer: confidence 0.3 (SPEC §7). With no model
     * configured: Plaid's own confidence for a clear case, else 0.3. Either way it stays under 0.7 → Review.
     */
    private Optional<Assignment> fallback(Candidate c, boolean afterAi) {
        if (c.amount().signum() < 0 && TransferDetector.P2P.matcher(text(c)).find()
                && (c.pfcPrimary() != null || c.pfcDetailed() != null)) {
            // A Venmo/Zelle payment is usually spending, not a transfer; guess low and let Tyler review it.
            return Optional.of(new Assignment(PfcMapper.MONEY_OUT_DEFAULT.category(), PfcMapper.MONEY_OUT_DEFAULT.subcategory(),
                    "plaid", FALLBACK_CONFIDENCE));
        }
        return PfcMapper.fallback(c.pfcPrimary(), c.pfcDetailed(), c.amount()).map(m -> {
            BigDecimal conf = FALLBACK_CONFIDENCE;
            if (!afterAi && PfcMapper.specific(c.pfcPrimary(), c.pfcDetailed()).isPresent()) {
                conf = PfcMapper.confidence(c.pfcConfidence()).min(PFC_THRESHOLD.subtract(new BigDecimal("0.2")))
                        .max(FALLBACK_CONFIDENCE);
            }
            return new Assignment(m.category(), m.subcategory(), "plaid", conf);
        });
    }

    /** Words that identify Tyler's credit cards, for spotting card payments on the bank side ("Amex payment"). */
    Set<String> cardNames() {
        Set<String> generic = Set.of("card", "credit", "rewards", "account", "visa", "mastercard", "signature", "preferred",
                "platinum", "gold", "cash", "back", "bank", "plaid", "the");
        Set<String> out = new TreeSet<>();
        jdbc.query("""
                select a.name, i.institution_name from account a left join item i on i.id = a.item_id
                where a.type = 'credit' and not a.is_sample""", rs -> {
            for (String s : new String[] {rs.getString(1), rs.getString(2)}) {
                if (s == null) {
                    continue;
                }
                for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                    if (w.length() >= 4 && !generic.contains(w)) {
                        out.add(w);
                    }
                }
            }
        });
        return out;
    }

    /** Spec-seeded examples for known ambiguous merchants, then up to 20 of Tyler's own corrections. */
    List<AiCategorizer.Example> examples() {
        List<AiCategorizer.Example> out = new ArrayList<>(SEED_EXAMPLES);
        jdbc.query("""
                select coalesce(nullif(t.merchant_name, ''), t.raw_name) as merchant, t.amount, a.name, t.category, t.subcategory
                from transaction t join account a on a.id = t.account_id
                where t.category_source = 'user' and t.category is not null
                order by t.updated_at desc limit ?""", rs -> {
            out.add(new AiCategorizer.Example(rs.getString(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), "corrected by the user"));
        }, MAX_USER_EXAMPLES);
        return out;
    }

    static final List<AiCategorizer.Example> SEED_EXAMPLES = List.of(
            new AiCategorizer.Example("Kroger Fuel #512", new BigDecimal("-48.20"), "Huntington Voice Rewards",
                    "Transportation", "Gas", "a grocery chain's fuel center is gas, not groceries"),
            new AiCategorizer.Example("Plato's Closet", new BigDecimal("-26.00"), "Capital One",
                    "Side Hustles", "Reselling Inventory", "thrift buys are inventory for reselling"),
            new AiCategorizer.Example("Goodwill Polaris", new BigDecimal("-18.50"), "Capital One",
                    "Side Hustles", "Reselling Inventory", "thrift buys are inventory for reselling"),
            new AiCategorizer.Example("Ticketmaster resale", new BigDecimal("-120.00"), "Capital One",
                    "Side Hustles", "Reselling Inventory", "tickets bought to resell"),
            new AiCategorizer.Example("Ticketmaster", new BigDecimal("-85.00"), "Capital One",
                    "Lifestyle", "Events", "tickets to attend"),
            new AiCategorizer.Example("Amazon.com", new BigDecimal("-64.20"), "American Express",
                    "Side Hustles", "YouTube Gear", "ambiguous merchant: low confidence"),
            new AiCategorizer.Example("Target", new BigDecimal("-37.80"), "American Express",
                    "Lifestyle", "Gifts", "ambiguous merchant: low confidence"),
            new AiCategorizer.Example("Venmo payment", new BigDecimal("-40.00"), "Huntington Checking",
                    "Lifestyle", "Gifts", "person-to-person, purpose unknown: low confidence"),
            new AiCategorizer.Example("Mercari payout", new BigDecimal("88.00"), "SoFi Checking",
                    "Income", "Reselling Sales", null),
            new AiCategorizer.Example("Amex payment", new BigDecimal("-187.43"), "Fifth Third Checking",
                    "Transfer", "Card Payment", "paying a credit card is a transfer, never spending"),
            new AiCategorizer.Example("Payment received – thank you", new BigDecimal("412.66"), "Huntington Voice Rewards",
                    "Transfer", "Card Payment", "the same payment as seen on the card"));

    private static String text(Candidate c) {
        return (c.merchantName() == null ? "" : c.merchantName()) + " " + (c.rawName() == null ? "" : c.rawName());
    }

    private void logUnmapped(List<Candidate> candidates) {
        Set<String> unmapped = new TreeSet<>();
        for (Candidate c : candidates) {
            if (c.pfcDetailed() != null && PfcMapper.specific(c.pfcPrimary(), c.pfcDetailed()).isEmpty()
                    && !c.pfcDetailed().startsWith("TRANSFER") && !c.pfcDetailed().contains("CREDIT_CARD_PAYMENT")) {
                unmapped.add(c.pfcDetailed());
            }
        }
        if (!unmapped.isEmpty()) {
            log.info("Plaid categories with no direct mapping (handled by the model or fallback): {}", unmapped);
        }
    }
}
