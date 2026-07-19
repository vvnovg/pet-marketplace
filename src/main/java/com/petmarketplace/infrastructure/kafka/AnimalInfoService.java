package com.petmarketplace.infrastructure.kafka;

import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.listing.repository.ListingRepository;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Loads a listing by id and maps it to an {@link AnimalInfoResponse}, enforcing the public-visibility
 * rule: only {@code ACTIVE}, {@code RESERVED}, {@code SOLD} are returned as OK. Any other status —
 * or a missing listing — is reported as {@link ReplyStatus#NOT_FOUND}, deliberately indistinguishable
 * from "does not exist" so the Kafka channel leaks no information about non-public listings.
 */
@Service
public class AnimalInfoService {

    private static final Set<ListingStatus> PUBLIC_STATUSES =
            EnumSet.of(ListingStatus.ACTIVE, ListingStatus.RESERVED, ListingStatus.SOLD);

    private final ListingRepository listingRepository;
    private final AnimalInfoMapper animalInfoMapper;

    public AnimalInfoService(ListingRepository listingRepository, AnimalInfoMapper animalInfoMapper) {
        this.listingRepository = listingRepository;
        this.animalInfoMapper = animalInfoMapper;
    }

    public AnimalInfoResponse findById(UUID listingId, String correlationId) {
        Optional<Listing> listing = listingRepository.findByIdWithSeller(listingId);
        if (listing.isEmpty() || !PUBLIC_STATUSES.contains(listing.get().getStatus())) {
            return AnimalInfoResponse.notFound(correlationId, listingId);
        }
        return animalInfoMapper.toOkResponse(listing.get(), correlationId);
    }
}