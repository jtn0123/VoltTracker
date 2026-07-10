package com.volttracker.build

final class DashboardHtmlAssembler {
    private DashboardHtmlAssembler() {}

    static String assemble(File srcDir) {
        def output = new StringBuilder()
        new File(srcDir, "index.template.html").eachLine("UTF-8") { line ->
            def match = (line =~ /^\s*<!-- include: (\S+) -->\s*$/)
            if (match.find()) {
                output.append(new File(srcDir, match.group(1)).getText("UTF-8").replaceAll(/\s+$/, ""))
            } else {
                output.append(line)
            }
            output.append("\n")
        }
        return output.toString().replaceAll(/\n+$/, "") + "\n"
    }
}
