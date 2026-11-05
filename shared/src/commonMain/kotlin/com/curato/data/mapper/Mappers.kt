package com.curato.data.mapper

import com.curato.data.remote.dto.CategoriesDto
import com.curato.data.remote.dto.CategoryProductsDto
import com.curato.data.remote.dto.CategoryRefDto
import com.curato.data.remote.dto.CategoryShelfDto
import com.curato.data.remote.dto.ContactDto
import com.curato.data.remote.dto.EnquiryAcceptedDto
import com.curato.data.remote.dto.EnquiryRequestDto
import com.curato.data.remote.dto.FeaturedDto
import com.curato.data.remote.dto.HomeDto
import com.curato.data.remote.dto.ProductDto
import com.curato.data.remote.dto.ProductSummaryDto
import com.curato.data.remote.dto.RatingDto
import com.curato.data.remote.dto.SearchResultsDto
import com.curato.domain.model.CategoryDetail
import com.curato.domain.model.CategoryRef
import com.curato.domain.model.CategoryShelf
import com.curato.domain.model.Contact
import com.curato.domain.model.Enquiry
import com.curato.domain.model.EnquiryAccepted
import com.curato.domain.model.FeaturedShelf
import com.curato.domain.model.FeaturedSource
import com.curato.domain.model.Product
import com.curato.domain.model.ProductSummary
import com.curato.domain.model.Rating
import com.curato.domain.model.SearchResults

/**
 * Wire ↔ domain. The only place the two vocabularies meet.
 *
 * Almost everything here runs one way, wire → domain, because until the enquiry the
 * client only read. The enquiry adds the one pair that goes the other way.
 */

fun CategoryRefDto.toDomain() = CategoryRef(slug = slug, name = name)

fun RatingDto.toDomain() = Rating(rate = rate, count = count)

fun ProductSummaryDto.toDomain() = ProductSummary(
    id = id,
    title = title,
    price = price,
    currency = currency,
    category = category.toDomain(),
    imageUrl = imageUrl,
    rating = rating.toDomain(),
)

fun ProductDto.toDomain() = Product(
    id = id,
    title = title,
    price = price,
    currency = currency,
    description = description,
    category = category.toDomain(),
    imageUrl = imageUrl,
    rating = rating.toDomain(),
)

/**
 * An unrecognised `source` falls back to `COMPUTED`, which heads the shelf "Top rated".
 *
 * That direction is deliberate. "Top rated" describes a ranking, and the computed path
 * is the one the backend falls back to itself; defaulting to "Featured" would instead
 * claim an editorial choice nobody made.
 */
fun FeaturedDto.toDomain() = FeaturedShelf(
    source = when (source.lowercase()) {
        "curated" -> FeaturedSource.CURATED
        else -> FeaturedSource.COMPUTED
    },
    products = products.map { it.toDomain() },
)

fun HomeDto.toDomain() = featured.toDomain()

fun CategoryShelfDto.toDomain() = CategoryShelf(
    category = category.toDomain(),
    topRated = topRated.map { it.toDomain() },
)

fun CategoriesDto.toDomain(): List<CategoryShelf> = categories.map { it.toDomain() }

fun CategoryProductsDto.toDomain() = CategoryDetail(
    category = category.toDomain(),
    topRated = topRated.map { it.toDomain() },
    additional = additional.map { it.toDomain() },
)

/** Order is preserved exactly: the backend ranked these, and the client does not re-sort. */
fun SearchResultsDto.toDomain() = SearchResults(
    query = query,
    products = results.map { it.toDomain() },
)

/**
 * Domain → wire, the client's only outbound mapping.
 *
 * Nothing is trimmed or normalised here: [EnquiryForm.toEnquiry] already did it, once,
 * and doing it again would put the same rule in a second place where only one of them
 * would get fixed.
 */
fun Enquiry.toDto() = EnquiryRequestDto(
    productId = productId,
    seenPrice = seenPrice,
    contact = contact.toDto(),
    message = message,
)

fun Contact.toDto() = ContactDto(name = name, email = email, phone = phone)

fun EnquiryAcceptedDto.toDomain() = EnquiryAccepted(acceptedAt = acceptedAt)
