package com.agent.core.command;

import java.util.List;
import java.util.Optional;

/** Slash Command 定义注册表。 */
public interface CommandRegistry {

    /** 原子替换当前命令定义快照。 */
    void replace(List<CommandDefinition> definitions);

    /** 精确查找名称或别名。 */
    Optional<CommandDefinition> find(String name);

    /** 返回按名称排序的不可变定义快照。 */
    List<CommandDefinition> list();

    /** 按前缀返回当前命令快照。 */
    List<CommandDefinition> search(String prefix);

    /** 返回快照修订号。 */
    long revision();
}
