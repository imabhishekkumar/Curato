package com.curato.domain.usecase

import com.curato.domain.ApiResult
import com.curato.domain.model.CategoryDetail
import com.curato.domain.model.CategoryShelf
import com.curato.domain.model.Contact
import com.curato.domain.model.Enquiry
import com.curato.domain.model.EnquiryAccepted
import com.curato.domain.model.FeaturedShelf
import com.curato.domain.model.Product
import com.curato.domain.model.RecentProduct
import com.curato.domain.model.SearchResults
import com.curato.domain.repository.CatalogueRepository
import com.curato.domain.repository.EnquiryRepository
import com.curato.domain.repository.RecentlyViewedRepository
import com.curato.domain.repository.SavedContactRepository
import kotlinx.coroutines.flow.Flow

/**
 * One call each, named for what the screen is asking for.
 *
 * Thin by design: they exist so a ViewModel depends on a verb rather than on a
 * repository surface, which is what keeps the ViewModel tests free of stubs for methods
 * the screen never calls.
 */

class GetFeaturedShelf(private val repository: CatalogueRepository) {
    suspend operator fun invoke(): ApiResult<FeaturedShelf> = repository.getFeatured()
}

class GetCategoryShelves(private val repository: CatalogueRepository) {
    suspend operator fun invoke(): ApiResult<List<CategoryShelf>> =
        repository.getCategoryShelves()
}

class GetCategory(private val repository: CatalogueRepository) {
    suspend operator fun invoke(slug: String): ApiResult<CategoryDetail> =
        repository.getCategory(slug)
}

class GetProduct(private val repository: CatalogueRepository) {
    suspend operator fun invoke(id: Int): ApiResult<Product> = repository.getProduct(id)
}

/**
 * The shortest term worth sending (SPEC.md §6, SPEC.md §4.4).
 *
 * The backend rejects anything shorter with `VALIDATION_FAILED`; the client declines to
 * send it at all. §6 calls the client half "mirrored for immediate feedback", so this is
 * the mirror — one number, read by the ViewModel that gates the request and by the test
 * that pins the boundary.
 */
const val MIN_QUERY_LENGTH = 2

/**
 * The exact string the backend returns as `fieldErrors.q`.
 *
 * Held here so the two cannot drift. The Screen Kit says the client's below-minimum copy
 * and the server's field error "never disagree", and the only way to make that true rather
 * than nearly true is for one of them to be a copy of the other — `EnvelopeMappingTest`
 * asserts this equals what a real 422 carries.
 */
const val MIN_QUERY_MESSAGE = "Enter at least 2 characters"

/**
 * Search (SPEC.md §4.4).
 *
 * Takes the term as given. Trimming and the minimum-length rule belong to the caller and
 * to the backend respectively; putting either here would make the same decision in a third
 * place.
 */
class SearchProducts(private val repository: CatalogueRepository) {
    suspend operator fun invoke(query: String): ApiResult<SearchResults> =
        repository.searchProducts(query)
}

class ObserveRecentlyViewed(private val repository: RecentlyViewedRepository) {
    operator fun invoke(): Flow<List<RecentProduct>> = repository.observe()
}

class RecordProductViewed(private val repository: RecentlyViewedRepository) {
    suspend operator fun invoke(product: RecentProduct) = repository.record(product)
}

/**
 * Submit an enquiry (SPEC.md §4.6).
 *
 * The app's only write. Named for the verb the screen performs rather than the endpoint
 * it reaches, like the rest of these.
 */
class SubmitEnquiry(private val repository: EnquiryRepository) {
    suspend operator fun invoke(enquiry: Enquiry): ApiResult<EnquiryAccepted> =
        repository.submit(enquiry)
}

/** Emits `null` when nothing is saved, which is the first-enquiry case (SPEC.md §9.2). */
class ObserveSavedContact(private val repository: SavedContactRepository) {
    operator fun invoke(): Flow<Contact?> = repository.observe()
}

/** Only ever called with consent checked — the gate is the ViewModel's (AC-17). */
class SaveContact(private val repository: SavedContactRepository) {
    suspend operator fun invoke(contact: Contact) = repository.save(contact)
}

class ClearSavedContact(private val repository: SavedContactRepository) {
    suspend operator fun invoke() = repository.clear()
}
