package com.study.kafka.web;

import java.util.List;

public record TransactionRequest(List<String> messages, boolean rollback) {}
