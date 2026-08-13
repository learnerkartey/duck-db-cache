package com.enterprise.datacache.app.dto;

/** Uniform error body for all REST endpoints. Never includes stack traces, SQL text, or credentials. */
public record ErrorResponse(String errorCode, String message) {
}
