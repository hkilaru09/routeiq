package com.routeiq.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Thin wrapper so callers do not have to handle checked Jackson exceptions. */
@Component
public class Json {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new MalformedMessageException("cannot parse " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    public <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new MalformedMessageException("cannot parse JSON: " + e.getOriginalMessage(), e);
        }
    }

    public Map<String, String> readStringMap(String json) {
        return json == null ? Map.of() : read(json, STRING_MAP);
    }

    public JsonNode tree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new MalformedMessageException("cannot parse JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** A payload that can never be processed; retrying it is pointless. */
    public static class MalformedMessageException extends RuntimeException {
        public MalformedMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
