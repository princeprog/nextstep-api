package com.nextstep.api.common;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int pageSize, long total, int totalPages) {

    public static <T> PageResponse<T> of(List<T> items, PageParams params, long total) {
        int totalPages = total == 0 ? 0 : (int) ((total + params.pageSize() - 1) / params.pageSize());
        return new PageResponse<>(items, params.page(), params.pageSize(), total, totalPages);
    }
}

