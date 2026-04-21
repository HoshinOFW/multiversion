package com.github.hoshinofw.multiversion;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that modifies a class from a previous version.
 *
 * <p>By default the target is the class this annotation sits on (implicit same-name,
 * same-package target, preserved for backwards compatibility). To modify a class whose
 * name or package differs from the modifier, pass the target as a class literal:
 *
 * <pre>
 * {@literal @}ModifyClass(Foo.class)
 * public class FooNetworkPatch { ... }
 * </pre>
 *
 * <p>Multiple classes in the same version may target the same upstream class
 * (sibling modifiers). Siblings are virtually merged before the forward merge runs,
 * so each member signature may have at most one defining (new, {@code @OverwriteVersion},
 * or {@code @ModifySignature}) file per version.
 *
 * <p>Cannot sit on an inner class. Targeting an inner class is also not supported yet.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface ModifyClass {
    /**
     * The target class to modify. The default sentinel {@code ModifyClass.class} means
     * "this class modifies its same-named same-package upstream counterpart".
     */
    Class<?> value() default ModifyClass.class;
}
