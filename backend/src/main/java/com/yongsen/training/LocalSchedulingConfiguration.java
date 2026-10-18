package com.yongsen.training;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The serverless runtime is driven by events and must never start periodic database scans. */
@Configuration
@Profile("!serverless")
@EnableScheduling
public class LocalSchedulingConfiguration {}
