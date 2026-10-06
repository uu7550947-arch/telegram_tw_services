package com.example.tw_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TwServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(TwServiceApplication.class, args);
		System.out.println("Twitter Account Status Checker Bot is running...");
	}

}
