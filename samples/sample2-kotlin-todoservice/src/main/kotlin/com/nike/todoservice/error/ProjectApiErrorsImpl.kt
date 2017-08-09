package com.github.amitsk.roomratecalculator.error

import com.nike.backstopper.apierror.ApiError
import com.nike.backstopper.apierror.projectspecificinfo.ProjectSpecificErrorCodeRange
import com.nike.backstopper.apierror.sample.SampleProjectApiErrorsBase

import java.util.Arrays

import javax.inject.Singleton

/**
 * Returns the project specific errors for this application.

 *
 * Individual projects should feel free to rename this class to something specific, e.g. [MyProject]ApiErrorsImpl

 *
 * NOTE: This extends [SampleProjectApiErrorsBase] for a reasonable base of "core errors". You may want to
 * create a similar reusable base class to be used by this project (and potentially others) that uses error codes and
 * messages of your choosing for the core errors.
 */
@Singleton
class ProjectApiErrorsImpl : SampleProjectApiErrorsBase() {

    override fun getProjectSpecificApiErrors(): List<ApiError> {
        return PROJECT_SPECIFIC_API_ERRORS
    }

    /**
     * @return the range of errors for this project. This is used to verify that [.getProjectSpecificApiErrors]
     * * doesn't include any error codes outside the range you have reserved for your project. See the class javadocs for
     * * [ProjectSpecificErrorCodeRange] for suggestions on how to manage cross-project error ranges in the same
     * * org.
     */
    override fun getProjectSpecificErrorCodeRange(): ProjectSpecificErrorCodeRange {
        return ProjectSpecificErrorCodeRange.ALLOW_ALL_ERROR_CODES
    }

    companion object {

        private val PROJECT_SPECIFIC_API_ERRORS = Arrays.asList<ApiError>(*ProjectApiError.values())
    }

}
