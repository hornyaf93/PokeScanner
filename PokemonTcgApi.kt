package com.yourname.pokescanner.data.remote

import com.yourname.pokescanner.data.remote.dto.PokemonTcgCardSearchResponseDto
import retrofit2.http.GET
import retrofit2.http.Query

interface PokemonTcgApi {
    @GET("v2/cards")
    suspend fun searchCards(
        @Query("q") query: String,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = MAX_PAGE_SIZE,
        @Query("select") select: String = CARD_SEARCH_FIELDS,
    ): PokemonTcgCardSearchResponseDto

    companion object {
        const val BASE_URL = "https://api.pokemontcg.io/"
        const val MAX_PAGE_SIZE = 250
        const val CARD_SEARCH_FIELDS = "id,name,number,set,rarity,images,tcgplayer"
    }
}

