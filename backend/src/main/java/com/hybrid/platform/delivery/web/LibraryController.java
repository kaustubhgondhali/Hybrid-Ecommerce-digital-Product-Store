package com.hybrid.platform.delivery.web;

import com.hybrid.platform.delivery.DeliveryService;
import com.hybrid.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
public class LibraryController {

    public record DownloadLinkRequest(@NotNull Long assetId) {
    }

    private final DeliveryService service;

    public LibraryController(DeliveryService service) {
        this.service = service;
    }

    @GetMapping("/library")
    public List<DeliveryService.LibraryItem> library() {
        return service.library(CurrentUser.customer().id());
    }

    @PostMapping("/library/{entitlementId}/download-links")
    public DeliveryService.DownloadLink downloadLink(@PathVariable Long entitlementId, @Valid @RequestBody DownloadLinkRequest req) {
        return service.createDownloadLink(CurrentUser.customer().id(), entitlementId, req.assetId());
    }

    @GetMapping("/downloads/{token}")
    public ResponseEntity<Resource> download(@PathVariable String token) {
        DeliveryService.DownloadFile file = service.resolveDownload(CurrentUser.customer().id(), token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.sizeBytes())
                .body(new FileSystemResource(file.path()));
    }
}
