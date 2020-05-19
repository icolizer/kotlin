/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.components

import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.resolve.calls.model.KotlinCallArgument
import org.jetbrains.kotlin.resolve.calls.model.KotlinResolutionCandidate
import org.jetbrains.kotlin.types.UnwrappedType

interface ParameterTypeConversion {
    fun conversionDefinitelyNotNeeded(
        candidate: KotlinResolutionCandidate,
        argument: KotlinCallArgument,
        expectedParameterType: UnwrappedType
    ): Boolean

    fun conversionIsNeededBeforeSubtypingCheck(argument: KotlinCallArgument): Boolean
    fun conversionIsNeededAfterSubtypingCheck(argument: KotlinCallArgument): Boolean

    fun convertParameterType(
        candidate: KotlinResolutionCandidate,
        argument: KotlinCallArgument,
        parameter: ParameterDescriptor,
        expectedParameterType: UnwrappedType
    ): UnwrappedType?
}

object TypeConversions {
    fun performCompositeConversionBeforeSubtyping(
        candidate: KotlinResolutionCandidate,
        argument: KotlinCallArgument,
        candidateParameter: ParameterDescriptor,
        candidateExpectedType: UnwrappedType,
    ): ConversionData {
        val samConversionData = performConversionBeforeSubtyping(
            candidate, argument, candidateParameter, candidateExpectedType, SamTypeConversions
        )
        val suspendConversionData = if (samConversionData.convertedType == null) {
            performConversionBeforeSubtyping(candidate, argument, candidateParameter, candidateExpectedType, SuspendTypeConversions)
        } else {
            null
        }

        return ConversionData(
            convertedType = samConversionData.convertedType ?: suspendConversionData?.convertedType,
            wasConversion = samConversionData.wasConversion || suspendConversionData?.wasConversion == true,
            conversionDefinitelyNotNeeded = samConversionData.conversionDefinitelyNotNeeded &&
                    (suspendConversionData == null || suspendConversionData.conversionDefinitelyNotNeeded)
        )
    }

    fun performCompositeConversionAfterSubtyping(
        candidate: KotlinResolutionCandidate,
        argument: KotlinCallArgument,
        candidateParameter: ParameterDescriptor,
        candidateExpectedType: UnwrappedType,
    ): UnwrappedType? {
        val samConvertedType = performConversionAfterSubtyping(
            candidate, argument, candidateParameter, candidateExpectedType, SamTypeConversions
        )

        if (samConvertedType != null) return samConvertedType

        return performConversionAfterSubtyping(candidate, argument, candidateParameter, candidateExpectedType, SuspendTypeConversions)
    }

    private fun performConversionAfterSubtyping(
        candidate: KotlinResolutionCandidate,
        argument: KotlinCallArgument,
        candidateParameter: ParameterDescriptor,
        candidateExpectedType: UnwrappedType,
        conversion: ParameterTypeConversion
    ): UnwrappedType? {
        return if (conversion.conversionIsNeededAfterSubtypingCheck(argument)) {
            conversion.convertParameterType(candidate, argument, candidateParameter, candidateExpectedType)
        } else {
            null
        }
    }

    private fun performConversionBeforeSubtyping(
        candidate: KotlinResolutionCandidate,
        argument: KotlinCallArgument,
        candidateParameter: ParameterDescriptor,
        candidateExpectedType: UnwrappedType,
        conversion: ParameterTypeConversion
    ): ConversionData {
        val conversionDefinitelyNotNeeded = conversion.conversionDefinitelyNotNeeded(candidate, argument, candidateExpectedType)
        return if (!conversionDefinitelyNotNeeded && conversion.conversionIsNeededBeforeSubtypingCheck(argument)) {
            ConversionData(
                conversion.convertParameterType(candidate, argument, candidateParameter, candidateExpectedType),
                wasConversion = true,
                conversionDefinitelyNotNeeded
            )
        } else {
            ConversionData(convertedType = null, wasConversion = false, conversionDefinitelyNotNeeded)
        }
    }

    class ConversionData(val convertedType: UnwrappedType?, val wasConversion: Boolean, val conversionDefinitelyNotNeeded: Boolean)
}
