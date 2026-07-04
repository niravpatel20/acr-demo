package com.acr.demo.controller;

import java.util.Map;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple test REST controller used for quick manual testing.
 */
@RestController
@RequestMapping("/api")
public class HelloController {

    private static final Logger log = LoggerFactory.getLogger(HelloController.class);

    @GetMapping("/hello")
    public Map<String, String> hello() {
        log.info("Received request for /api/hello");
        return Collections.singletonMap("message", "Hello from Demo!");
    }
}

