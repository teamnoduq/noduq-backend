package com.noduq.adapter.inbound.http;

import com.noduq.domain.identity.IdentityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IdentityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(IdentityExceptionHandler.class);

	@ExceptionHandler(IdentityException.class)
	ResponseEntity<ApiError> identity(IdentityException ex) {
		if (ex.httpStatus() >= 500) {
			log.error("IdentityException code={} status={} message={}", ex.code(), ex.httpStatus(), ex.getMessage());
		} else {
			log.info("IdentityException code={} status={} message={}", ex.code(), ex.httpStatus(), ex.getMessage());
		}
		return ResponseEntity.status(ex.httpStatus()).body(new ApiError(ex.code(), ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(error -> error.getDefaultMessage())
				.orElse("Datos inválidos.");
		log.info("Validation failed: {}", message);
		return ResponseEntity.badRequest().body(new ApiError("VALIDATION", message));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ApiError> conflict(DataIntegrityViolationException ex) {
		log.warn("Data integrity conflict: {}", ex.getMostSpecificCause().getMessage());
		return ResponseEntity.status(409).body(new ApiError("CONFLICT", "Ese dato ya existe."));
	}

	@ExceptionHandler(JwtException.class)
	ResponseEntity<ApiError> jwt(JwtException ex) {
		log.info("JwtException in MVC: {}", ex.getMessage());
		return ResponseEntity.status(401).body(new ApiError("UNAUTHORIZED", "Sesión inválida."));
	}

	@ExceptionHandler(CannotCreateTransactionException.class)
	ResponseEntity<ApiError> database(CannotCreateTransactionException ex) {
		log.error("Database unavailable", ex);
		return ResponseEntity.status(503).body(new ApiError("DATABASE", "No se pudo abrir la base de datos."));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> unknown(Exception ex) {
		log.error("Unhandled API exception", ex);
		return ResponseEntity.status(500).body(new ApiError("ERROR", "No se pudo completar la petición."));
	}
}
