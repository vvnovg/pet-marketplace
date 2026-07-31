package com.petmarketplace.domain.importjob.repository;

import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnimalImportJobRepository extends JpaRepository<AnimalImportJob, UUID> {

    List<AnimalImportJob> findTop20ByOrderByCreatedAtDesc();

    /**
     * Есть ли уже задача на этот объект хранилища. Шедулер спрашивает это про каждый файл на
     * каждом опросе — под запрос заведён индекс (changelogs/008).
     */
    boolean existsBySourceBucketAndSourceKey(String sourceBucket, String sourceKey);
}
