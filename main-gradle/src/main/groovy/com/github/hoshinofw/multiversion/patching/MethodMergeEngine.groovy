package com.github.hoshinofw.multiversion.patching

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.printer.DefaultPrettyPrinter
import org.gradle.api.GradleException

class MethodMergeEngine {

    private static final JavaParser PARSER = new JavaParser(
        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

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

            // Quick text scan: skip entirely if no base and no DeleteClass annotation
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
        ClassOrInterfaceDeclaration currentCls = currentCU.findFirst(ClassOrInterfaceDeclaration.class).orElse(null)
        if (currentCls == null) return

        // @DeleteClass: remove this class from patchedSrc entirely
        if (currentCls.getAnnotationByName("DeleteClass").isPresent()) {
            outFile.delete()
            return
        }

        if (!baseFile.exists()) return

        boolean hasOverwrite = currentCls.getMethods().any { it.getAnnotationByName("OverwriteVersion").isPresent() }
        boolean hasDelete    = currentCls.getAnnotationByName("DeleteMethodsAndFields").isPresent()
        boolean hasShadow    = currentCls.getMethods().any { it.getAnnotationByName("ShadowVersion").isPresent() } ||
                               currentCls.getFields().any  { it.getAnnotationByName("ShadowVersion").isPresent() }
        if (!hasOverwrite && !hasDelete && !hasShadow) return

        CompilationUnit baseCU = PARSER.parse(baseFile).getResult().get()
        ClassOrInterfaceDeclaration baseCls = baseCU.findFirst(ClassOrInterfaceDeclaration.class).orElse(null)
        if (baseCls == null) return

        // --- @Delete ---
        if (hasDelete) {
            for (String desc : extractDeleteMethodsAndFieldsDescriptors(currentCls)) {
                String name = desc.contains("(") ? desc.substring(0, desc.indexOf("(")) : desc
                boolean isField = !desc.contains("(") &&
                        baseCls.getFields().any { fd -> fd.getVariables().any { it.getNameAsString() == name } }

                if (isField) {
                    FieldDeclaration f = baseCls.getFields().find { fd -> fd.getVariables().any { it.getNameAsString() == name } }
                    if (f == null) throw new GradleException("@Delete: field '${name}' not found in base for ${rel}")
                    f.remove()
                } else {
                    List<String> params = desc.contains("(") ? parseParamTypes(desc) : null
                    List<MethodDeclaration> overloads = baseCls.getMethods().findAll { it.getNameAsString() == name }
                    if (overloads.isEmpty()) throw new GradleException("@Delete: method '${name}' not found in base for ${rel}")
                    if (params == null && overloads.size() > 1)
                        throw new GradleException("@Delete: '${name}' in ${rel} has ${overloads.size()} overloads — add parameter types to disambiguate")
                    findByParams(overloads, params ?: [], name, rel).remove()
                }
            }
        }

        // --- @OverwriteVersion and implicit @Add ---
        Map<String, Integer> currentOrigins = [:]

        currentCls.getMethods().each { MethodDeclaration method ->
            String desc = descriptor(method)
            List<String> mParams = method.getParameters().collect { simpleTypeName(it.getTypeAsString()) }
            boolean inBase = baseCls.getMethods().any { it.getNameAsString() == method.getNameAsString() && paramsMatch(it, mParams) }

            if (method.getAnnotationByName("ShadowVersion").isPresent()) {
                if (!inBase) throw new GradleException("@ShadowVersion: '${desc}' not found in base for ${rel}")
                return // leave base's copy intact, skip this declaration entirely
            }

            if (method.isAbstract() && !inBase && !baseCls.isAbstract()) {
                throw new GradleException("Abstract method '${desc}' in ${rel} cannot be added to a non-abstract base class — annotate with @ShadowVersion if referencing a base method")
            }

            if (method.getAnnotationByName("OverwriteVersion").isPresent()) {
                if (!inBase) throw new GradleException("@OverwriteVersion: '${desc}' not found in base for ${rel}")
                MethodDeclaration target = findByParams(
                        baseCls.getMethods().findAll { it.getNameAsString() == method.getNameAsString() },
                        mParams, method.getNameAsString(), rel)
                method.getAnnotationByName("OverwriteVersion").ifPresent { it.remove() }
                baseCls.getMembers().set(baseCls.getMembers().indexOf(target), method.clone())
                currentOrigins[desc] = method.getBegin().map { it.line }.orElse(0)
            } else if (inBase) {
                throw new GradleException("Method '${desc}' exists in both versions of ${rel} without @OverwriteVersion or @ShadowVersion — annotate it explicitly")
            } else {
                baseCls.addMember(method.clone())
                currentOrigins[desc] = method.getBegin().map { it.line }.orElse(0)
            }
        }

        currentCls.getFields().each { FieldDeclaration field ->
            String fName = field.getVariables()[0].getNameAsString()
            boolean inBase = baseCls.getFields().any { bf -> bf.getVariables().any { it.getNameAsString() == fName } }

            if (field.getAnnotationByName("ShadowVersion").isPresent()) {
                if (!inBase) throw new GradleException("@ShadowVersion: field '${fName}' not found in base for ${rel}")
                return // leave base's copy intact
            }

            if (field.getAnnotationByName("OverwriteVersion").isPresent()) {
                if (!inBase) throw new GradleException("@OverwriteVersion on field '${fName}' not found in base for ${rel}")
                FieldDeclaration target = baseCls.getFields().find { bf -> bf.getVariables().any { it.getNameAsString() == fName } }
                field.getAnnotationByName("OverwriteVersion").ifPresent { it.remove() }
                baseCls.getMembers().set(baseCls.getMembers().indexOf(target), field.clone())
            } else if (!inBase) {
                baseCls.addMember(field.clone())
            } else {
                throw new GradleException("Field '${fName}' exists in both versions of ${rel} without @OverwriteVersion or @ShadowVersion")
            }
        }

        // Merge imports
        currentCU.getImports().each { imp ->
            if (!baseCU.getImports().any { it.getNameAsString() == imp.getNameAsString() && it.isStatic() == imp.isStatic() })
                baseCU.addImport(imp.getNameAsString(), imp.isStatic(), imp.isAsterisk())
        }

        outFile.write(new DefaultPrettyPrinter().print(baseCU), "UTF-8")

        // Write method-level origin entries
        if (mapOut != null) {
            baseCls.getMethods().each { MethodDeclaration m ->
                String desc = descriptor(m)
                if (currentOrigins.containsKey(desc)) {
                    mapOut.write("${rel}#${desc}\t${currentSrcRelRoot}/${rel}:${currentOrigins[desc]}\n")
                } else {
                    int line = m.getBegin().map { it.line }.orElse(0)
                    mapOut.write("${rel}#${desc}\t${baseRelRoot}/${rel}:${line}\n")
                }
            }
        }
    }

    private static String descriptor(MethodDeclaration m) {
        "${m.getNameAsString()}(${m.getParameters().collect { simpleTypeName(it.getTypeAsString()) }.join(",")})"
    }

    private static List<String> extractDeleteMethodsAndFieldsDescriptors(ClassOrInterfaceDeclaration cls) {
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
        int open = descriptor.indexOf("(")
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

    private static boolean paramsMatch(MethodDeclaration m, List<String> expected) {
        def params = m.getParameters()
        if (params.size() != expected.size()) return false
        for (int i = 0; i < params.size(); i++) {
            if (simpleTypeName(params[i].getTypeAsString()) != expected[i]) return false
        }
        return true
    }

    private static MethodDeclaration findByParams(List<MethodDeclaration> overloads, List<String> params, String name, String rel) {
        if (params.isEmpty() && overloads.size() == 1) return overloads[0]
        MethodDeclaration match = overloads.find { paramsMatch(it, params) }
        if (match == null) throw new GradleException("No overload '${name}(${params.join(", ")})' found in base for ${rel}")
        return match
    }
}