package com.company.oms.masterdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("产品别称保存失败", exception);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) return new ArrayList<>();
        try {
            return new ArrayList<>(OBJECT_MAPPER.readValue(value, STRING_LIST));
        } catch (Exception exception) {
            throw new IllegalArgumentException("产品别称读取失败", exception);
        }
    }
}
