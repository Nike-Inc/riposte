package com.nike.riposte.server.http;

import com.fasterxml.jackson.core.type.TypeReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

/**
 * Base implementation of {@link NonblockingEndpoint}.
 * <p/>
 * This class attempts to infer the input type during the constructor and defaults the return value of {@link
 * #requestContentType()} to this inferred type. This means that normally you do *not* need to override this method in
 * your endpoints in order to have POST/PUT/etc content deserialized for you and ready to retrieve via {@link
 * RequestInfo#getContent()} - it should happen automatically.
 * <p/>
 * See the javadocs for {@link NonblockingEndpoint} and {@link Endpoint}.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public abstract class StandardEndpoint<I, O> implements NonblockingEndpoint<I, O> {

    @SuppressWarnings("FieldCanBeLocal")
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final Type inputType;
    protected final TypeReference<I> inferredTypeReference;

    /**
     * Uses some magic to determine the {@link #inputType} of the {@code I} generic type for this class (the {@code I}
     * in {@code NonblockingEndpoint<I, O>}). This in turn is used when deserializing {@link
     * RequestInfo#getRawContentBytes()} into the expected input {@link RequestInfo#getContent()}.
     * <p/>
     * The magic is inspired by Jackson's {@code TypeReference}, which was in turn inspired by
     * <a href="http://gafter.blogspot.com/2006/12/super-type-tokens.html">http://gafter.blogspot.com/2006/12/super-type-tokens.html</a>.
     */
    protected StandardEndpoint() {
        Type superClass = getClass().getGenericSuperclass();

        this.inputType = (superClass instanceof Class<?>)
                         ? null
                         : ((ParameterizedType) superClass).getActualTypeArguments()[0];

        if (inputType == null) {
            logger.warn("A StandardEndpoint was constructed with raw type information. This is not recommended - "
                        + "please construct your endpoints with concrete type information.",
                        new Exception("Not a real exception - here for stack trace information")
            );
        }
        else if (inputType instanceof TypeVariable) {
            // Non-specific generic type. This is usually trivially fixed and is almost surely a bug.
            //      Throw an error with the necessary info.
            IllegalArgumentException ex = new IllegalArgumentException(
                "A StandardEndpoint was constructed with non-specific type information. This is usually trivially "
                + "fixed by making your class a subclass - i.e. instead of new MyEndpoint<ConcreteInputType, "
                + "ConcreteOutputType>(), do new MyEndpoint<ConcreteInputType, ConcreteOutputType>(){} instead (note "
                + "the trailing {} curly braces to force an anonymous subclass). You can also create a concrete "
                + "subclass and instantiate that however you want, e.g. public class MyEndpoint extends "
                + "StandardEndpoint<ConcreteInputType, ConcreteOutputType> { ... }"
            );
            logger.warn("Error constructing class of type: {}", this.getClass().getName(), ex);
            throw ex;
        }

        //noinspection EqualsBetweenInconvertibleTypes
        this.inferredTypeReference = (inputType == null || Void.class.equals(inputType))
                                     ? null
                                     : new TypeReference<I>() {
                                         @Override
                                         public Type getType() {
                                             return inputType;
                                         }
                                     };
    }

    /**
     * {@link StandardEndpoint} overrides this method to provide a default implementation that returns the inferred
     * {@link TypeReference} calculated in the constructor. This should be correct for most cases, meaning you don't
     * need to override this for POSTs/PUTs/etc in order to have your {@link RequestInfo#getContent()} populated,
     * however if the default provided here is not sufficient for some reason you always have the option of overriding
     * this yourself for your use case.
     * <p/>
     * See the javadocs for the interface method {@link Endpoint#requestContentType()} for more context of how this is
     * used.
     */
    @Override
    public TypeReference<I> requestContentType() {
        return inferredTypeReference;
    }
}
