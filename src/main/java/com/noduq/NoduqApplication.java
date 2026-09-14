package com.noduq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NoduqApplication {

	public static void main(String[] args) {
		SpringApplication.run(NoduqApplication.class, args);
	}
}
