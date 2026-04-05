package com.github.hoshinofw.multiversion

/**
 * Root-level DSL extension registered as {@code multiversionConfiguration { }} in the root build.gradle.
 *
 * <p>Contains project-wide plugin behaviour settings.
 *
 * <p>Example:
 * <pre>
 * multiversionConfiguration {
 *     automaticArchApi = true
 * }
 * </pre>
 */
class MultiversionConfigurationExtension {

    /**
     * When true, the plugin reads {@code architectury_api_version} from gradle.properties
     * and automatically adds the Architectury API dependency to every module enrolled in an
     * {@code architectury*} list in {@code multiversionModules}.
     *
     * <p>When false (default), the property is ignored entirely and no warning is emitted.
     * Add the Architectury API dependency manually if needed.
     */
    boolean automaticArchApi = false
}