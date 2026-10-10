package com.finoniq.docql;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frozen public surface (D-01..D-06, D-21): the public top-level types of
 * {@code com.finoniq.docql} in {@code target/classes}, their nested public
 * builders, every type's public declared methods and the absence of public
 * constructors — pinned so nothing drifts before the owner surface review.
 * The pinned {@code DocQL} list carries the two {@code queryDocumentAsync}
 * overloads (13-04, D-05).
 */
class SurfaceTest {

    private static final String PACKAGE = "com.finoniq.docql";

    @Test
    void publicSurface() throws Exception {
        Path dir = Path.of("target", "classes", "com", "finoniq", "docql");
        assertTrue(Files.isDirectory(dir), "target/classes missing; run from the repo root");

        Set<String> topLevel = new TreeSet<>();
        Set<String> nested = new TreeSet<>();
        Map<String, Class<?>> types = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".class")) {
                    continue;
                }
                String simple = name.substring(0, name.length() - ".class".length());
                if (simple.equals("package-info") || simple.matches(".*\\$\\d+$")) {
                    continue; // package metadata and anonymous classes are not surface
                }
                Class<?> cls = Class.forName(PACKAGE + "." + simple);
                if (!Modifier.isPublic(cls.getModifiers())) {
                    continue;
                }
                if (simple.contains("$")) {
                    nested.add(simple);
                } else {
                    topLevel.add(simple);
                }
                types.put(simple, cls);
            }
        }

        assertEquals(Set.of("DocQL", "DocQLConnectionException", "DocQLException", "DocFile",
                "DocQLTimeoutException", "Instruction", "Mode", "QueryOptions", "QueryResult", "Reasons"),
                topLevel, "the public top-level types drifted");
        assertEquals(Set.of("DocQL$Builder", "QueryOptions$Builder"), nested,
                "the public nested types drifted");

        Map<String, Set<String>> wantMethods = new TreeMap<>();
        wantMethods.put("DocQL", Set.of("builder()", "queryDocument(DocFile,Instruction)",
                "queryDocument(DocFile,Instruction,QueryOptions)", "queryDocumentAsync(DocFile,Instruction)",
                "queryDocumentAsync(DocFile,Instruction,QueryOptions)", "toString()"));
        wantMethods.put("DocQL$Builder", Set.of("apiKey(String)", "apiUrl(String)", "timeout(Duration)",
                "httpClient(HttpClient)", "build()"));
        wantMethods.put("DocFile", Set.of("bytes(byte[],String)", "path(Path)", "stream(InputStream,long,String)"));
        wantMethods.put("Instruction", Set.of("query(String)", "prompt(String)"));
        wantMethods.put("Mode", Set.of("of(String)", "value()", "equals(Object)", "hashCode()", "toString()"));
        wantMethods.put("QueryOptions", Set.of("builder()"));
        wantMethods.put("QueryOptions$Builder", Set.of("mode(Mode)", "filename(String)", "build()"));
        wantMethods.put("Reasons", Set.of());
        wantMethods.put("QueryResult", Set.of("data()", "requestId()", "pageCount()", "pagesOcr()", "metadata()"));
        wantMethods.put("DocQLException", Set.of("status()", "reason()", "requestId()", "retryAfter()",
                "retryable()", "serverMessage()", "body()", "getMessage()"));
        wantMethods.put("DocQLConnectionException", Set.of());
        wantMethods.put("DocQLTimeoutException", Set.of());

        for (Map.Entry<String, Set<String>> entry : wantMethods.entrySet()) {
            Class<?> cls = types.get(entry.getKey());
            assertNotNull(cls, entry.getKey() + " must be public surface");
            Set<String> got = new TreeSet<>();
            for (Method method : cls.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge() || !Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                if (cls.isRecord() && Set.of("equals", "hashCode", "toString").contains(method.getName())) {
                    continue; // record-generated, not a declared surface choice
                }
                StringBuilder signature = new StringBuilder(method.getName()).append('(');
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) {
                        signature.append(',');
                    }
                    signature.append(params[i].getSimpleName());
                }
                got.add(signature.append(')').toString());
            }
            assertEquals(entry.getValue(), got, entry.getKey() + " public method drift");
        }

        for (Class<?> cls : types.values()) {
            if (cls.isRecord()) {
                continue; // the record's canonical constructor is the API
            }
            assertEquals(0, cls.getConstructors().length,
                    cls.getSimpleName() + " must have no public constructor");
        }
    }
}
