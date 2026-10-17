package com.govagent.tool.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * mock 数据中心：启动后从 classpath:mockdata/ 加载，支持写操作产生的状态变更（内存覆盖）。
 * 真实场景替换为调用 RMS/Apollo/扁鹊等治理系统。
 */
@Component
@RequiredArgsConstructor
public class MockDataService {

    private final ObjectMapper objectMapper;
    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();

    public JsonNode get(String name) {
        return cache.computeIfAbsent(name, this::loadJson);
    }

    public String getText(String name) {
        try (InputStream in = getClass().getResourceAsStream("/mockdata/" + name)) {
            if (in == null) {
                throw new IllegalStateException("mock 文本不存在: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 mock 文本失败: " + name, e);
        }
    }

    /** 写操作产生的状态变更：就地修改已缓存的数据 */
    public void mutate(String name, Consumer<ObjectNode> mutator) {
        JsonNode node = get(name);
        if (node instanceof ObjectNode objectNode) {
            mutator.accept(objectNode);
        } else {
            throw new IllegalStateException("mock 数据不是对象，无法变更: " + name);
        }
    }

    private JsonNode loadJson(String name) {
        try (InputStream in = getClass().getResourceAsStream("/mockdata/" + name + ".json")) {
            if (in == null) {
                throw new IllegalStateException("mock 数据不存在: " + name);
            }
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("加载 mock 数据失败: " + name, e);
        }
    }
}
