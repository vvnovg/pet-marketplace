package com.petmarketplace.application.imports;

import com.petmarketplace.application.imports.dto.AnimalImportJobResponse;
import com.petmarketplace.application.imports.dto.AnimalImportRequest;
import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/imports")
@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
public class AnimalImportController {

    private final AnimalImportService importService;
    private final AnimalImportJobService jobService;

    public AnimalImportController(AnimalImportService importService,
                                  AnimalImportJobService jobService) {
        this.importService = importService;
        this.jobService = jobService;
    }

    @PostMapping("/animals")
    public ResponseEntity<AnimalImportJobResponse> importAnimals(
            @Valid @RequestBody AnimalImportRequest request) {
        AnimalImportJob job = jobService.create(request.bucket(), request.objectKey());
        importService.importAnimals(job.getId(), request.bucket(), request.objectKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AnimalImportJobResponse.from(job));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<AnimalImportJobResponse> getJob(@PathVariable UUID jobId) {
        AnimalImportJob job = jobService.findById(jobId);
        return ResponseEntity.ok(AnimalImportJobResponse.from(job));
    }

    @GetMapping
    public ResponseEntity<List<AnimalImportJobResponse>> listJobs() {
        List<AnimalImportJobResponse> jobs = jobService.findRecent().stream()
                .map(AnimalImportJobResponse::from)
                .toList();
        return ResponseEntity.ok(jobs);
    }
}
