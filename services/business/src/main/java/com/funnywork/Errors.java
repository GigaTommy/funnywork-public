package com.funnywork;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> api(ApiException e) { return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","Request does not match the supported contract")); }
    @ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class,org.springframework.dao.TransientDataAccessResourceException.class,org.springframework.transaction.CannotCreateTransactionException.class})
    public ResponseEntity<?> unavailable(Exception e) {return ResponseEntity.status(503).body(Map.of("code","STORAGE_UNAVAILABLE"));}
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> integrity(Exception e) { return ResponseEntity.status(409).body(Map.of("code","STATE_CONFLICT","message","A version or resource already exists")); }
}
