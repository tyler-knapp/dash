package com.tylerknapp.finance.categorize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TaxonomyRepository {
    private final JdbcTemplate jdbc;

    public TaxonomyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Taxonomy load() {
        Map<String, String> kinds = new LinkedHashMap<>();
        jdbc.query("select name, kind from category order by sort_order", rs -> {
            kinds.put(rs.getString(1), rs.getString(2));
        });
        Map<String, List<String>> subs = new LinkedHashMap<>();
        kinds.keySet().forEach(k -> subs.put(k, new ArrayList<>()));
        jdbc.query("select category_name, name from subcategory order by sort_order", rs -> {
            subs.get(rs.getString(1)).add(rs.getString(2));
        });
        return Taxonomy.of(kinds, subs);
    }
}
