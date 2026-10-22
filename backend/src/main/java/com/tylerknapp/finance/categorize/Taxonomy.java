package com.tylerknapp.finance.categorize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The category / subcategory list (SPEC §4), as loaded from the database. */
public record Taxonomy(Map<String, String> kinds, Map<String, List<String>> subs) {

    public boolean isValid(String category, String subcategory) {
        return category != null && subcategory != null && subs.getOrDefault(category, List.of()).contains(subcategory);
    }

    public String kind(String category) {
        return kinds.get(category);
    }

    /** "Category › Subcategory" lines, for the model prompt. */
    public List<String> pairs() {
        List<String> out = new ArrayList<>();
        subs.forEach((c, ss) -> ss.forEach(s -> out.add(c + " › " + s)));
        return out;
    }

    public static Taxonomy of(Map<String, String> kinds, Map<String, List<String>> subs) {
        return new Taxonomy(new LinkedHashMap<>(kinds), new LinkedHashMap<>(subs));
    }
}
