/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.types;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import net.sourceforge.pmd.internal.util.AssertionUtil;
import net.sourceforge.pmd.lang.java.ast.ASTList;
import net.sourceforge.pmd.lang.java.ast.InternalApiBridge;
import net.sourceforge.pmd.lang.java.ast.InvocationNode;
import net.sourceforge.pmd.lang.java.ast.TypeNode;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeDeclSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeParameterSymbol;
import net.sourceforge.pmd.lang.java.symbols.internal.UnresolvedClassStore;
import net.sourceforge.pmd.util.OptionalBool;

/**
 * Public utilities to test the type of nodes.
 */
public final class TypeTestUtil {

    private TypeTestUtil() {
        // utility class
    }


    /**
     * Checks whether the static type of the node is a subtype of the
     * class identified by the given name. This ignores type arguments,
     * if the type of the node is parameterized. Examples:
     *
     * <pre>{@code
     * isA(List.class, <new ArrayList<String>()>)      = true
     * isA(ArrayList.class, <new ArrayList<String>()>) = true
     * isA(int[].class, <new int[0]>)                  = true
     * isA(Object[].class, <new String[0]>)            = true
     * isA(_, null) = false
     * isA(null, _) = NullPointerException
     * }</pre>
     *
     * <p>If either type is unresolved, the types are tested for equality,
     * thus giving more useful results than {@link JTypeMirror#isSubtypeOf(JTypeMirror)}.
     *
     * <p>Note that primitives are NOT considered subtypes of one another
     * by this method, even though {@link JTypeMirror#isSubtypeOf(JTypeMirror)} does.
     *
     * @param clazz a class (non-null)
     * @param node  the type node to check
     *
     * @return true if the type test matches
     *
     * @throws NullPointerException if the class parameter is null
     */
    public static boolean isA(final @NonNull Class<?> clazz, final @Nullable TypeNode node) {
        AssertionUtil.requireParamNotNull("class", clazz);
        if (node == null) {
            return false;
        }

        return hasNoSubtypes(clazz) ? isExactlyA(clazz, node)
                                    : isA(clazz, node.getTypeMirror());
    }


    private static boolean isA(@NonNull Class<?> clazz, @Nullable JTypeMirror type) {
        AssertionUtil.requireParamNotNull("klass", clazz);
        if (type == null) {
            return false;
        }

        JTypeMirror otherType = TypesFromReflection.fromReflect(clazz, type.getTypeSystem());

        if (otherType == null || TypeOps.isUnresolved(type) || otherType.isPrimitive()) {
            // We'll return true if the types have equal symbols (same binary name),
            // but we ignore subtyping.
            return isExactlyA(clazz, type.getSymbol());
        }

        return type.isSubtypeOf(otherType);
    }


    /**
     * Checks whether the static type of the node is a subtype of the
     * class identified by the given name. See {@link #isA(Class, TypeNode)}
     * for examples and more info.
     *
     * @param canonicalName the canonical name of a class or array type (without whitespace)
     * @param node          the type node to check
     *
     * @return true if the type test matches
     *
     * @throws NullPointerException     if the class name parameter is null
     * @throws IllegalArgumentException if the class name parameter is not a valid java binary name,
     *                                  eg it has type arguments
     * @see #isA(Class, TypeNode)
     */
    public static boolean isA(final @NonNull String canonicalName, final @Nullable TypeNode node) {
        AssertionUtil.requireParamNotNull("canonicalName", (Object) canonicalName);
        if (node == null) {
            return false;
        }

        UnresolvedClassStore unresolvedStore = InternalApiBridge.getProcessor(node).getUnresolvedStore();
        return isA(canonicalName, node.getTypeMirror(), unresolvedStore);
    }

    private static boolean isA(@NonNull String canonicalName, @Nullable JTypeMirror thisType) {
        AssertionUtil.requireParamNotNull("canonicalName", (Object) canonicalName);
        if (thisType == null) {
            return false;
        }

        return isA(canonicalName, thisType, null);
    }

    private static boolean isA(@NonNull String canonicalName, @NonNull JTypeMirror thisType, @Nullable UnresolvedClassStore unresolvedStore) {

        OptionalBool exactMatch = isExactlyAOrAnon(canonicalName, thisType);
        if (exactMatch != OptionalBool.NO) {
            return exactMatch == OptionalBool.YES; // otherwise anon, and we return false
        }

        JTypeDeclSymbol thisClass = thisType.getSymbol();
        if (thisClass instanceof JClassSymbol && ((JClassSymbol) thisClass).isAnnotation()) {
            return isAnnotationSuperType(canonicalName);
        }

        if (thisClass != null && thisClass.isUnresolved()) {
            // we can't get any useful info from this, isSubtypeOf would return true
            // do not test for equality, we already checked isExactlyA, which has its fallback
            return false;
        }

        TypeSystem ts = thisType.getTypeSystem();
        @Nullable JTypeMirror otherType = TypesFromReflection.loadType(ts, canonicalName, unresolvedStore);
        if (otherType == null
            || otherType.isClassOrInterface() && ((JClassType) otherType).getSymbol().isAnonymousClass()) {
            return false; // we know isExactlyA(canonicalName, node); returned false
        } else if (otherType.isPrimitive()) {
            return otherType == thisType; // isSubtypeOf considers primitive widening like subtyping
        }

        return thisType.isSubtypeOf(otherType);
    }

    private static boolean isAnnotationSuperType(String clazzName) {
        AssertionUtil.assertValidJavaBinaryName(clazzName);
        // then, the supertype may only be Object, j.l.Annotation
        // this is used e.g. by the typeIs function in XPath
        return "java.lang.annotation.Annotation".equals(clazzName)
            || "java.lang.Object".equals(clazzName);
    }

    /**
     * Checks whether the static type of the node is exactly the type
     * of the class. This ignores strict supertypes, and type arguments,
     * if the type of the node is parameterized.
     *
     * <pre>{@code
     * isExactlyA(List.class, <new ArrayList<String>()>)      = false
     * isExactlyA(ArrayList.class, <new ArrayList<String>()>) = true
     * isExactlyA(int[].class, <new int[0]>)                  = true
     * isExactlyA(Object[].class, <new String[0]>)            = false
     * isExactlyA(_, null) = false
     * isExactlyA(null, _) = NullPointerException
     * }</pre>
     *
     * @param clazz a class (non-null)
     * @param node  the type node to check
     *
     * @return true if the node is non-null and has the given type
     *
     * @throws NullPointerException if the class parameter is null
     */
    public static boolean isExactlyA(final @NonNull Class<?> clazz, final @Nullable TypeNode node) {
        AssertionUtil.requireParamNotNull("class", clazz);
        if (node == null) {
            return false;
        }

        return isExactlyA(clazz, node.getTypeMirror().getSymbol());
    }

    public static boolean isExactlyA(@NonNull Class<?> klass, @Nullable JTypeDeclSymbol type) {
        AssertionUtil.requireParamNotNull("klass", klass);
        if (!(type instanceof JClassSymbol)) {
            // Class cannot reference a type parameter
            return false;
        }

        JClassSymbol symClass = (JClassSymbol) type;

        if (klass.isArray()) {
            return symClass.isArray() && isExactlyA(klass.getComponentType(), symClass.getArrayComponent());
        }

        // Note: klass.getName returns a type descriptor for arrays,
        // which is why we have to destructure the array above
        return symClass.getBinaryName().equals(klass.getName());
    }

    /**
     * Returns true if the signature is that of a method declared in the
     * given class.
     *
     * @param klass Class
     * @param sig   Method signature to test
     *
     * @throws NullPointerException If any argument is null
     */
    public static boolean isDeclaredInClass(@NonNull Class<?> klass, @NonNull JMethodSig sig) {
        return isExactlyA(klass, sig.getDeclaringType().getSymbol());
    }


    /**
     * Checks whether the static type of the node is exactly the type
     * given by the name. See {@link #isExactlyA(Class, TypeNode)} for
     * examples and more info.
     *
     * @param canonicalName a canonical name of a class or array type
     * @param node          the type node to check
     *
     * @return true if the node is non-null and has the given type
     *
     * @throws NullPointerException     if the class name parameter is null
     * @throws IllegalArgumentException if the class name parameter is not a valid java binary name,
     *                                  eg it has type arguments
     * @see #isExactlyA(Class, TypeNode)
     */
    public static boolean isExactlyA(@NonNull String canonicalName, final @Nullable TypeNode node) {
        if (node == null) {
            return false;
        }
        return isExactlyAOrAnon(canonicalName, node.getTypeMirror()) == OptionalBool.YES;
    }

    private static OptionalBool isExactlyAOrAnon(@NonNull String canonicalName, final @NonNull JTypeMirror node) {
        AssertionUtil.requireParamNotNull("canonicalName", canonicalName);

        JTypeDeclSymbol sym = node.getSymbol();
        if (sym == null || sym instanceof JTypeParameterSymbol) {
            return OptionalBool.NO;
        }

        canonicalName = StringUtils.deleteWhitespace(canonicalName);

        JClassSymbol klass = (JClassSymbol) sym;
        String canonical = klass.getCanonicalName();
        if (canonical == null) {
            return OptionalBool.UNKNOWN; // anonymous
        }
        return OptionalBool.definitely(canonical.equals(canonicalName));
    }


    private static boolean hasNoSubtypes(Class<?> clazz) {
        // Neither final nor an annotation. Enums & records have ACC_FINAL
        // Note: arrays have ACC_FINAL, but have subtypes by covariance
        // Note: annotations may be implemented by classes
        return Modifier.isFinal(clazz.getModifiers()) && !clazz.isArray() || clazz.isPrimitive();
    }


    /**
     * Matches a method signature.
     */
    public static final class MethodSigMatcher {

        final String expectedName;
        final List<TypeMatcher> argMatchers;
        final TypeMatcher qualifierMatcher;

        MethodSigMatcher(TypeMatcher qualifierMatcher, String expectedName, List<TypeMatcher> argMatchers) {
            this.expectedName = expectedName;
            this.argMatchers = argMatchers;
            this.qualifierMatcher = qualifierMatcher;
        }

        public boolean matchesCall(InvocationNode node) {
            if (!node.getMethodName().equals(expectedName)
                || ASTList.sizeOrZero(node.getArguments()) != argMatchers.size()) {
                return false;
            }
            OverloadSelectionResult info = node.getOverloadSelectionInfo();
            if (info.isFailed()) {
                return false;
            }
            return matchesSig(info.getMethodType());
        }

        public boolean matchesSig(JMethodSig invoc) {
            if (!invoc.getName().equals(expectedName)) {
                return false;
            }
            List<JTypeMirror> formals = invoc.getFormalParameters();
            if (invoc.getArity() != argMatchers.size()) {
                return false;
            }
            for (int i = 0; i < formals.size(); i++) {
                if (!argMatchers.get(i).matches(formals.get(i))) {
                    return false;
                }
            }
            return qualifierMatcher.matches(invoc.getDeclaringType());
        }


        /**
         * Parses a {@link MethodSigMatcher} from a string.
         *
         * @param qualifierName Type matcher for the qualifier (either "_", or a qualified name).
         *                      This will be matched with {@link #isA(String, TypeNode)}
         * @param sig           A signature in the form {@code name(arg1, arg2, ...)},
         *                      where each {@code argi} is either {@code _} or a qualified
         *                      type name, without type arguments.
         *                      These will be matched with {@link #isExactlyA(String, TypeNode)}.
         *
         * @return A sig matcher
         *
         * @throws IllegalArgumentException If the parameters are malformed
         * @throws NullPointerException     If the parameters are null
         */
        public static MethodSigMatcher parse(String qualifierName, String sig) {
            AssertionUtil.assertValidJavaBinaryName(qualifierName);
            int i = 0;
            while (i < sig.length() && Character.isJavaIdentifierPart(sig.charAt(i))) {
                i++;
            }
            final String methodName = sig.substring(0, i);
            if (methodName.isEmpty()) {
                throw new IllegalArgumentException("Not a valid signature " + sig);
            }
            i = consumeChar(sig, i, '(');
            if (isChar(sig, i, ')')) {
                return new MethodSigMatcher(newMatcher(qualifierName, false), methodName, Collections.emptyList());
            }
            List<TypeMatcher> argMatchers = new ArrayList<>();
            i = parseArgList(sig, i, argMatchers);
            if (i != sig.length()) {
                throw new IllegalArgumentException("Not a valid signature " + sig);
            }
            return new MethodSigMatcher(newMatcher(qualifierName, false), methodName, argMatchers);
        }

        private static int parseArgList(String sig, int i, List<TypeMatcher> argMatchers) {
            while (i < sig.length()) {
                i = parseType(sig, i, argMatchers, true);
                if (isChar(sig, i, ')')) {
                    return i + 1;
                }
                i = consumeChar(sig, i, ',');
            }
            throw new IllegalArgumentException("Not a valid signature " + sig);
        }

        private static int consumeChar(String source, int i, char c) {
            if (isChar(source, i, c)) {
                return i + 1;
            }
            throw new IllegalArgumentException("Expected " + c + " at index " + i);
        }

        private static boolean isChar(String source, int i, char c) {
            return i < source.length() && source.charAt(i) == c;
        }

        private static int parseType(String source, int i, List<TypeMatcher> result, boolean exact) {
            final int start = i;
            while (i < source.length() && (Character.isJavaIdentifierPart(source.charAt(i))
                || source.charAt(i) == '.')) {
                i++;
            }
            String name = source.substring(start, i);
            AssertionUtil.assertValidJavaBinaryName(name);
            result.add(newMatcher(name, exact));
            return i;
        }

        private static TypeMatcher newMatcher(String name, boolean exact) {
            return "_".equals(name) ? TypeMatcher.ANY : new TypeMatcher(name, exact);
        }

        private static final class TypeMatcher {

            /** Matches any type. */
            public static final TypeMatcher ANY = new TypeMatcher(null, false);

            final @Nullable String name;
            private final boolean exact;

            private TypeMatcher(@Nullable String name, boolean exact) {
                this.name = name;
                this.exact = exact;
            }

            boolean matches(JTypeMirror type) {
                if (name == null) {
                    return true;
                }
                return exact ? TypeTestUtil.isExactlyAOrAnon(name, type) == OptionalBool.YES
                             : TypeTestUtil.isA(name, type);
            }
        }
    }

}
