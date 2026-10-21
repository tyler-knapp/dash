package com.tylerknapp.finance.web;

import com.tylerknapp.finance.link.LinkService;
import com.tylerknapp.finance.sync.SyncResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plaid")
public class PlaidController {
    private final LinkService link;
    private final ItemQueries queries;

    public PlaidController(LinkService link, ItemQueries queries) {
        this.link = link;
        this.queries = queries;
    }

    /** {@code kind}: "bank" (default) or "retirement". Ignored in update mode. */
    public record LinkTokenRequest(UUID itemId, String kind) {}

    public record ExchangeRequest(@NotBlank String publicToken) {}

    public record ExchangeResponse(ItemQueries.ItemView item, SyncResult firstSync) {}

    @PostMapping("/link-token")
    public Map<String, String> linkToken(@RequestBody(required = false) LinkTokenRequest req) {
        UUID itemId = req == null ? null : req.itemId();
        LinkService.Kind kind = req == null || req.kind() == null ? null
                : switch (req.kind()) {
                    case "bank" -> LinkService.Kind.BANK;
                    case "retirement" -> LinkService.Kind.RETIREMENT;
                    default -> throw new IllegalArgumentException("kind must be bank or retirement");
                };
        return Map.of("linkToken", link.createLinkToken(itemId, kind));
    }

    @PostMapping("/exchange")
    public ExchangeResponse exchange(@Valid @RequestBody ExchangeRequest req) {
        LinkService.Linked linked = link.exchange(req.publicToken());
        return new ExchangeResponse(queries.item(linked.itemId()), linked.firstSync());
    }
}
