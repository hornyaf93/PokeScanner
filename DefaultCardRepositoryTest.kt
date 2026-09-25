package com.yourname.pokescanner.data.repository

import com.yourname.pokescanner.data.remote.PokemonTcgApi
import com.yourname.pokescanner.data.remote.dto.PokemonTcgCardDto
import com.yourname.pokescanner.data.remote.dto.PokemonTcgCardSearchResponseDto
import com.yourname.pokescanner.data.remote.dto.PokemonTcgSetDto
import com.yourname.pokescanner.domain.model.CardIdentityQuery
import com.yourname.pokescanner.domain.model.CatalogMatchEvidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCardRepositoryTest {

    @Test
    fun `printed denominator removes same card number from wrong sets`() = runBlocking {
        val api = FakeApi(
            cards = listOf(
                card(id = "base1-4", setId = "base1", printedTotal = 102),
                card(id = "other-4", setId = "other", printedTotal = 110),
            ),
        )
        val repository = DefaultCardRepository(api, Dispatchers.Unconfined)

        val result = repository.searchByIdentity(
            CardIdentityQuery("Charizard", "004", "102"),
        )

        assertEquals(listOf("base1-4"), result.map { it.id })
        assertTrue(
            CatalogMatchEvidence.EXACT_PRINTED_SET_TOTAL in result.single().matchEvidence,
        )
        assertTrue(api.queries.all { "name:\"Charizard\"" in it })
        assertTrue(api.queries.any { "number:\"004\"" in it })
        assertTrue(api.queries.any { "number:\"4\"" in it })
    }

    private fun card(
        id: String,
        setId: String,
        printedTotal: Int,
    ) = PokemonTcgCardDto(
        id = id,
        name = "Charizard",
        number = "4",
        set = PokemonTcgSetDto(
            id = setId,
            name = "Set",
            series = "Series",
            printedTotal = printedTotal,
            total = printedTotal,
        ),
    )

    private class FakeApi(
        private val cards: List<PokemonTcgCardDto>,
    ) : PokemonTcgApi {
        val queries = mutableListOf<String>()

        override suspend fun searchCards(
            query: String,
            page: Int,
            pageSize: Int,
            select: String,
        ): PokemonTcgCardSearchResponseDto {
            queries += query
            return PokemonTcgCardSearchResponseDto(
                data = cards,
                page = 1,
                pageSize = cards.size,
                count = cards.size,
                totalCount = cards.size,
            )
        }
    }
}

