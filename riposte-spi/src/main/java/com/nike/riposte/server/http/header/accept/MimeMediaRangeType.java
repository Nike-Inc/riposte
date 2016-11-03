package com.nike.riposte.server.http.header.accept;

import com.nike.riposte.server.http.mimetype.MimeType;
import com.nike.riposte.server.http.mimetype.MimeType.Type;

import java.util.Objects;

/**
 * Models a type that is a specific mime type, rather than a wildcard type.
 *
 * @author Kirk Peterson
 */
public class MimeMediaRangeType implements MediaRangeType {

    public final Type type;

    /**
     * Constructs a MediaRangeType instance using the given type.
     *
     * @throws IllegalArgumentException
     *     If the given type is null.
     */
    public MimeMediaRangeType(Type type) {
        if (type == null) {
            throw new IllegalArgumentException("type instance cannot be null.");
        }
        this.type = type;
    }

    /**
     * Returns the type used to describe this MediaRangeType.
     *
     * @return the type used to describe this MediaRangeType.
     */
    public MimeType.Type getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof MimeMediaRangeType)) {
            return false;
        }

        MimeMediaRangeType that = (MimeMediaRangeType) o;

        return Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    @Override
    public String toString() {
        return type.getName();
    }
}