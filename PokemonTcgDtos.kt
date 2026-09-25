package com.yourname.pokescanner.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PokemonTcgCardSearchResponseDto(
    val data: List<PokemonTcgCardDto> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 0,
    val count: Int = 0,
    val totalCount: Int = 0,
)

@Serializable
data class PokemonTcgCardDto(
    val id: String,
    val name: String,
    val number: String,
    val set: PokemonTcgSetDto,
    val rarity: String? = null,
    val images: PokemonTcgCardImagesDto? = null,
    val tcgplayer: PokemonTcgPlayerDto? = null,
)

@Serializable
data class PokemonTcgSetDto(
    val id: String,
    val name: String,
    val series: String,
    val printedTotal: Int,
    val total: Int,
)

@Serializable
data class PokemonTcgCardImagesDto(
    val small: String? = null,
    val large: String? = null,
)

@Serializable
data class PokemonTcgPlayerDto(
    val url: String? = null,
    val updatedAt: String? = null,
    val prices: PokemonTcgPricesDto? = null,
)

@Serializable
data class PokemonTcgPricesDto(
    val normal: PokemonTcgPriceDto? = null,
    val holofoil: PokemonTcgPriceDto? = null,
    val reverseHolofoil: PokemonTcgPriceDto? = null,
    @SerialName("1stEditionNormal")
    val firstEditionNormal: PokemonTcgPriceDto? = null,
    @SerialName("1stEditionHolofoil")
    val firstEditionHolofoil: PokemonTcgPriceDto? = null,
)

@Serializable
data class PokemonTcgPriceDto(
    val low: Double? = null,
    val mid: Double? = null,
    val high: Double? = null,
    val market: Double? = null,
    val directLow: Double? = null,
)

