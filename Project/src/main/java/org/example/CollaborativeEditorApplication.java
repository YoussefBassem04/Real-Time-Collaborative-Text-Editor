package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CollaborativeEditorApplication {
    static {
        // Workaround for JavaFX + Spring Boot module system
        System.setProperty("spring.main.lazy-initialization", "true");
        System.setProperty("spring.devtools.restart.enabled", "false");
    }

    public static void main(String[] args) {
        SpringApplication.run(CollaborativeEditorApplication.class, args);
    }
}