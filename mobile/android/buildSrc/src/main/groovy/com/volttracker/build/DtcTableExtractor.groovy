package com.volttracker.build

/**
 * Builds the native screens' trouble-code table from the classic dashboard's two DTC sources, so
 * the Compose Health screen names and grades codes exactly as the classic dashboard does without a
 * second hand-kept copy: `dtc-lookup.ts` (code → description) and `dtc-causes.ts` (code →
 * severity and category). One tab-separated line per code: `code  description  severity  category`,
 * with an empty field where a source has nothing for that code.
 */
final class DtcTableExtractor {
    private DtcTableExtractor() {}

    private static final def CODE = /[PBCU][0-9A-F]{4}/

    static String extract(File lookupTs, File causesTs) {
        def descriptions = new TreeMap<String, String>()
        boolean inTable = false
        lookupTs.eachLine("UTF-8") { line ->
            if (line.contains("const VOLT_DTC")) {
                inTable = true
                return
            }
            if (!inTable) return
            if (line ==~ /^\s*};\s*$/) {
                inTable = false
                return
            }
            def match = (line =~ /^\s*"?(${CODE})"?:\s*"((?:[^"\\]|\\.)*)",?\s*$/)
            if (match.find()) descriptions[match.group(1)] = unescape(match.group(2))
        }
        def severities = new HashMap<String, String>()
        def categories = new HashMap<String, String>()
        String current = null
        causesTs.eachLine("UTF-8") { line ->
            def open = (line =~ /^\s*"(${CODE})":\s*\{\s*$/)
            if (open.find()) {
                current = open.group(1)
                return
            }
            if (current == null) return
            def severity = (line =~ /^\s*"severity":\s*"(info|warning|critical)"/)
            if (severity.find()) severities[current] = severity.group(1)
            def category = (line =~ /^\s*"category":\s*"([^"\\]*)"/)
            if (category.find()) categories[current] = category.group(1)
        }
        def codes = new TreeSet<String>(descriptions.keySet())
        codes.addAll(severities.keySet())
        if (descriptions.size() < MIN_CODES) {
            throw new IllegalStateException("dtc-lookup.ts yielded only ${descriptions.size()} codes; did its layout change?")
        }
        def out = new StringBuilder()
        codes.each { code ->
            out.append(code).append('\t')
                .append(clean(descriptions[code])).append('\t')
                .append(severities[code] ?: "").append('\t')
                .append(clean(categories[code])).append('\n')
        }
        return out.toString()
    }

    /** A sanity floor: the lookup lists thousands of codes, so far fewer means the parse broke. */
    private static final int MIN_CODES = 1000

    private static String unescape(String text) {
        return text.replace("\\'", "'").replace('\\"', '"').replace("\\\\", "\\")
    }

    private static String clean(String text) {
        return (text ?: "").replaceAll(/[\t\r\n]+/, " ").trim()
    }
}
