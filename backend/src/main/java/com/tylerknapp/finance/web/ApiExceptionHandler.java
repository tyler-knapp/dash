package com.tylerknapp.finance.web;

import com.tylerknapp.finance.config.NotConfiguredException;
import com.tylerknapp.finance.plaid.PlaidApiException;
import com.tylerknapp.finance.sync.ItemNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps app errors to RFC 7807 problem responses. Messages never include tokens or secrets. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PlaidApiException.class)
    ProblemDetail plaid(PlaidApiException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                e.displayMessage() != null ? e.displayMessage() : "Plaid returned " + e.errorCode());
        pd.setTitle("Plaid error");
        pd.setProperty("errorCode", e.errorCode());
        pd.setProperty("requestId", e.requestId());
        return pd;
    }

    @ExceptionHandler(NotConfiguredException.class)
    ProblemDetail notConfigured(NotConfiguredException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        pd.setTitle("Not configured");
        return pd;
    }

    @ExceptionHandler(ItemNotFoundException.class)
    ProblemDetail notFound(ItemNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
