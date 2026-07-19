package com.petmarketplace.application.admin.service;

import com.petmarketplace.domain.user.entity.Role;
import com.petmarketplace.domain.user.entity.User;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

public final class UserSpecification {

    private UserSpecification() {
    }

    public static Specification<User> hasRole(Role role) {
        return role == null ? null : (root, query, cb) -> cb.equal(root.get("role"), role);
    }

    public static Specification<User> isActive(Boolean active) {
        return active == null ? null : (root, query, cb) -> cb.equal(root.get("active"), active);
    }

    public static Specification<User> isVerified(Boolean verified) {
        return verified == null ? null : (root, query, cb) -> cb.equal(root.get("verified"), verified);
    }

    public static Specification<User> searchByEmailOrName(String search) {
        if (!StringUtils.hasText(search)) {
            return null;
        }
        String pattern = "%" + search.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("email")), pattern),
                cb.like(cb.lower(root.get("firstName")), pattern),
                cb.like(cb.lower(root.get("lastName")), pattern)
        );
    }

    public static Specification<User> buildAdminSearch(Role role, Boolean active, Boolean verified, String search) {
        // The per-filter helpers return null when their argument is absent, and Spring Data JPA
        // rejects spec.and(null) with "Other specification must not be null" (HTTP 500), so chain
        // each only when present.
        Specification<User> spec = (root, query, cb) -> cb.conjunction();
        spec = andIfPresent(spec, hasRole(role));
        spec = andIfPresent(spec, isActive(active));
        spec = andIfPresent(spec, isVerified(verified));
        spec = andIfPresent(spec, searchByEmailOrName(search));
        return spec;
    }

    private static Specification<User> andIfPresent(Specification<User> spec, Specification<User> addition) {
        return addition == null ? spec : spec.and(addition);
    }
}
