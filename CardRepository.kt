package com.yourname.pokescanner.domain.repository

import com.yourname.pokescanner.domain.model.CardIdentityQuery
import com.yourname.pokescanner.domain.model.CatalogCard

interface CardRepository {
    suspend fun searchByIdentity(query: CardIdentityQuery): List<CatalogCard>
}

class CardRepositoryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

