package com.agent.core.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** 使用冻结快照实现来源覆盖和别名解析的进程内注册表。 */
public final class InMemoryCommandRegistry implements CommandRegistry {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Map<String, CommandDefinition> definitions = Map.of();
    private Map<String, CommandDefinition> names = Map.of();
    private long revision;

    /** 原子校验、合并并替换所有来源定义。 */
    @Override
    public void replace(List<CommandDefinition> input) {
        Objects.requireNonNull(input, "definitions 不能为空");
        Map<String, CommandDefinition> selected = selectDefinitions(input);
        Map<String, CommandDefinition> resolvedNames = resolveNames(selected);
        lock.writeLock().lock();
        try {
            definitions = Map.copyOf(selected);
            names = Map.copyOf(resolvedNames);
            revision++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<CommandDefinition> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            return Optional.ofNullable(names.get(name));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CommandDefinition> list() {
        lock.readLock().lock();
        try {
            return definitions.values().stream()
                    .sorted(Comparator.comparing(CommandDefinition::name))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CommandDefinition> search(String prefix) {
        String exactPrefix = prefix == null ? "" : prefix;
        return list().stream()
                .filter(definition -> definition.name().startsWith(exactPrefix)
                        || definition.aliases().stream().anyMatch(alias -> alias.startsWith(exactPrefix)))
                .toList();
    }

    @Override
    public long revision() {
        lock.readLock().lock();
        try {
            return revision;
        } finally {
            lock.readLock().unlock();
        }
    }

    private Map<String, CommandDefinition> selectDefinitions(List<CommandDefinition> input) {
        Map<String, CommandDefinition> selected = new HashMap<>();
        Map<CommandSource, Map<String, CommandDefinition>> bySource = new HashMap<>();
        for (CommandDefinition definition : input) {
            Objects.requireNonNull(definition, "definitions 不能包含 null");
            Map<String, CommandDefinition> sourceDefinitions = bySource.computeIfAbsent(
                    definition.source(), ignored -> new HashMap<>());
            if (sourceDefinitions.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalArgumentException("同一来源存在重复命令: " + definition.name());
            }
            CommandDefinition previous = selected.get(definition.name());
            if (previous == null || definition.source().priority() > previous.source().priority()) {
                selected.put(definition.name(), definition);
            }
        }
        return selected;
    }

    private Map<String, CommandDefinition> resolveNames(Map<String, CommandDefinition> selected) {
        Map<String, CommandDefinition> resolved = new LinkedHashMap<>();
        selected.values().stream()
                .sorted(Comparator.comparing(CommandDefinition::name))
                .forEach(definition -> addName(resolved, definition.name(), definition));
        selected.values().stream()
                .sorted(Comparator.comparingInt(value -> value.source().priority()))
                .forEach(definition -> definition.aliases()
                        .forEach(alias -> addAlias(resolved, alias, definition)));
        return resolved;
    }

    private void addName(Map<String, CommandDefinition> resolved, String name, CommandDefinition definition) {
        CommandDefinition previous = resolved.putIfAbsent(name, definition);
        if (previous != null && previous != definition) {
            throw new IllegalArgumentException("命令名称冲突: " + name);
        }
    }

    private void addAlias(Map<String, CommandDefinition> resolved, String alias, CommandDefinition definition) {
        CommandDefinition previous = resolved.get(alias);
        if (previous == null || previous == definition
                || definition.source().priority() > previous.source().priority()) {
            resolved.put(alias, definition);
            return;
        }
        if (definition.source().priority() == previous.source().priority()
                && !previous.name().equals(definition.name())) {
            throw new IllegalArgumentException("命令别名冲突: " + alias);
        }
    }
}
