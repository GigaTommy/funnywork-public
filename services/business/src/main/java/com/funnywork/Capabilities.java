package com.funnywork;

import java.util.Set;

/** Registration is not execution authorization; write capabilities require explicit scope. */
public final class Capabilities {
    private Capabilities() {}
    public static final String ANNUAL = "report.annual.v1";
    public static final String REPOSITORY = "repository.maintain.v1";
    public static final String COMPUTER = "computer.write.v1";
    public static final String PATTERN = "text\\.stats\\.v1|model\\.read\\.v1|connector\\.write\\.v1|browser\\.write\\.v1|computer\\.write\\.v1|repository\\.maintain\\.v1|report\\.annual\\.v1";
    public static final Set<String> SUPPORTED = Set.of(ANNUAL,"text.stats.v1","model.read.v1","connector.write.v1","browser.write.v1",COMPUTER,REPOSITORY);
}
