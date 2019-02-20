package com.nike.riposte.server.error.validation;

import com.nike.riposte.server.error.exception.Unauthorized401Exception;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;

import org.jetbrains.annotations.NotNull;

import java.util.Base64;
import java.util.Collection;
import java.util.Collections;

/**
 * A {@link RequestSecurityValidator} for validating that the incoming request for any of the given collection
 * of endpoints passes Basic auth validation.
 */
@SuppressWarnings("WeakerAccess")
public class BasicAuthSecurityValidator implements RequestSecurityValidator {

    protected final @NotNull Collection<Endpoint<?>> basicAuthValidatedEndpoints;

    protected final String expectedUsername;
    protected final String expectedPassword;

    /**
     * @param basicAuthValidatedEndpoints
     *     The endpoints that this {@link RequestSecurityValidator} should be applied against.
     * @param expectedUsername The expected username portion of the Basic Authorization header.
     * @param expectedPassword The expected password portion of the Basic Authorization header.
     */
    public BasicAuthSecurityValidator(Collection<Endpoint<?>> basicAuthValidatedEndpoints,
                                      String expectedUsername,
                                      String expectedPassword) {
        this.basicAuthValidatedEndpoints = (basicAuthValidatedEndpoints == null)
                                           ? Collections.emptySet()
                                           : basicAuthValidatedEndpoints;
        this.expectedUsername = expectedUsername;
        this.expectedPassword = expectedPassword;
    }

    @Override
    public void validateSecureRequestForEndpoint(
        @NotNull RequestInfo<?> requestInfo,
        @NotNull Endpoint<?> endpoint
    ) {
        String authorizationHeader = requestInfo.getHeaders().get("Authorization");

        if (authorizationHeader == null) {
            throw new Unauthorized401Exception("Missing authorization header.", requestInfo.getPath(), null);
        }

        final String[] authSplit = authorizationHeader.split(" ");
        if (authSplit.length != 2 || !"Basic".equals(authSplit[0])) {
            throw new Unauthorized401Exception("Authorization header does not contain Basic", requestInfo.getPath(),
                                               authorizationHeader);
        }

        Base64.Decoder decoder = Base64.getDecoder();
        byte[] decodedBytes;
        try {
            decodedBytes = decoder.decode(authSplit[1]);
        }
        catch (IllegalArgumentException ex) {
            throw new Unauthorized401Exception(
                "Malformed Authorization header (not Base64 encoded), caused by: " + ex.toString(),
                requestInfo.getPath(), authorizationHeader);
        }

        String pair = new String(decodedBytes);
        String[] userDetails = pair.split(":", 2);
        if (userDetails.length != 2) {
            throw new Unauthorized401Exception("Malformed Authorization header.", requestInfo.getPath(),
                                               authorizationHeader);
        }
        String username = userDetails[0];
        String password = userDetails[1];

        if (!username.equals(expectedUsername) || !password.equals(expectedPassword)) {
            throw new Unauthorized401Exception("Invalid username or password", requestInfo.getPath(),
                                               authorizationHeader);
        }
    }

    @Override
    public @NotNull Collection<Endpoint<?>> endpointsToValidate() {
        return basicAuthValidatedEndpoints;
    }

    @Override
    public boolean isFastEnoughToRunOnNettyWorkerThread() {
        // Basic auth processing is extremely quick - no need to run asynchronously.
        return true;
    }
}
