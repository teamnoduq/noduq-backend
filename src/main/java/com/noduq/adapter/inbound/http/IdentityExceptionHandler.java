package com.noduq.adapter.inbound.http;

import com.noduq.domain.identity.IdentityException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IdentityExceptionHandler {

	@ExceptionHandler(IdentityException.class)
	ResponseEntity<ApiError> identity(IdentityException ex) {
		return ResponseEntity.status(ex.httpStatus()).body(new ApiError(ex.code(), ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(error -> error.getDefaultMessage())
				.orElse("Datos inválidos.");
		return ResponseEntity.badRequest().body(new ApiError("VALIDATION", message));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ApiError> conflict(DataIntegrityViolationException ex) {
		return ResponseEntity.status(409).body(new ApiError("CONFLICT", "Ese dato ya existe."));
	}

	@ExceptionHandler(JwtException.class)
	ResponseEntity<ApiError> jwt(JwtException ex) {
		return ResponseEntity.status(401).body(new ApiError("UNAUTHORIZED", "Sesión inválida."));
	}
}
