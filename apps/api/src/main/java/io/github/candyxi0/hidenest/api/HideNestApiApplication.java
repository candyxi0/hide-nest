package io.github.candyxi0.hidenest.api;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** hide-nest API application entry point. */
@SpringBootApplication(scanBasePackages = "io.github.candyxi0.hidenest")
public class HideNestApiApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HideNestApiApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(java.util.Map.of("spring.application.name", "hide-nest-api"));
        app.run(args);
        System.out.println("hide-nest-api started");
    }
}
