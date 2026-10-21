package com.tylerknapp.finance.sync;

import com.tylerknapp.finance.config.AppProperties;
import com.tylerknapp.finance.store.ItemRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background syncing: every Item on a schedule (default every 6 hours, New York time), plus
 * follow-up syncs right after linking, because Plaid pulls history for a while after a new link
 * and there are no webhooks to say when it's done.
 */
@Component
public class SyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SyncService sync;
    private final ItemRepository items;
    private final TaskScheduler scheduler;
    private final AppProperties props;

    public SyncScheduler(SyncService sync, ItemRepository items, TaskScheduler scheduler, AppProperties props) {
        this.sync = sync;
        this.items = items;
        this.scheduler = scheduler;
        this.props = props;
    }

    @Scheduled(cron = "${app.sync.cron}", zone = "${app.time-zone}")
    public void scheduledSync() {
        if (props.sync().schedulerEnabled()) {
            runAll();
        }
    }

    /** Sync everything; never throws, so the scheduler thread keeps running. */
    public void runAll() {
        try {
            List<SyncResult> results = sync.syncAll();
            long failed = results.stream().filter(r -> !"ok".equals(r.outcome())).count();
            log.info("Scheduled sync finished: {} items, {} not ok", results.size(), failed);
        } catch (RuntimeException e) {
            // Never let an exception escape the scheduler thread.
            log.error("Scheduled sync failed: {}", e.getClass().getSimpleName(), e);
        }
    }

    /** Re-sync a newly linked Item at the configured delays until Plaid reports full history. */
    public void scheduleFollowUps(UUID itemId) {
        scheduleFollowUp(itemId, 0);
    }

    private void scheduleFollowUp(UUID itemId, int index) {
        var delays = props.sync().followUpDelays();
        if (index >= delays.size()) {
            return;
        }
        scheduler.schedule(() -> {
            try {
                var item = items.find(itemId);
                if (item.isEmpty() || "HISTORICAL_UPDATE_COMPLETE".equals(item.get().transactionsUpdateStatus())
                        || !item.get().hasProduct("transactions")) {
                    return;
                }
                SyncResult r = sync.syncItem(itemId);
                if ("ok".equals(r.outcome()) || "skipped".equals(r.outcome())) {
                    scheduleFollowUp(itemId, index + 1);
                }
            } catch (RuntimeException e) {
                log.warn("Follow-up sync for item {} failed: {}", itemId, e.getClass().getSimpleName());
            }
        }, Instant.now().plus(delays.get(index)));
    }
}
