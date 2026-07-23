package com.powsybl.network.store.server;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
@SpringBootApplication
public class NetworkStoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetworkStoreApplication.class, args);
    }

    @Bean
    public Module module() {
        return new JavaTimeModule();
    }

    @Bean
    public Module blackbirdModule() {
        // replaces Jackson's reflection-based property access with generated MethodHandles;
        // registered on the auto-configured mapper, so both the repository's DB-column
        // deserialization and the HTTP response serialization benefit
        return new BlackbirdModule();
    }
}
