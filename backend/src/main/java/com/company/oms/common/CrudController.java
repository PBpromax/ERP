package com.company.oms.common;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

public abstract class CrudController<T extends BaseEntity> {
    protected final JpaRepository<T, Long> repository;

    protected CrudController(JpaRepository<T, Long> repository) {
        this.repository = repository;
    }

    @GetMapping
    public ApiResponse<List<T>> list() {
        return ApiResponse.ok(repository.findAll().stream()
                .filter(e -> e.deleted == null || !e.deleted)
                .collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public ApiResponse<T> get(@PathVariable Long id) {
        return ApiResponse.ok(repository.findById(id)
                .filter(e -> e.deleted == null || !e.deleted)
                .orElseThrow(() -> new BusinessException("数据不存在")));
    }

    @PostMapping
    public ApiResponse<T> create(@RequestBody T entity) {
        entity.id = null;
        return ApiResponse.ok(repository.save(entity));
    }

    @PutMapping("/{id}")
    public ApiResponse<T> update(@PathVariable Long id, @RequestBody T entity) {
        repository.findById(id).orElseThrow(() -> new BusinessException("数据不存在"));
        entity.id = id;
        return ApiResponse.ok(repository.save(entity));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        T entity = repository.findById(id).orElseThrow(() -> new BusinessException("数据不存在"));
        repository.delete(entity);
        return ApiResponse.ok();
    }
}
