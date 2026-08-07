package io.github.candyxi0.hidenest.worker;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** hide-nest Worker application entry point. */
@SpringBootApplication(scanBasePackages = "io.github.candyxi0.hidenest")
public class HideNestWorkerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HideNestWorkerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(java.util.Map.of("spring.application.name", "hide-nest-worker"));
        app.run(args);
        System.out.println("hide-nest-worker started");
    }
}
