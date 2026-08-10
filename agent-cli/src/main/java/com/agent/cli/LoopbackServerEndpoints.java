package com.agent.cli;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;

/** 为精确 localhost 地址提供显式 IPv6 与 IPv4 回环端点。 */
final class LoopbackServerEndpoints {

    private static final String LOCALHOST = "localhost";

    private LoopbackServerEndpoints() {
    }

    /** 返回显式回环服务端访问顺序。 */
    static List<URI> forServer(URI server) {
        Objects.requireNonNull(server, "server 不能为空");
        if (!LOCALHOST.equals(server.getHost())) {
            return List.of(server);
        }
        return List.of(
                replaceHost(server, "::1"),
                replaceHost(server, "127.0.0.1"));
    }

    private static URI replaceHost(URI server, String host) {
        try {
            return new URI(
                    server.getScheme(),
                    server.getUserInfo(),
                    host,
                    server.getPort(),
                    server.getPath(),
                    server.getQuery(),
                    server.getFragment());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("无法生成回环服务端 URI", exception);
        }
    }
}
