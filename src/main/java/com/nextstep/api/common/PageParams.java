package com.nextstep.api.common;

/** One-based page and a page size between 1 and 100. */
public record PageParams(int page, int pageSize) {

    public static PageParams parse(String page, String pageSize, FieldErrors errors) {
        int parsedPage = parseInt(page, 1);
        int parsedSize = parseInt(pageSize, 20);
        if (parsedPage < 1) {
            errors.add("page", "Page must be a whole number of 1 or more.");
        }
        if (parsedSize < 1 || parsedSize > 100) {
            errors.add("pageSize", "Page size must be between 1 and 100.");
        }
        return new PageParams(Math.max(parsedPage, 1), Math.min(Math.max(parsedSize, 1), 100));
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public long offset() {
        return (long) (page - 1) * pageSize;
    }
}

