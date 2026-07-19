package com.petmarketplace.domain.category.entity;

import com.petmarketplace.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "breeds")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Breed extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "name_ru", length = 100)
    private String nameRu;

    @Column(name = "name_en", length = 100)
    private String nameEn;
}
