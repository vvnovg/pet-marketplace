package com.petmarketplace.application.imports;

import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.domain.user.repository.UserRepository;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.novgorodtsev.excelimport.ErrorKind;
import org.novgorodtsev.excelimport.RowError;
import org.novgorodtsev.excelimport.RowRef;
import org.novgorodtsev.excelimport.validate.BatchValidator;
import org.springframework.stereotype.Component;

@Component
public class OwnerValidationBatchValidator implements BatchValidator<AnimalImportRow> {

    private final UserRepository userRepository;

    public OwnerValidationBatchValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public List<RowError> validate(List<RowRef<AnimalImportRow>> batch, Connection connection) {
        // 1. Собираем все email из батча
        Set<String> emails = batch.stream()
                .map(ref -> ref.value().getSellerEmail().toLowerCase())
                .collect(Collectors.toSet());

        // 2. Одним запросом находим существующих пользователей
        Map<String, UUID> existing = userRepository.findAllByEmailIn(emails).stream()
                .collect(Collectors.toMap(
                        u -> u.getEmail().toLowerCase(),
                        User::getId));

        // 3. Для каждой строки: если email не найден — ошибка, иначе подставляем seller_id
        List<RowError> errors = new ArrayList<>();
        for (RowRef<AnimalImportRow> ref : batch) {
            String email = ref.value().getSellerEmail().toLowerCase();
            UUID ownerId = existing.get(email);
            if (ownerId == null) {
                errors.add(new RowError(
                        ref.rowNum(),
                        "Email владельца",
                        ref.value().getSellerEmail(),
                        ErrorKind.BATCH,
                        "OWNER_NOT_FOUND",
                        "владелец не зарегистрирован: " + email));
            } else {
                ref.value().setSellerId(ownerId);
            }
        }
        return errors;
    }
}
