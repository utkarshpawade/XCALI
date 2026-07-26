package com.drawapp.backend.common;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as {@code {"message": ...}}, plus a per-field
 * {@code errors} map for validation failures - the shapes the frontends read.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	/**
	 * zod reports the first failing check of each field; checks are listed in
	 * the order the old schemas ran them.
	 */
	private static final List<String> CHECK_ORDER = List.of("NotNull", "Size", "Pattern");

	public record ErrorBody(String message) {
	}

	public record ValidationErrorBody(String message, Map<String, String> errors) {
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorBody> handleApiException(ApiException ex) {
		return ResponseEntity.status(ex.getStatus()).body(new ErrorBody(ex.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorBody> handleUnexpected(Exception ex) {
		log.error("Unhandled error", ex);
		return ResponseEntity.internalServerError().body(new ErrorBody("Internal server error"));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<String> fieldOrder = declaredFieldOrder(ex.getBindingResult().getTarget());
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.sorted(Comparator.comparingInt((FieldError error) -> rank(fieldOrder, error.getField()))
				.thenComparingInt((error) -> rank(CHECK_ORDER, error.getCode())))
			.forEach((error) -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return ResponseEntity.badRequest().body(new ValidationErrorBody("Incorrect inputs", errors));
	}

	/** A missing or malformed body fails validation as a whole, as it did before. */
	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return ResponseEntity.badRequest().body(new ValidationErrorBody("Incorrect inputs", Map.of()));
	}

	/** Everything else Spring MVC raises itself: unknown routes, wrong methods. */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		HttpStatus status = HttpStatus.resolve(statusCode.value());
		String message = (status == HttpStatus.NOT_FOUND) ? "Not found"
				: (status != null) ? status.getReasonPhrase() : "Request failed";
		return new ResponseEntity<>(new ErrorBody(message), headers, statusCode);
	}

	private static List<String> declaredFieldOrder(@Nullable Object target) {
		if (target == null || !target.getClass().isRecord()) {
			return List.of();
		}
		return Arrays.stream(target.getClass().getRecordComponents()).map(RecordComponent::getName).toList();
	}

	private static int rank(List<String> order, @Nullable String value) {
		int index = order.indexOf(value);
		return (index < 0) ? order.size() : index;
	}

}
