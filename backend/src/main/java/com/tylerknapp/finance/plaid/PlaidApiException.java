package com.tylerknapp.finance.plaid;

/** A Plaid API error. Carries Plaid's error_code and request_id; never tokens or secrets. */
public class PlaidApiException extends RuntimeException {
    public static final String NETWORK_ERROR = "NETWORK_ERROR";

    private final String errorType;
    private final String errorCode;
    private final String displayMessage;
    private final String requestId;

    public PlaidApiException(String errorType, String errorCode, String message, String displayMessage,
                             String requestId) {
        super(errorCode + ": " + message);
        this.errorType = errorType;
        this.errorCode = errorCode;
        this.displayMessage = displayMessage;
        this.requestId = requestId;
    }

    public String errorType() { return errorType; }
    public String errorCode() { return errorCode; }
    public String displayMessage() { return displayMessage; }
    public String requestId() { return requestId; }

    public boolean isLoginRequired() {
        return "ITEM_LOGIN_REQUIRED".equals(errorCode);
    }

    public boolean isMutationDuringPagination() {
        return "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION".equals(errorCode);
    }
}
