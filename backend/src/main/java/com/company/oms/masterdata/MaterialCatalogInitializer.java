package com.company.oms.masterdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Idempotent catalog initialization: existing quantities, units and warning thresholds are never replaced. */
@Component
@Order(30)
public class MaterialCatalogInitializer implements CommandLineRunner {
    public record Entry(String code, String category, String name) {}
    private final MaterialRepository repository;
    private final ObjectMapper mapper;
    public MaterialCatalogInitializer(MaterialRepository repository, ObjectMapper mapper) {
        this.repository = repository; this.mapper = mapper;
    }
    public List<Entry> catalog() throws java.io.IOException {
        try (var input = new ClassPathResource("material-catalog.json").getInputStream()) {
            return mapper.readValue(input, new TypeReference<List<Entry>>() {});
        }
    }
    @Override @Transactional
    public void run(String... args) throws Exception {
        List<Material> existing = new ArrayList<>(repository.findAll());
        for (Entry e : catalog()) {
            List<Material> matches = existing.stream().filter(m -> e.code().equals(m.code)
                    || e.name().equals(m.name)
                    || (e.category().equals(m.name) && e.name().equals(m.spec))).toList();
            if (!matches.isEmpty()) {
                // Ambiguous legacy matches require human reconciliation; do not create another material.
                if (matches.size() == 1 && (matches.get(0).category == null || matches.get(0).category.isBlank())) {
                    matches.get(0).category = e.category(); repository.save(matches.get(0));
                }
                continue;
            }
            Material m = new Material();
            m.code = e.code(); m.name = e.name(); m.category = e.category();
            m.enabled = true;
            m.remark = "请完善单位、初始数量及预警值。";
            repository.save(m); existing.add(m);
        }
    }
}
