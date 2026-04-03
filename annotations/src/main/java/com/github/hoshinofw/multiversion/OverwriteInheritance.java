package com.github.hoshinofw.multiversion;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces the base version's {@code extends} and {@code implements} clauses with this
 * version's declarations. Use when a class changes its parent or the interfaces it implements.
 *
 * <p>The entire inheritance declaration is replaced — partial updates are not supported.
 * To remove an interface, simply omit it from the version class's {@code implements} list.
 *
 * <p>Example:
 * <pre>
 * // 1.21.1/common
 * {@literal @}OverwriteInheritance
 * public class MyClass extends NewParent implements NewInterface { }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface OverwriteInheritance {
}
