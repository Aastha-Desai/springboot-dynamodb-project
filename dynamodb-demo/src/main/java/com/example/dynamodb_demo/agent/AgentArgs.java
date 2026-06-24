package com.example.dynamodb_demo.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AgentArgs {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, List<String>> lists = new HashMap<>();
    private final List<String> flags = new ArrayList<>();

    private AgentArgs() {
    }

    static AgentArgs parse(String[] args) {
        AgentArgs parsed = new AgentArgs();
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if (!arg.startsWith("--")) {
                continue;
            }
            String key = arg.substring(2);
            if ("paths".equals(key)) {
                List<String> paths = new ArrayList<>();
                while (index + 1 < args.length && !args[index + 1].startsWith("--")) {
                    paths.add(args[++index]);
                }
                parsed.lists.put(key, paths);
            } else if (index + 1 < args.length && !args[index + 1].startsWith("--")) {
                parsed.values.put(key, args[++index]);
            } else {
                parsed.flags.add(key);
            }
        }
        return parsed;
    }

    String get(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    int getInt(String key, int defaultValue) {
        String value = values.get(key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    boolean has(String key) {
        return flags.contains(key);
    }

    List<String> list(String key, List<String> defaultValue) {
        return lists.getOrDefault(key, defaultValue);
    }
}
