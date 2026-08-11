package com.agent.web.model;

import java.util.List;

/** 当前用户的模型配置快照。 */
public record ModelConfigurationSnapshot(
        List<ModelProviderRecord> providers,
        List<ModelEndpointRecord> endpoints,
        List<ModelGroupRecord> groups) {

    public ModelConfigurationSnapshot {
        providers = List.copyOf(providers);
        endpoints = List.copyOf(endpoints);
        groups = List.copyOf(groups);
    }
}
