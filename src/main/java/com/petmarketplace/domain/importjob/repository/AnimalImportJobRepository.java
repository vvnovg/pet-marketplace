package com.petmarketplace.domain.importjob.repository;

import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnimalImportJobRepository extends JpaRepository<AnimalImportJob, UUID> {

    List<AnimalImportJob> findTop20ByOrderByCreatedAtDesc();
}
