package com.tylerknapp.finance.plaid;

import com.google.gson.Gson;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountsGetRequest;
import com.plaid.client.model.CountryCode;
import com.plaid.client.model.Holding;
import com.plaid.client.model.InstitutionsGetByIdRequest;
import com.plaid.client.model.InvestmentsHoldingsGetRequest;
import com.plaid.client.model.ItemGetRequest;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemWithConsentFields;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateRequestUser;
import com.plaid.client.model.LinkTokenTransactions;
import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.PersonalFinanceCategoryVersion;
import com.plaid.client.model.PlaidError;
import com.plaid.client.model.Products;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.SandboxItemResetLoginRequest;
import com.plaid.client.model.SandboxPublicTokenCreateRequest;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncRequestOptions;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Call;
import retrofit2.Response;

/** {@link PlaidGateway} over the official plaid-java client. Logs Plaid request_id on every error. */
public class PlaidSdkGateway implements PlaidGateway {
    private static final Logger log = LoggerFactory.getLogger(PlaidSdkGateway.class);
    private static final Gson GSON = new Gson();

    private final PlaidApi api;
    private final boolean sandbox;

    public PlaidSdkGateway(PlaidApi api, boolean sandbox) {
        this.api = api;
        this.sandbox = sandbox;
    }

    @Override
    public String createLinkToken(LinkTokenSpec spec) {
        LinkTokenCreateRequest req = new LinkTokenCreateRequest()
                .clientName("Personal Finance")
                .language("en")
                .countryCodes(List.of(CountryCode.US))
                .user(new LinkTokenCreateRequestUser().clientUserId(spec.clientUserId()));
        if (spec.accessToken() != null) {
            req.accessToken(spec.accessToken()); // update mode: no products
        } else {
            req.products(spec.products().stream().map(Products::fromValue).toList());
            if (spec.products().contains("transactions")) {
                req.transactions(new LinkTokenTransactions().daysRequested(spec.daysRequested()));
            }
        }
        if (spec.redirectUri() != null && !spec.redirectUri().isBlank()) {
            req.redirectUri(spec.redirectUri());
        }
        return call("link/token/create", api.linkTokenCreate(req)).getLinkToken();
    }

    @Override
    public Exchanged exchangePublicToken(String publicToken) {
        var res = call("item/public_token/exchange",
                api.itemPublicTokenExchange(new ItemPublicTokenExchangeRequest().publicToken(publicToken)));
        return new Exchanged(res.getAccessToken(), res.getItemId());
    }

    @Override
    public ItemInfo getItem(String accessToken) {
        ItemWithConsentFields item = call("item/get", api.itemGet(new ItemGetRequest().accessToken(accessToken))).getItem();
        String name = item.getInstitutionName();
        if ((name == null || name.isBlank()) && item.getInstitutionId() != null) {
            name = call("institutions/get_by_id", api.institutionsGetById(new InstitutionsGetByIdRequest()
                    .institutionId(item.getInstitutionId()).countryCodes(List.of(CountryCode.US))))
                    .getInstitution().getName();
        }
        List<Products> products = item.getProducts() != null ? item.getProducts() : item.getBilledProducts();
        return new ItemInfo(item.getItemId(), item.getInstitutionId(), name,
                products == null ? List.of() : products.stream().map(Products::getValue).toList());
    }

    @Override
    public SyncPage syncTransactions(String accessToken, String cursor, int count, int daysRequested) {
        TransactionsSyncRequest req = new TransactionsSyncRequest()
                .accessToken(accessToken)
                .count(count)
                .options(new TransactionsSyncRequestOptions()
                        .includePersonalFinanceCategory(true)
                        .personalFinanceCategoryVersion(PersonalFinanceCategoryVersion.V2)
                        .daysRequested(daysRequested));
        if (cursor != null && !cursor.isEmpty()) {
            req.cursor(cursor);
        }
        TransactionsSyncResponse res = call("transactions/sync", api.transactionsSync(req));
        return new SyncPage(
                res.getAdded().stream().map(PlaidSdkGateway::toTxn).toList(),
                res.getModified().stream().map(PlaidSdkGateway::toTxn).toList(),
                res.getRemoved().stream().map(RemovedTransaction::getTransactionId).filter(Objects::nonNull).toList(),
                res.getNextCursor(),
                Boolean.TRUE.equals(res.getHasMore()),
                res.getTransactionsUpdateStatus() == null ? null : res.getTransactionsUpdateStatus().getValue());
    }

    @Override
    public List<Account> getAccounts(String accessToken) {
        return call("accounts/get", api.accountsGet(new AccountsGetRequest().accessToken(accessToken)))
                .getAccounts().stream().map(PlaidSdkGateway::toAccount).toList();
    }

    @Override
    public Map<String, BigDecimal> holdingsValueByAccount(String accessToken) {
        var res = call("investments/holdings/get",
                api.investmentsHoldingsGet(new InvestmentsHoldingsGetRequest().accessToken(accessToken)));
        Map<String, BigDecimal> out = new HashMap<>();
        for (Holding h : res.getHoldings()) {
            if (h.getInstitutionValue() != null) {
                out.merge(h.getAccountId(), money(h.getInstitutionValue()), BigDecimal::add);
            }
        }
        return out;
    }

    @Override
    public String sandboxCreatePublicToken(String institutionId, List<String> products) {
        requireSandbox();
        return call("sandbox/public_token/create", api.sandboxPublicTokenCreate(new SandboxPublicTokenCreateRequest()
                .institutionId(institutionId)
                .initialProducts(products.stream().map(Products::fromValue).toList()))).getPublicToken();
    }

    @Override
    public void sandboxResetLogin(String accessToken) {
        requireSandbox();
        call("sandbox/item/reset_login", api.sandboxItemResetLogin(new SandboxItemResetLoginRequest().accessToken(accessToken)));
    }

    private void requireSandbox() {
        if (!sandbox) {
            throw new IllegalStateException("Sandbox-only Plaid call attempted outside sandbox");
        }
    }

    static Txn toTxn(Transaction t) {
        PersonalFinanceCategory pfc = t.getPersonalFinanceCategory();
        return new Txn(t.getTransactionId(), t.getAccountId(), t.getPendingTransactionId(), t.getDate(),
                t.getAuthorizedDate(), t.getMerchantName(), t.getName(),
                t.getAmount() == null ? null : money(t.getAmount()), Boolean.TRUE.equals(t.getPending()),
                pfc == null ? null : pfc.getPrimary(), pfc == null ? null : pfc.getDetailed(),
                pfc == null ? null : pfc.getConfidenceLevel());
    }

    static Account toAccount(AccountBase a) {
        AccountBalance b = a.getBalances();
        return new Account(a.getAccountId(), a.getName(), a.getOfficialName(), a.getMask(),
                a.getType() == null ? null : a.getType().getValue(),
                a.getSubtype() == null ? null : a.getSubtype().getValue(),
                b == null || b.getCurrent() == null ? null : money(b.getCurrent()),
                b == null || b.getAvailable() == null ? null : money(b.getAvailable()));
    }

    /** Plaid sends doubles; BigDecimal.valueOf uses the shortest decimal string, so 12.34 stays 12.34. */
    static BigDecimal money(double d) {
        return BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP);
    }

    private <T> T call(String endpoint, Call<T> call) {
        Response<T> res;
        try {
            res = call.execute();
        } catch (IOException e) {
            log.warn("Plaid {} network error: {}", endpoint, e.getClass().getSimpleName());
            throw new PlaidApiException("API_ERROR", PlaidApiException.NETWORK_ERROR,
                    "Couldn't reach Plaid", "Couldn't reach Plaid. Try again in a minute.", null);
        }
        if (res.isSuccessful() && res.body() != null) {
            return res.body();
        }
        PlaidError err = null;
        try (var body = res.errorBody()) {
            if (body != null) {
                err = GSON.fromJson(body.string(), PlaidError.class);
            }
        } catch (Exception ignored) {
            // fall through to a generic error
        }
        String type = err != null && err.getErrorType() != null ? err.getErrorType().getValue() : "API_ERROR";
        String code = err != null && err.getErrorCode() != null ? err.getErrorCode() : "HTTP_" + res.code();
        String msg = err != null ? err.getErrorMessage() : "HTTP " + res.code();
        String display = err != null ? err.getDisplayMessage() : null;
        String requestId = err != null ? err.getRequestId() : res.headers().get("x-request-id");
        log.warn("Plaid {} failed: error_type={} error_code={} request_id={}", endpoint, type, code, requestId);
        throw new PlaidApiException(type, code, msg, display, requestId);
    }
}
