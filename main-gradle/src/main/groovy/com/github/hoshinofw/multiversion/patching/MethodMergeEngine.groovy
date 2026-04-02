package com.github.hoshinofw.multiversion.patching

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.AnnotationMemberDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.printer.DefaultPrettyPrinter
import org.gradle.api.GradleException

class MethodMergeEngine {

    private static final JavaParser PARSER = new JavaParser(
        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    // ---- Entry point ----

    static void processVersion(
            File currentSrcDir,
            File baseDir,
            File patchedOutDir,
            String currentSrcRelRoot,
            String baseRelRoot,
            BufferedWriter mapOut
    ) {
        if (!currentSrcDir.exists()) return

        currentSrcDir.eachFileRecurse { File currentFile ->
            if (!currentFile.name.endsWith(".java")) return

            String rel = currentSrcDir.toPath().relativize(currentFile.toPath()).toString().replace('\\', '/')
            File outFile = new File(patchedOutDir, rel)
            if (!outFile.exists()) return
            File baseFile = new File(baseDir, rel)

            if (!baseFile.exists() && !currentFile.text.contains("DeleteClass")) return

            try {
                mergeFile(currentFile, baseFile, outFile, rel, currentSrcRelRoot, baseRelRoot, mapOut)
            } catch (GradleException e) {
                throw e
            } catch (Exception e) {
                throw new GradleException("MethodMergeEngine failed merging ${rel}: ${e.message}", e)
            }
        }
    }

    // ---- Orchestrator ----
    // Parses both compilation units, routes deletes and member merging to the appropriate
    // type-specific methods, then writes the merged output. Each type-specific merge method
    // is self-contained and will eventually move to its own engine class.

    private static void mergeFile(
            File currentFile,
            File baseFile,
            File outFile,
            String rel,
            String currentSrcRelRoot,
            String baseRelRoot,
            BufferedWriter mapOut
    ) {
        CompilationUnit currentCU = PARSER.parse(currentFile).getResult().get()
        def currentCls = currentCU.findFirst(TypeDeclaration.class).orElse(null)
        if (currentCls == null) return

        if (currentCls.getAnnotationByName("DeleteClass").isPresent()) {
            outFile.delete()
            return
        }

        if (!baseFile.exists()) return

        if (!hasTriggers(currentCls)) {
            if (mapOut != null) writeNonAnnotatedOrigins(currentCls, rel, currentSrcRelRoot, mapOut)
            return
        }

        CompilationUnit baseCU = PARSER.parse(baseFile).getResult().get()
        def baseCls = baseCU.findFirst(TypeDeclaration.class).orElse(null)
        if (baseCls == null) return

        if (currentCls.getAnnotationByName("DeleteMethodsAndFields").isPresent())
            applyDeletes(currentCls, baseCls, rel)

        mergeSharedMembers(currentCls, baseCls, rel, currentSrcRelRoot, baseRelRoot, mapOut)
        mergeEnumEntries(currentCls, baseCls, rel, currentSrcRelRoot, baseRelRoot, mapOut)
        mergeAnnotationTypeMembers(currentCls, baseCls, rel, currentSrcRelRoot, baseRelRoot, mapOut)
        mergeClassAnnotations(currentCls, baseCls)

        currentCU.getImports().each { imp ->
            if (!baseCU.getImports().any { it.getNameAsString() == imp.getNameAsString() && it.isStatic() == imp.isStatic() })
                baseCU.addImport(imp.getNameAsString(), imp.isStatic(), imp.isAsterisk())
        }

        baseCU.getAllComments().forEach { it.remove() }
        outFile.write(new DefaultPrettyPrinter().print(baseCU), "UTF-8")
    }

    // ---- Shared member merging: methods, fields, constructors ----
    // Applicable to all type kinds. Will be shared infrastructure for all future engine classes.

    private static void mergeSharedMembers(
            def currentCls, def baseCls, String rel,
            String currentSrcRelRoot, String baseRelRoot, BufferedWriter mapOut
    ) {
        Map<String, Integer> methodOrigins = [:]
        Map<String, Integer> fieldOrigins = [:]
        Map<String, Integer> constructorOrigins = [:]

        currentCls.getMethods().each { MethodDeclaration method ->
            String desc = descriptor(method)
            List<String> mParams = method.getParameters().collect { simpleTypeName(it.getTypeAsString()) }
            boolean inBase = baseCls.getMethods().any { it.getNameAsString() == method.getNameAsString() && paramsMatch(it, mParams) }

            if (method.getAnnotationByName("ShadowVersion").isPresent()) {
                if (!inBase) throw new GradleException("@ShadowVersion: '${desc}' not found in base for ${rel}")
                return
            }

            if (method.isAbstract() && !inBase &&
                    baseCls instanceof ClassOrInterfaceDeclaration &&
                    !baseCls.isAbstract() && !baseCls.isInterface()) {
                baseCls.setAbstract(true)
            }

            if (method.getAnnotationByName("OverwriteVersion").isPresent()) {
                if (!inBase) throw new GradleException("@OverwriteVersion: '${desc}' not found in base for ${rel}")
                MethodDeclaration target = findByParams(
                        baseCls.getMethods().findAll { it.getNameAsString() == method.getNameAsString() },
                        mParams, method.getNameAsString(), rel)
                method.getAnnotationByName("OverwriteVersion").ifPresent { it.remove() }
                baseCls.getMembers().set(baseCls.getMembers().indexOf(target), method.clone())
                methodOrigins[desc] = method.getBegin().map { it.line }.orElse(0)
            } else if (inBase) {
                throw new GradleException("Method '${desc}' exists in both versions of ${rel} without @OverwriteVersion or @ShadowVersion. Annotate it explicitly")
            } else {
                baseCls.addMember(method.clone())
                methodOrigins[desc] = method.getBegin().map { it.line }.orElse(0)
            }
        }

        currentCls.getFields().each { FieldDeclaration field ->
            String fName = field.getVariables()[0].getNameAsString()
            boolean inBase = baseCls.getFields().any { bf -> bf.getVariables().any { it.getNameAsString() == fName } }

            if (field.getAnnotationByName("ShadowVersion").isPresent()) {
                if (!inBase) throw new GradleException("@ShadowVersion: field '${fName}' not found in base for ${rel}")
                return
            }

            if (field.getAnnotationByName("OverwriteVersion").isPresent()) {
                if (!inBase) throw new GradleException("@OverwriteVersion on field '${fName}' not found in base for ${rel}")
                FieldDeclaration target = baseCls.getFields().find { bf -> bf.getVariables().any { it.getNameAsString() == fName } }
                field.getAnnotationByName("OverwriteVersion").ifPresent { it.remove() }
                baseCls.getMembers().set(baseCls.getMembers().indexOf(target), field.clone())
                fieldOrigins[fName] = field.getBegin().map { it.line }.orElse(0)
            } else if (!inBase) {
                baseCls.addMember(field.clone())
                fieldOrigins[fName] = field.getBegin().map { it.line }.orElse(0)
            } else {
                throw new GradleException("Field '${fName}' exists in both versions of ${rel} without @OverwriteVersion or @ShadowVersion")
            }
        }

        getTypeConstructors(currentCls).each { ConstructorDeclaration ctor ->
            String desc = constructorDescriptor(ctor)
            List<String> mParams = ctor.getParameters().collect { simpleTypeName(it.getTypeAsString()) }
            boolean inBase = getTypeConstructors(baseCls).any { paramsMatch(it, mParams) }

            if (ctor.getAnnotationByName("ShadowVersion").isPresent()) {
                if (!inBase) throw new GradleException("@ShadowVersion: constructor '${desc}' not found in base for ${rel}")
                return
            }

            if (ctor.getAnnotationByName("OverwriteVersion").isPresent()) {
                if (!inBase) throw new GradleException("@OverwriteVersion: constructor '${desc}' not found in base for ${rel}")
                def target = findByParams(getTypeConstructors(baseCls), mParams, "init", rel)
                ctor.getAnnotationByName("OverwriteVersion").ifPresent { it.remove() }
                baseCls.getMembers().set(baseCls.getMembers().indexOf(target), ctor.clone())
                constructorOrigins[desc] = ctor.getBegin().map { it.line }.orElse(0)
            } else if (inBase) {
                throw new GradleException("Constructor '${desc}' exists in both versions of ${rel} without @OverwriteVersion or @ShadowVersion. Annotate it explicitly")
            } else {
                baseCls.addMember(ctor.clone())
                constructorOrigins[desc] = ctor.getBegin().map { it.line }.orElse(0)
            }
        }

        if (mapOut == null) return

        baseCls.getMethods().each { MethodDeclaration m ->
            String desc = descriptor(m)
            String src  = methodOrigins.containsKey(desc) ? currentSrcRelRoot : baseRelRoot
            int line    = methodOrigins.containsKey(desc) ? methodOrigins[desc] : m.getBegin().map { it.line }.orElse(0)
            mapOut.write("${rel}#${desc}\t${src}/${rel}:${line}\n")
        }
        getTypeConstructors(baseCls).each { ConstructorDeclaration c ->
            String desc = constructorDescriptor(c)
            String src  = constructorOrigins.containsKey(desc) ? currentSrcRelRoot : baseRelRoot
            int line    = constructorOrigins.containsKey(desc) ? constructorOrigins[desc] : c.getBegin().map { it.line }.orElse(0)
            mapOut.write("${rel}#${desc}\t${src}/${rel}:${line}\n")
        }
        baseCls.getFields().each { FieldDeclaration f ->
            String fName = f.getVariables()[0].getNameAsString()
            String src   = fieldOrigins.containsKey(fName) ? currentSrcRelRoot : baseRelRoot
            int line     = fieldOrigins.containsKey(fName) ? fieldOrigins[fName] : f.getBegin().map { it.line }.orElse(0)
            mapOut.write("${rel}#${fName}\t${src}/${rel}:${line}\n")
        }
    }

    // ---- Enum constant merging ----
    // No-op for non-enum types. Will become EnumMergeEngine.

    private static void mergeEnumEntries(
            def currentCls, def baseCls, String rel,
            String currentSrcRelRoot, String baseRelRoot, BufferedWriter mapOut
    ) {
        def currentEntries = getEnumEntries(currentCls)
        def baseEntries    = getEnumEntries(baseCls)
        if (currentEntries.isEmpty() && baseEntries.isEmpty()) return

        Map<String, Integer> enumConstantOrigins = [:]

        currentEntries.each { EnumConstantDeclaration entry ->
            String cName  = entry.getNameAsString()
            boolean inBase = baseEntries.any { it.getNameAsString() == cName }

            if (entry.getAnnotationByName("ShadowVersion").isPresent()) {
                if (!inBase) throw new GradleException("@ShadowVersion: enum constant '${cName}' not found in base for ${rel}")
                return
            }

            if (entry.getAnnotationByName("OverwriteVersion").isPresent()) {
                if (!inBase) throw new GradleException("@OverwriteVersion: enum constant '${cName}' not found in base for ${rel}")
                def target = baseEntries.find { it.getNameAsString() == cName }
                entry.getAnnotationByName("OverwriteVersion").ifPresent { it.remove() }
                baseEntries.set(baseEntries.indexOf(target), entry.clone())
                enumConstantOrigins[cName] = entry.getBegin().map { it.line }.orElse(0)
            } else if (inBase) {
                throw new GradleException("Enum constant '${cName}' exists in both versions of ${rel} without @OverwriteVersion or @ShadowVersion")
            } else {
                baseEntries.add(entry.clone())
                enumConstantOrigins[cName] = entry.getBegin().map { it.line }.orElse(0)
            }
        }

        if (mapOut == null) return

        baseEntries.each { EnumConstantDeclaration e ->
            String cName = e.getNameAsString()
            String src   = enumConstantOrigins.containsKey(cName) ? currentSrcRelRoot : baseRelRoot
            int line     = enumConstantOrigins.containsKey(cName) ? enumConstantOrigins[cName] : e.getBegin().map { it.line }.orElse(0)
            mapOut.write("${rel}#${cName}\t${src}/${rel}:${line}\n")
        }
    }

    // ---- Annotation type member merging ----
    // No-op for non-annotation types. Will become AnnotationMergeEngine.

    private static void mergeAnnotationTypeMembers(
            def currentCls, def baseCls, String rel,
            String currentSrcRelRoot, String baseRelRoot, BufferedWriter mapOut
    ) {
        def currentMembers = getAnnotationMembers(currentCls)
        def baseMembers    = getAnnotationMembers(baseCls)
        if (currentMembers.isEmpty() && baseMembers.isEmpty()) return

        Map<String, Integer> annotationMemberOrigins = [:]

        currentMembers.each { AnnotationMemberDeclaration member ->
            String mName   = member.getNameAsString()
            boolean inBase = baseMembers.any { it.getNameAsString() == mName }

            if (member.getAnnotationByName("ShadowVersion").isPresent()) {
                if (!inBase) throw new GradleException("@ShadowVersion: annotation member '${mName}()' not found in base for ${rel}")
                return
            }

            if (member.getAnnotationByName("OverwriteVersion").isPresent()) {
                if (!inBase) throw new GradleException("@OverwriteVersion: annotation member '${mName}()' not found in base for ${rel}")
                def target = baseMembers.find { it.getNameAsString() == mName }
                member.getAnnotationByName("OverwriteVersion").ifPresent { it.remove() }
                baseCls.getMembers().set(baseCls.getMembers().indexOf(target), member.clone())
                annotationMemberOrigins[mName] = member.getBegin().map { it.line }.orElse(0)
            } else if (inBase) {
                throw new GradleException("Annotation member '${mName}()' exists in both versions of ${rel} without @OverwriteVersion or @ShadowVersion")
            } else {
                baseCls.addMember(member.clone())
                annotationMemberOrigins[mName] = member.getBegin().map { it.line }.orElse(0)
            }
        }

        if (mapOut == null) return

        getAnnotationMembers(baseCls).each { AnnotationMemberDeclaration m ->
            String mName = m.getNameAsString()
            String src   = annotationMemberOrigins.containsKey(mName) ? currentSrcRelRoot : baseRelRoot
            int line     = annotationMemberOrigins.containsKey(mName) ? annotationMemberOrigins[mName] : m.getBegin().map { it.line }.orElse(0)
            mapOut.write("${rel}#${mName}()\t${src}/${rel}:${line}\n")
        }
    }

    // ---- Class-level annotation merging ----
    // Current version wins for any annotation it declares: if the base also has it, the base's
    // copy is replaced. Annotations only in the base are left intact. Multiversion processing
    // annotations are excluded since they are directives, not output annotations.

    private static final Set<String> PROCESSING_ANNOTATIONS = ["DeleteMethodsAndFields", "DeleteClass"] as Set

    private static void mergeClassAnnotations(def currentCls, def baseCls) {
        currentCls.getAnnotations().each { currentAnn ->
            if (currentAnn.getNameAsString() in PROCESSING_ANNOTATIONS) return
            baseCls.getAnnotationByName(currentAnn.getNameAsString()).ifPresent { it.remove() }
            baseCls.getAnnotations().add(currentAnn.clone())
        }
    }

    // ---- Delete dispatching ----
    // Routes each descriptor from @DeleteMethodsAndFields to the correct member kind.
    // Precedence: constructor -> enum constant -> field -> annotation member -> method.

    private static void applyDeletes(def currentCls, def baseCls, String rel) {
        for (String desc : extractDeleteDescriptors(currentCls)) {
            String name = desc.contains("(") ? desc.substring(0, desc.indexOf("(")) : desc

            if (name == "init") {
                List<ConstructorDeclaration> ctors = getTypeConstructors(baseCls)
                if (ctors.isEmpty()) throw new GradleException("@Delete: no constructors found in base for ${rel}")
                List<String> params = desc.contains("(") ? parseParamTypes(desc) : null
                if (params == null && ctors.size() > 1)
                    throw new GradleException("@Delete: 'init' in ${rel} has ${ctors.size()} constructors; add parameter types to disambiguate")
                findByParams(ctors, params ?: [], "init", rel).remove()
                continue
            }

            def enumEntry = getEnumEntries(baseCls).find { it.getNameAsString() == name }
            if (enumEntry != null) { enumEntry.remove(); continue }

            FieldDeclaration field = baseCls.getFields().find { fd -> !desc.contains("(") && fd.getVariables().any { it.getNameAsString() == name } }
            if (field != null) { field.remove(); continue }

            def annMember = getAnnotationMembers(baseCls).find { it.getNameAsString() == name }
            if (annMember != null) { annMember.remove(); continue }

            List<String> params = desc.contains("(") ? parseParamTypes(desc) : null
            List<MethodDeclaration> overloads = baseCls.getMethods().findAll { it.getNameAsString() == name }
            if (overloads.isEmpty()) throw new GradleException("@Delete: '${name}' not found in base for ${rel}")
            if (params == null && overloads.size() > 1)
                throw new GradleException("@Delete: '${name}' in ${rel} has ${overloads.size()} overloads; add parameter types to disambiguate")
            findByParams(overloads, params ?: [], name, rel).remove()
        }
    }

    // ---- Trigger detection ----
    // Returns true if any member in the current version carries an annotation that requires merging.

    private static boolean hasTriggers(def cls) {
        cls.getMethods().any      { it.getAnnotationByName("OverwriteVersion").isPresent() || it.getAnnotationByName("ShadowVersion").isPresent() } ||
        cls.getFields().any       { it.getAnnotationByName("OverwriteVersion").isPresent() || it.getAnnotationByName("ShadowVersion").isPresent() } ||
        getTypeConstructors(cls).any { it.getAnnotationByName("OverwriteVersion").isPresent() || it.getAnnotationByName("ShadowVersion").isPresent() } ||
        getEnumEntries(cls).any   { it.getAnnotationByName("OverwriteVersion").isPresent() || it.getAnnotationByName("ShadowVersion").isPresent() } ||
        getAnnotationMembers(cls).any { it.getAnnotationByName("OverwriteVersion").isPresent() || it.getAnnotationByName("ShadowVersion").isPresent() } ||
        cls.getAnnotationByName("DeleteMethodsAndFields").isPresent() ||
        cls.getAnnotations().any  { !(it.getNameAsString() in PROCESSING_ANNOTATIONS) }
    }

    // ---- Non-annotated origin writing ----
    // Called when a file has no merge triggers. Writes member origins pointing to the current
    // version source so that navigation from patchedSrc resolves correctly.

    private static void writeNonAnnotatedOrigins(def cls, String rel, String srcRelRoot, BufferedWriter mapOut) {
        cls.getFields().each { FieldDeclaration f ->
            int line = f.getBegin().map { it.line }.orElse(0)
            mapOut.write("${rel}#${f.getVariables()[0].getNameAsString()}\t${srcRelRoot}/${rel}:${line}\n")
        }
        getEnumEntries(cls).each { EnumConstantDeclaration e ->
            int line = e.getBegin().map { it.line }.orElse(0)
            mapOut.write("${rel}#${e.getNameAsString()}\t${srcRelRoot}/${rel}:${line}\n")
        }
        getAnnotationMembers(cls).each { AnnotationMemberDeclaration m ->
            int line = m.getBegin().map { it.line }.orElse(0)
            mapOut.write("${rel}#${m.getNameAsString()}()\t${srcRelRoot}/${rel}:${line}\n")
        }
    }

    // ---- Descriptors and parsing ----

    private static String descriptor(MethodDeclaration m) {
        "${m.getNameAsString()}(${m.getParameters().collect { simpleTypeName(it.getTypeAsString()) }.join(",")})"
    }

    private static String constructorDescriptor(ConstructorDeclaration c) {
        "init(${c.getParameters().collect { simpleTypeName(it.getTypeAsString()) }.join(",")})"
    }

    private static List<String> extractDeleteDescriptors(def cls) {
        def ann = cls.getAnnotationByName("DeleteMethodsAndFields")
        if (!ann.isPresent()) return []
        List<String> out = []
        def expr = ann.get()
        if (expr.isSingleMemberAnnotationExpr()) {
            def val = expr.asSingleMemberAnnotationExpr().getMemberValue()
            if (val instanceof ArrayInitializerExpr)
                (val as ArrayInitializerExpr).getValues().each { out << it.asStringLiteralExpr().asString() }
            else
                out << val.asStringLiteralExpr().asString()
        }
        return out
    }

    private static List<String> parseParamTypes(String descriptor) {
        int open  = descriptor.indexOf("(")
        int close = descriptor.lastIndexOf(")")
        if (open < 0 || close <= open) return []
        String inner = descriptor.substring(open + 1, close).trim()
        if (inner.isEmpty()) return []
        return inner.split(",").collect { simpleTypeName(it.trim()) }
    }

    private static String simpleTypeName(String type) {
        String base = type.replaceAll(/\[\]/, "").replaceAll(/\.\.\.$/, "").trim()
        int dot = base.lastIndexOf(".")
        return dot >= 0 ? base.substring(dot + 1) : base
    }

    // ---- Type-aware member accessors ----
    // These return empty lists for types that do not support the member kind, making all
    // loops above safe to call unconditionally regardless of the underlying TypeDeclaration.

    // Returns constructors for class, enum, and record types; empty list for interfaces and annotation types.
    private static List getTypeConstructors(def cls) {
        cls.respondsTo('getConstructors') ? cls.getConstructors() : []
    }

    // Returns enum constant entries; empty list for non-enum types.
    private static List getEnumEntries(def cls) {
        cls instanceof EnumDeclaration ? cls.getEntries() : []
    }

    // Returns annotation member declarations; empty list for non-annotation types.
    private static List getAnnotationMembers(def cls) {
        cls.getMembers().findAll { it instanceof AnnotationMemberDeclaration }
    }

    // ---- Parameter matching and overload resolution ----
    // Works for both MethodDeclaration and ConstructorDeclaration via duck typing.

    private static boolean paramsMatch(def m, List<String> expected) {
        def params = m.getParameters()
        if (params.size() != expected.size()) return false
        for (int i = 0; i < params.size(); i++) {
            if (simpleTypeName(params[i].getTypeAsString()) != expected[i]) return false
        }
        return true
    }

    private static def findByParams(List overloads, List<String> params, String name, String rel) {
        if (params.isEmpty() && overloads.size() == 1) return overloads[0]
        def match = overloads.find { paramsMatch(it, params) }
        if (match == null) throw new GradleException("No overload '${name}(${params.join(", ")})' found in base for ${rel}")
        return match
    }
}
