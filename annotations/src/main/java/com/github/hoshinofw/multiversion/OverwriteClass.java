package com.github.hoshinofw.multiversion;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface OverwriteClass {
    /**
     * Specify that this class fully overwrites another in a previous version
     * Two classes in the same version cannot share an overwrite target.
     */

    Class<?> target();
}
