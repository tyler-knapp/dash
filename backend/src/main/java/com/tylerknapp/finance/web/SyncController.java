package com.tylerknapp.finance.web;

import com.tylerknapp.finance.sync.SyncResult;
import com.tylerknapp.finance.sync.SyncService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SyncController {
    private final SyncService sync;
    private final ItemQueries queries;

    public SyncController(SyncService sync, ItemQueries queries) {
        this.sync = sync;
        this.queries = queries;
    }

    /** "Sync now": every Item, returns per-Item results. Also how the UI finishes a Reconnect. */
    @PostMapping("/api/sync")
    public Map<String, List<SyncResult>> syncNow() {
        return Map.of("results", sync.syncAll());
    }

    @GetMapping("/api/items")
    public List<ItemQueries.ItemView> items() {
        return queries.items();
    }
}
