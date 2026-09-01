package com.stackwatch.incident.skills;

import com.stackwatch.incident.toolset.Toolset;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the small, versioned Skill resource set bundled with StackWatch. */
public final class IncidentSkillLoader {

    private static final List<String> DEFAULT_RESOURCE_PATHS = List.of(
        "incident-skills/spring/feign-timeout/SKILL.md",
        "incident-skills/java/null-pointer/SKILL.md",
        "incident-skills/redis/connection-pool-exhaustion/SKILL.md");

    public List<IncidentSkill> loadDefaults() {
        return DEFAULT_RESOURCE_PATHS.stream()
            .map(this::load)
            .sorted(Comparator.comparing(IncidentSkill::id))
            .toList();
    }

    public IncidentSkill load(String resourcePath) {
        try (InputStream input = classLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException("Skill resource was not found: " + resourcePath);
            }
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8), resourcePath);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Could not read Skill resource: " + resourcePath, exception);
        }
    }

    private static IncidentSkill parse(String document, String resourcePath) {
        List<String> lines = document.lines().toList();
        if (lines.size() < 3 || !"---".equals(lines.getFirst())) {
            throw new IllegalArgumentException(
                "Skill resource must begin with front matter: " + resourcePath);
        }
        int frontMatterEnd = findFrontMatterEnd(lines, resourcePath);
        Map<String, List<String>> fields = frontMatter(
            lines.subList(1, frontMatterEnd), resourcePath);
        String instructions = String.join(
            "\n", lines.subList(frontMatterEnd + 1, lines.size())).trim();
        List<Toolset> toolsets = fields.getOrDefault("toolsets", List.of()).stream()
            .map(name -> Toolset.fromConfiguredName(name).orElseThrow(
                () -> new IllegalArgumentException("Unknown Skill Toolset: " + name)))
            .toList();
        return new IncidentSkill(
            single(fields, "id", resourcePath), single(fields, "version", resourcePath),
            fields.getOrDefault("signals", List.of()), toolsets, instructions);
    }

    private static int findFrontMatterEnd(List<String> lines, String resourcePath) {
        for (int index = 1; index < lines.size(); index++) {
            if ("---".equals(lines.get(index))) {
                return index;
            }
        }
        throw new IllegalArgumentException(
            "Skill resource front matter is not terminated: " + resourcePath);
    }

    private static Map<String, List<String>> frontMatter(List<String> lines, String resourcePath) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        String currentKey = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                if (currentKey == null) {
                    throw new IllegalArgumentException(
                        "Skill list item has no key: " + resourcePath);
                }
                fields.get(currentKey).add(trimmed.substring(2).trim());
                continue;
            }
            int separator = trimmed.indexOf(':');
            if (separator < 1) {
                throw new IllegalArgumentException("Invalid Skill front matter: " + resourcePath);
            }
            currentKey = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            List<String> values = new ArrayList<>();
            if (!value.isEmpty()) {
                values.add(value);
            }
            if (fields.putIfAbsent(currentKey, values) != null) {
                throw new IllegalArgumentException(
                    "Duplicate Skill front matter key: " + currentKey);
            }
        }
        return fields;
    }

    private static String single(Map<String, List<String>> fields, String key,
                                 String resourcePath) {
        List<String> values = fields.get(key);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException(
                "Skill resource requires one " + key + ": " + resourcePath);
        }
        return values.getFirst();
    }

    private static ClassLoader classLoader() {
        return IncidentSkillLoader.class.getClassLoader();
    }
}
