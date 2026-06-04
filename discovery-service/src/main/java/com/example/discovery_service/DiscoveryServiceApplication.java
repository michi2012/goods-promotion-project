package com.example.discovery_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;
import reactor.core.publisher.Hooks;

@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServiceApplication {

	public static void main(String[] args) {
		Hooks.enableAutomaticContextPropagation();

		SpringApplication.run(DiscoveryServiceApplication.class, args);
	}

}
