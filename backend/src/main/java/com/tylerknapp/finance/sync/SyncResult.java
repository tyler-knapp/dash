package com.tylerknapp.finance.sync;

import java.util.UUID;

/** Per-Item outcome of a sync. {@code outcome}: ok | login_required | error | skipped. */
public record SyncResult(UUID itemId, String institutionName, String outcome, int added, int modified, int removed,
                         String errorCode, String message) {

    static SyncResult ok(UUID id, String name, SyncApplier.Counts c) {
        return new SyncResult(id, name, "ok", c.added(), c.modified(), c.removed(), null, null);
    }

    static SyncResult failed(UUID id, String name, String outcome, String code, String message) {
        return new SyncResult(id, name, outcome, 0, 0, 0, code, message);
    }
}
