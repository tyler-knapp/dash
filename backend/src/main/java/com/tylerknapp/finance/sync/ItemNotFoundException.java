package com.tylerknapp.finance.sync;

import java.util.UUID;

public class ItemNotFoundException extends RuntimeException {
    public ItemNotFoundException(UUID id) {
        super("No linked institution with id " + id);
    }
}
