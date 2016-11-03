package com.nike.riposte.server.http.mimetype;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Models an RFC-6838 Mime Type.
 *
 * @author Kirk Peterson
 */
@SuppressWarnings("WeakerAccess")
public class MimeType {

    public final Type type;
    public final SubType subType;
    public final Map<String, String> parameters;

    /**
     * Creates a MimeType instance with the given type, sub-type, and parameters values.
     *
     * @param type
     *     the type to assign to the mimetype
     * @param subType
     *     the subtype to assign to the mimetype
     * @param parameters
     *     the parameters to assign to the mimetype
     */
    private MimeType(final Type type, final SubType subType, final Map<String, String> parameters) {
        this.type = type;
        this.subType = subType;
        this.parameters = parameters;
    }

    /**
     * Creates a MimeType instance with the given type, sub-type, and parameters values.
     *
     * @param type
     *     the type to assign to the mimetype
     * @param subType
     *     the subtype to assign to the mimetype
     * @param parameters
     *     the parameters to assign to the mimetype
     *
     * @return a MimeType instance with the given type, sub-type, and an empty parameters map.
     */
    public static MimeType of(final Type type, final SubType subType, final Map<String, String> parameters) {
        return new MimeType(type, subType, parameters);
    }

    /**
     * Creates a MimeType instance with the given type and sub-type values.
     *
     * @param type
     *     the type to assign to the mimetype
     * @param subType
     *     the subtype to assign to the mimetype
     *
     * @return a MimeType instance with the given type, sub-type, and an empty parameters map.
     */
    public static MimeType of(final Type type, final SubType subType) {
        return of(type, subType, new HashMap<>());
    }

    /**
     * Returns this MimeType's top-level Type.
     *
     * @return this MimeType's top-level Type.
     */
    public Type getType() {
        return type;
    }

    /**
     * Returns this MimeType's SubType.
     *
     * @return this MimeType's SubType.
     */
    public SubType getSubType() {
        return subType;
    }

    /**
     * Returns this MimeType's parameters map.
     *
     * @return this MimeType's parameters map.
     */
    public Map<String, String> getParameters() {
        return parameters;
    }

    /**
     * Top-level types defined in RFC-6838
     */
    public static final class Type {

        /**
         * RFC-6838 4.2.5
         */
        public static final Type APPLICATION = new Type("application");

        /**
         * RFC-6838 4.2.1
         */
        public static final Type TEXT = new Type("text");

        /**
         * RFC-6838 4.2.2
         */
        public static final Type IMAGE = new Type("image");

        /**
         * RFC-6838 4.2.3
         */
        public static final Type AUDIO = new Type("audio");

        /**
         * RFC-6838 4.2.4
         */
        public static final Type VIDEO = new Type("video");

        /**
         * RFC-6838 4.2.6
         */
        public static final Type MULTIPART = new Type("multipart");

        /**
         * RFC-6838 4.2.6
         */
        public static final Type MESSAGE = new Type("message");

        public static final Type MODEL = new Type("model");
        public static final Type EXAMPLE = new Type("example");

        public static final List<Type> values = Collections.unmodifiableList(
            Arrays.asList(APPLICATION, TEXT, IMAGE, AUDIO, VIDEO, MULTIPART, MESSAGE, MODEL, EXAMPLE));

        public static final Map<String, Type> lowercaseNameToTypeValuesMap =
            Collections.unmodifiableMap(
                values.stream().collect(Collectors.toMap(type -> type.getName().toLowerCase(), Function.identity()))
            );

        public final String name;

        private Type(final String name) {
            this.name = name;
        }

        public String getName() {
            return this.name;
        }

        /**
         * Attempts to lookup a defined top-level type using the given type name.
         * Note, per RFC-6838 4.2, top-level type and subtype names are case-insensitive.
         *
         * @param typeName
         *     the name to lookup a Type instance by.
         *
         * @return The top-level Type instance identified by the given type name, if one is not found, then a new Type
         * instance is created with the given name.
         */
        public static Type of(final String typeName) {
            // return a top-level type instance if givenType's name matches, else create a new type
            final Type type = lowercaseNameToTypeValuesMap.get(typeName.toLowerCase());
            return type != null ? type : new Type(typeName);  // allow non-registered top-level types.
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            final Type type = (Type) o;

            return getName().equals(type.getName());

        }

        @Override
        public int hashCode() {
            return getName().hashCode();
        }
    }

    /**
     * Models the sub-type portion of a mime type. A sub-type is composed of a Facet, a Name and optionally a Suffix
     *
     * Some example subtypes:
     * <ul>
     *     <li>
     *         json -- a subtype in the STANDARD registration tree(facet), with the name of 'json', and no suffix.
     *     </li>
     *     <li>
     *         schema+json -- a subtype in the STANDARD registration tree(facet), with the name of 'schema', and the
     *         suffix of 'json'.
     *     </li>
     *     <li>
     *         vnd.nike.fooservice-v3.1+json -- a subtype in the VENDOR registration tree(facet), with the name
     *         'nike.fooservice-v3.1', and the suffix of 'json'.
     *     </li>
     *     <li>
     *         x.nike.supersecret-payload -- a subtype in the UNREGISTERED registration tree(facet), with the name
     *         'nike.supersecret-payload', and no suffix.
     *     </li>
     * </ul>
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public static class SubType {

        public final Facet facet;
        public final String name;
        public final Optional<String> suffix;

        private SubType(final Facet facet, final String name, final Optional<String> suffix) {
            this.facet = facet;
            this.name = name;
            this.suffix = suffix;
        }

        public Facet getFacet() {
            return facet;
        }

        public String getName() {
            return name;
        }

        public Optional<String> getSuffix() {
            return suffix;
        }


        /**
         * Creates a SubType assigned with the given name, suffix, and facet.
         * Note, per RFC-6838 4.2, top-level type and subtype names are case-insensitive.
         *
         * @param facet
         *     the facet to assign the subtype
         * @param name
         *     the name to assign the subtype
         * @param suffix
         *     the suffix to assign the subtype
         *
         * @return a SubType assigned with the given name, suffix, and facet
         */
        public static SubType of(final Facet facet, final String name, final Optional<String> suffix) {
            return new SubType(facet, name, suffix);
        }

        /**
         * Creates a SubType with a Name assigned to the given name, an empty suffix, and assigned to the STANDARD
         * facet.
         *
         * @param name
         *     the name of this subtype
         *
         * @return a SubType with a Name assigned to the given name, an empty suffix, and assigned to the STANDARD
         * facet.
         */
        public static SubType of(final String name) {
            return new SubType(Facet.STANDARD, name, Optional.empty());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            final SubType subType = (SubType) o;

            if (getFacet() != subType.getFacet())
                return false;

            //noinspection SimplifiableIfStatement
            if (!getName().equals(subType.getName()))
                return false;

            return getSuffix().equals(subType.getSuffix());

        }

        @Override
        public int hashCode() {
            int result = getFacet().hashCode();
            result = 122949829 * result + getName().hashCode();
            result = 122949829 * result + getSuffix().hashCode();
            return result;
        }
    }

    /**
     * Models the registration tree names (facets) defined by RFC-6838
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public enum Facet {

        /**
         * RFC-6838 3.2
         */
        VENDOR(Optional.of("vnd")),

        /**
         * RFC-6838 3.3
         */
        PERSONAL(Optional.of("prs")),

        /**
         * RFC-6838 3.4
         */
        UNREGISTERED(Optional.of("x")),

        /**
         * RFC-6838 3.1
         */
        STANDARD(Optional.empty());

        private Optional<String> registrationTreeName;

        Facet(final Optional<String> registrationTreeName) {
            this.registrationTreeName = registrationTreeName;
        }

        @SuppressWarnings("OptionalGetWithoutIsPresent")
        public static final Map<String, Facet> lowercaseRegistrationTreeNameToFacetValuesMap =
            Collections.unmodifiableMap(
                Stream.of(Facet.values())
                      .filter(facet -> facet.getRegistrationTreeName().isPresent())
                      .collect(
                          Collectors.toMap(facet -> facet.getRegistrationTreeName().get().toLowerCase(),
                                           Function.identity())
                      )
            );

        /**
         * The registration tree name for this Facet, if one exits.
         *
         * @return The registration tree name for this Facet, if one exits.
         */
        public Optional<String> getRegistrationTreeName() {
            return registrationTreeName;
        }

        /**
         * Attempts to find a a Facet by its registrationTreeName.
         *
         * @param registrationTreeName
         *     the registration tree name to match a Facet instance to.
         *
         * @return An Optional<Facet> with a registrationTreeName value that matches the given registrationTreeName, if
         * one exits, else Optional.empty()
         */
        public static Optional<Facet> forRegistrationTreeName(final String registrationTreeName) {
            return Optional.ofNullable(registrationTreeName).flatMap(
                name -> Optional.ofNullable(lowercaseRegistrationTreeNameToFacetValuesMap.get(name))
            );
        }

    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        final MimeType mimeType = (MimeType) o;

        if (!getType().equals(mimeType.getType()))
            return false;

        //noinspection SimplifiableIfStatement
        if (!getSubType().equals(mimeType.getSubType()))
            return false;

        return getParameters().equals(mimeType.getParameters());

    }

    @Override
    public int hashCode() {
        int result = getType().hashCode();
        result = 67867979 * result + getSubType().hashCode();
        result = 67867979 * result + getParameters().hashCode();
        return result;
    }

    private String toStringCache = null;

    @Override
    public String toString() {
        if (toStringCache == null) {
            toStringCache =
                type.getName() +
                "/" +
                subType.getFacet().getRegistrationTreeName().map(tree -> tree + ".").orElse("") +
                subType.getName() +
                subType.getSuffix().map(suffix -> "+" + suffix).orElse("") +
                parameters.entrySet().stream().map(entry -> ";" + entry.getKey() + "=" + entry.getValue())
                          .collect(Collectors.joining());
        }
        return toStringCache;
    }
}
