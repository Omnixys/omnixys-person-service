package com.omnixys.person.utils;

import io.github.cdimascio.dotenv.Dotenv;

public class Env {
    static {
        Dotenv dotenv = Dotenv.configure().load();
        dotenv.entries().forEach(entry -> System.setProperty(entry.getKey(), entry.getValue()));
    }
}
