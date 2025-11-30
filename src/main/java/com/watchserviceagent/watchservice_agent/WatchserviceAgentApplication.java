package com.watchserviceagent.watchservice_agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication
public class WatchserviceAgentApplication {

	public static void main(String[] args) {
		log.info("[Test] - 1");
		SpringApplication.run(WatchserviceAgentApplication.class, args);
	}
}
