package com.curato.data.repository

import com.curato.data.mapper.toDomain
import com.curato.data.mapper.toDto
import com.curato.data.remote.CuratoApi
import com.curato.domain.ApiResult
import com.curato.domain.map
import com.curato.domain.model.Enquiry
import com.curato.domain.model.EnquiryAccepted
import com.curato.domain.repository.EnquiryRepository

class EnquiryRepositoryImpl(
    private val api: CuratoApi,
) : EnquiryRepository {

    override suspend fun submit(enquiry: Enquiry): ApiResult<EnquiryAccepted> =
        api.submitEnquiry(enquiry.toDto()).map { it.toDomain() }
}
