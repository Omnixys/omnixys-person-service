package com.omnixys.person.models;

@FunctionalInterface
public interface LogCallback {
    void log(String level, String message, String context);
}
