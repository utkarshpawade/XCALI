package com.drawapp.backend.common;

import java.sql.SQLException;

import org.springframework.dao.DataIntegrityViolationException;

/** Tells a unique-constraint violation apart from other integrity errors. */
public final class UniqueViolation {

	/** Postgres SQLSTATE for unique_violation. */
	private static final String UNIQUE_VIOLATION = "23505";

	private UniqueViolation() {
	}

	public static boolean isCause(DataIntegrityViolationException ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
				return true;
			}
		}
		return false;
	}

}
