package com.github.hoshinofw.multiversion.util

import groovy.io.FileType

class PatchingUtil {

    static LinkedHashSet<String> relFileSet(File root) {
        def out = new LinkedHashSet<String>()
        if (!root.exists()) return out
        root.eachFileRecurse(FileType.FILES) { f ->
            def rel = root.toPath().relativize(f.toPath()).toString().replace('\\','/')
            out.add(rel)
        }
        return out
    }

}
