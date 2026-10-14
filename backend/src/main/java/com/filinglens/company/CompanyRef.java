package com.filinglens.company;

/** A company as referenced by other modules. {@code cik} is 10-digit zero-padded. */
public record CompanyRef(long id, String cik, String name) {
}
