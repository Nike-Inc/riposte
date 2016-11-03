package com.nike.riposte.server.error.validation;

import com.nike.riposte.server.error.exception.Unauthorized401Exception;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;

import org.apache.commons.codec.binary.Base64;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import io.netty.handler.codec.http.HttpHeaders;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BasicAuthSecurityValidatorTest {

    BasicAuthSecurityValidator underTest;
    Endpoint<?> mockEndpoint1;
    Endpoint<?> mockEndpoint2;
    Endpoint<?> mockEndpoint3;

    private static final String USERNAME = "testUsername";
    private static final String PASSWORD = "testPassword";

    @Before
    public void setup() {
        mockEndpoint1 = mock(Endpoint.class);
        mockEndpoint2 = mock(Endpoint.class);
        mockEndpoint3 = mock(Endpoint.class);
        underTest = new BasicAuthSecurityValidator(Arrays.asList(mockEndpoint1, mockEndpoint2, mockEndpoint3), USERNAME,
                                                   PASSWORD);
    }

    @Test
    public void endpointsToValidateTest() {
        assertThat(underTest.endpointsToValidate().size(), is(3));
        assertThat(underTest.endpointsToValidate().contains(mockEndpoint1), is(true));
        assertThat(underTest.endpointsToValidate().contains(mockEndpoint2), is(true));
        assertThat(underTest.endpointsToValidate().contains(mockEndpoint3), is(true));
    }

    @Test
    public void endpointsToValidateNullTest() {
        underTest = new BasicAuthSecurityValidator(null, USERNAME, PASSWORD);
        assertThat(underTest.endpointsToValidate(), nullValue());
    }

    @Test
    public void endpointsToValidateEmptyTest() {
        underTest = new BasicAuthSecurityValidator(Collections.emptyList(), USERNAME, PASSWORD);
        assertThat(underTest.endpointsToValidate().size(), is(0));
    }

    @Test
    public void validateHappyPath() {
        RequestInfo mockRequest = mock(RequestInfo.class);
        doReturn(mock(HttpHeaders.class)).when(mockRequest).getHeaders();
        when(mockRequest.getHeaders().get("Authorization")).thenReturn(calcAuthHeader(USERNAME, PASSWORD));

        underTest.validateSecureRequestForEndpoint(mockRequest, mockEndpoint1);
    }

    @Test(expected = Unauthorized401Exception.class)
    public void validateNullAuthHeader() {
        RequestInfo mockRequest = mock(RequestInfo.class);
        doReturn(mock(HttpHeaders.class)).when(mockRequest).getHeaders();
        when(mockRequest.getHeaders().get("Authorization")).thenReturn(null);

        underTest.validateSecureRequestForEndpoint(mockRequest, mockEndpoint1);
    }

    @Test(expected = Unauthorized401Exception.class)
    public void validateMissingBasicString() {
        RequestInfo mockRequest = mock(RequestInfo.class);
        doReturn(mock(HttpHeaders.class)).when(mockRequest).getHeaders();
        when(mockRequest.getHeaders().get("Authorization")).thenReturn("blah");

        underTest.validateSecureRequestForEndpoint(mockRequest, mockEndpoint1);
    }

    @Test(expected = Unauthorized401Exception.class)
    public void validateMissingUsername() {
        RequestInfo mockRequest = mock(RequestInfo.class);
        doReturn(mock(HttpHeaders.class)).when(mockRequest).getHeaders();
        when(mockRequest.getHeaders().get("Authorization")).thenReturn(calcAuthHeader(null, PASSWORD));

        underTest.validateSecureRequestForEndpoint(mockRequest, mockEndpoint1);
    }

    @Test(expected = Unauthorized401Exception.class)
    public void validateMissingPassword() {
        RequestInfo mockRequest = mock(RequestInfo.class);
        doReturn(mock(HttpHeaders.class)).when(mockRequest).getHeaders();
        when(mockRequest.getHeaders().get("Authorization")).thenReturn(calcAuthHeader(USERNAME, null));

        underTest.validateSecureRequestForEndpoint(mockRequest, mockEndpoint1);
    }

    @Test(expected = Unauthorized401Exception.class)
    public void validateMissingColonSeparator() {
        RequestInfo mockRequest = mock(RequestInfo.class);
        doReturn(mock(HttpHeaders.class)).when(mockRequest).getHeaders();
        when(mockRequest.getHeaders().get("Authorization"))
            .thenReturn("Basic " + Base64.encodeBase64String(USERNAME.getBytes()));

        underTest.validateSecureRequestForEndpoint(mockRequest, mockEndpoint1);
    }

    @Test(expected = Unauthorized401Exception.class)
    public void validateInvalidUsername() {
        RequestInfo mockRequest = mock(RequestInfo.class);
        doReturn(mock(HttpHeaders.class)).when(mockRequest).getHeaders();
        when(mockRequest.getHeaders().get("Authorization")).thenReturn(calcAuthHeader("blah", PASSWORD));

        underTest.validateSecureRequestForEndpoint(mockRequest, mockEndpoint1);
    }

    @Test(expected = Unauthorized401Exception.class)
    public void validateInvalidPassword() {
        RequestInfo mockRequest = mock(RequestInfo.class);
        doReturn(mock(HttpHeaders.class)).when(mockRequest).getHeaders();
        when(mockRequest.getHeaders().get("Authorization")).thenReturn(calcAuthHeader(USERNAME, "blah"));

        underTest.validateSecureRequestForEndpoint(mockRequest, mockEndpoint1);
    }

    @Test(expected = Unauthorized401Exception.class)
    public void validateTooManySpaces() {
        RequestInfo mockRequest = mock(RequestInfo.class);
        doReturn(mock(HttpHeaders.class)).when(mockRequest).getHeaders();
        when(mockRequest.getHeaders().get("Authorization")).thenReturn(calcAuthHeader(USERNAME, PASSWORD) + " foo");

        underTest.validateSecureRequestForEndpoint(mockRequest, mockEndpoint1);
    }

    @Test(expected = Unauthorized401Exception.class)
    public void validateFirstTokenIsNotBasic() {
        RequestInfo mockRequest = mock(RequestInfo.class);
        doReturn(mock(HttpHeaders.class)).when(mockRequest).getHeaders();
        when(mockRequest.getHeaders().get("Authorization"))
            .thenReturn(calcAuthHeader(USERNAME, PASSWORD).replaceFirst("Basic", "NotBasic"));

        underTest.validateSecureRequestForEndpoint(mockRequest, mockEndpoint1);
    }

    @Test(expected = Unauthorized401Exception.class)
    public void validateNonBase64Encoded() {
        RequestInfo mockRequest = mock(RequestInfo.class);
        doReturn(mock(HttpHeaders.class)).when(mockRequest).getHeaders();
        when(mockRequest.getHeaders().get("Authorization")).thenReturn("Basic " + USERNAME + ":" + PASSWORD);

        underTest.validateSecureRequestForEndpoint(mockRequest, mockEndpoint1);
    }

    @Test
    public void isFastEnoughToRunOnNettyWorkerThread_returns_true() {
        assertThat(underTest.isFastEnoughToRunOnNettyWorkerThread(), is(true));
    }

    private String calcAuthHeader(String username, String password) {
        String stringForEncoding = "";
        if (username != null)
            stringForEncoding += username;

        stringForEncoding += ":";

        if (password != null)
            stringForEncoding += password;

        return "Basic " + Base64.encodeBase64String(stringForEncoding.getBytes());
    }
}
