package io.quarkus.resteasy.reactive.jackson.deployment.test;

import java.util.List;
import java.util.stream.Collectors;

public class LiveReloadDto {

    private String name;
    private List<String> stackTrace;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.stackTrace = computeStackTrace();
    }

    public List<String> getStackTrace() {
        return stackTrace;
    }

    private static List<String> computeStackTrace() {
        return StackWalker.getInstance()
                .walk(frames -> frames.limit(10)
                        .map(frame -> frame.getClassName() + "." + frame.getMethodName())
                        .collect(Collectors.toList()));
    }
}
