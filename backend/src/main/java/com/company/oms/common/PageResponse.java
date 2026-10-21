package com.company.oms.common;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(List<T> items, long total, int page, int size, int totalPages) {
    public static <T> PageResponse<T> of(List<T> items, Page<?> source) {
        return new PageResponse<>(items, source.getTotalElements(), source.getNumber(), source.getSize(), source.getTotalPages());
    }
}
