package com.tylerknapp.finance.networth;

import java.util.Locale;

/** How an account is labeled and grouped on the Net worth tab (the prototype's kinds and groups). */
public final class AccountKinds {
    private AccountKinds() {}

    public static final String CASH = "Cash & savings";
    public static final String CARDS = "Credit cards";
    public static final String RETIREMENT = "Retirement";
    public static final String OTHER = "Other";
    static final java.util.List<String> GROUP_ORDER = java.util.List.of(CASH, CARDS, RETIREMENT, OTHER);

    /** Prototype kinds: Checking, Savings, Money market, Credit card, Retirement (plus Loan / Other). */
    public static String kind(String type, String subtype) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        String s = subtype == null ? "" : subtype.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "credit" -> "Credit card";
            case "investment", "brokerage" -> "Retirement";
            case "loan" -> "Loan";
            case "depository" -> s.contains("money market") || s.contains("cash management") ? "Money market"
                    : s.contains("saving") || s.equals("cd") || s.equals("hsa") ? "Savings" : "Checking";
            default -> "Other";
        };
    }

    public static String group(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "depository" -> CASH;
            case "credit" -> CARDS;
            case "investment", "brokerage" -> RETIREMENT;
            default -> OTHER;
        };
    }

    /** Order within a group: checking, savings, money market, then the rest. */
    static int kindRank(String kind) {
        return switch (kind) {
            case "Checking" -> 0;
            case "Savings" -> 1;
            case "Money market" -> 2;
            default -> 3;
        };
    }
}
