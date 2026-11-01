package io.github.misdocumeno.application

import io.github.misdocumeno.domain.model.LocationRequest
import io.github.misdocumeno.domain.model.LocationResponse

interface LocationHandler {
    fun handle(request: LocationRequest): LocationResponse?
}