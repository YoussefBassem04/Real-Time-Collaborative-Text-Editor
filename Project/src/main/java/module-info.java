module org.example.collaborativeeditor {
    // JavaFX
    requires javafx.controls;
    requires javafx.fxml;

    // Spring Boot
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.context;
    requires spring.web;
    requires spring.websocket;
    requires spring.messaging;
    requires spring.beans;
    requires spring.core;

    // Jackson
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires java.desktop;
    requires json;

    // Open packages for Spring reflection
    opens org.example to spring.core, spring.beans, spring.context, spring.web;
    opens org.example.client to javafx.fxml, spring.web;
    opens org.example.controller to spring.web;
    opens org.example.config to spring.core;
    opens org.example.service to spring.beans;
    opens org.example.crdt to com.fasterxml.jackson.databind;

    // Export public API
    exports org.example;
    exports org.example.client;
    exports org.example.controller;
    exports org.example.config;
    exports org.example.service;
    exports org.example.model;
    exports org.example.crdt;
    opens org.example.model to com.fasterxml.jackson.databind, javafx.fxml, spring.core, spring.web;
}