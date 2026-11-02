package com.funnywork;

/** Local wake-up hint only. Durable task state and dispatch remain in the database. */
public record TenantWorkChanged(String tenant) {}
