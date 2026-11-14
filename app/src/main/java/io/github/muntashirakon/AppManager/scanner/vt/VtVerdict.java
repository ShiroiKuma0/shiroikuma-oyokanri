// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.scanner.vt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fork (白い熊, APK-vetting chain, item 4): interprets a stored VirusTotal file-report JSON into a
 * single verdict for 白い熊, who installs cracked/modded (always re-signed) games and wants the
 * noise of riskware/patcher labels told apart from a real threat.
 *
 * <p>It is computed entirely from the raw JSON ({@code vt_file.latest_report_json}), so the
 * thresholds can be re-tuned and a file re-judged without a rescan. <b>Every threshold and word
 * list lives here, in one place</b> — this is a synthesis, not a published standard, and 白い熊 will
 * tune it on real files.
 *
 * <p>It deliberately does not use {@link VtAvEngineStats#getTotal()} (which omits
 * timeouts/failures/unsupported) or {@code getDetected()} (malicious only); a report with zero
 * engine results is {@link Level#NOT_ANALYSED}, never clean.
 */
public final class VtVerdict {
    public enum Level {
        /** No engine returned a verdict — the file is not analysed, not clean. */
        NOT_ANALYSED,
        /** Fewer than {@link #MIN_ENGINES_FOR_VERDICT} engines returned a verdict — offer a rescan. */
        INCOMPLETE,
        /** No votes, and the analysis is recent enough to be provisional. */
        CLEAN_PROVISIONAL,
        /** No votes. */
        CLEAN,
        /** Detections exist but every vote is noise — the expected result for a crack. */
        RISKWARE,
        /** A weak or lone non-noise signal. */
        SUSPICIOUS,
        /** A strong or agreeing non-noise signal. */
        MALICIOUS
    }

    /** How an engine's detection label reads once the noise of a crack is stripped out. */
    public enum LabelClass {
        /** Riskware/patcher/PUA/adware/generic — expected for a re-signed crack. */
        NOISE,
        /** Banker, spy, stealer, RAT/backdoor, ransom/locker, or a named family. */
        SEVERE,
        /** Dropper/downloader or SMS sender — a weaker severe class. */
        DROPPER_SMS,
        /** A non-noise detection that names no clear class. */
        OTHER
    }

    // ── Tunable thresholds (白い熊 tunes these on real files) ────────────────────────────────────
    private static final int MIN_ENGINES_FOR_VERDICT = 45;
    private static final int MALICIOUS_VOTE_FAMILIES = 4;
    private static final int MALICIOUS_STRONG_SEVERE_FAMILIES = 2;
    private static final long PROVISIONAL_WINDOW_MS = 72 * 60 * 60_000L;

    public final Level level;
    public final int totalEngines;
    public final int engineVerdictCount;
    public final int maliciousCount;
    public final int suspiciousCount;
    public final int nonNoiseVoteFamilies;
    public final int severeVoteFamilies;
    public final int strongSevereFamilies;
    public final int noiseDetectionCount;
    public final boolean sandboxMalicious;
    public final boolean familyAgreement;
    @Nullable
    public final String suggestedLabel;
    public final long firstSubmissionDate;
    public final long lastAnalysisDate;
    @NonNull
    public final List<String> reasons;

    private VtVerdict(@NonNull Builder b) {
        this.level = b.level;
        this.totalEngines = b.totalEngines;
        this.engineVerdictCount = b.engineVerdictCount;
        this.maliciousCount = b.maliciousCount;
        this.suspiciousCount = b.suspiciousCount;
        this.nonNoiseVoteFamilies = b.voteFamilies.size();
        this.severeVoteFamilies = b.severeFamilies.size();
        this.strongSevereFamilies = b.strongSevereFamilies.size();
        this.noiseDetectionCount = b.noiseDetectionCount;
        this.sandboxMalicious = b.sandboxMalicious;
        this.familyAgreement = b.familyAgreement;
        this.suggestedLabel = b.suggestedLabel;
        this.firstSubmissionDate = b.firstSubmissionDate;
        this.lastAnalysisDate = b.lastAnalysisDate;
        this.reasons = b.reasons;
    }

    public boolean isProvisional() {
        return level == Level.CLEAN_PROVISIONAL;
    }

    public boolean offersRescan() {
        return level == Level.INCOMPLETE || level == Level.NOT_ANALYSED;
    }

    @NonNull
    public static VtVerdict fromJson(@Nullable String rawJson) {
        Builder b = new Builder();
        if (rawJson == null) {
            b.level = Level.NOT_ANALYSED;
            return new VtVerdict(b);
        }
        try {
            JSONObject attrs = new JSONObject(rawJson).getJSONObject("data").getJSONObject("attributes");
            b.firstSubmissionDate = attrs.optLong("first_submission_date") * 1_000L;
            b.lastAnalysisDate = attrs.optLong("last_analysis_date") * 1_000L;
            parseResults(attrs.optJSONObject("last_analysis_results"), b);
            parsePopularThreat(attrs.optJSONObject("popular_threat_classification"), b);
            parseSandbox(attrs.optJSONObject("sandbox_verdicts"), b);
        } catch (Throwable e) {
            e.printStackTrace();
            b.level = Level.NOT_ANALYSED;
            return new VtVerdict(b);
        }
        decide(b);
        return new VtVerdict(b);
    }

    private static void parseResults(@Nullable JSONObject results, @NonNull Builder b) {
        if (results == null) return;
        for (java.util.Iterator<String> it = results.keys(); it.hasNext(); ) {
            JSONObject r = results.optJSONObject(it.next());
            if (r == null) continue;
            b.totalEngines++;
            String category = r.optString("category", "");
            String engine = r.optString("engine_name", "");
            String label = r.optString("result", null);
            boolean returnedVerdict = "harmless".equals(category) || "undetected".equals(category)
                    || "suspicious".equals(category) || "malicious".equals(category);
            if (returnedVerdict) b.engineVerdictCount++;
            boolean detection = "malicious".equals(category) || "suspicious".equals(category);
            if (!detection) continue;
            if ("malicious".equals(category)) b.maliciousCount++;
            else b.suspiciousCount++;
            LabelClass labelClass = classify(label);
            if (labelClass == LabelClass.NOISE) {
                b.noiseDetectionCount++;
                continue;
            }
            // A non-noise vote — one per engine family.
            String family = familyOf(engine);
            b.voteFamilies.add(family);
            boolean severe = labelClass == LabelClass.SEVERE || labelClass == LabelClass.DROPPER_SMS;
            if (severe) b.severeFamilies.add(family);
            if (severe && isStrongEngine(engine)) b.strongSevereFamilies.add(family);
        }
    }

    private static void parsePopularThreat(@Nullable JSONObject ptc, @NonNull Builder b) {
        if (ptc == null) return;
        b.suggestedLabel = ptc.optString("suggested_threat_label", null);
        JSONArray names = ptc.optJSONArray("popular_threat_name");
        if (names != null) {
            for (int i = 0; i < names.length(); ++i) {
                JSONObject n = names.optJSONObject(i);
                if (n != null && n.optInt("count") >= 2) {
                    b.familyAgreement = true;
                    break;
                }
            }
        }
    }

    private static void parseSandbox(@Nullable JSONObject sandbox, @NonNull Builder b) {
        if (sandbox == null) return;
        for (java.util.Iterator<String> it = sandbox.keys(); it.hasNext(); ) {
            JSONObject v = sandbox.optJSONObject(it.next());
            if (v != null && "malicious".equals(v.optString("category", ""))) {
                b.sandboxMalicious = true;
                return;
            }
        }
    }

    private static void decide(@NonNull Builder b) {
        int votes = b.voteFamilies.size();
        boolean severePresent = !b.severeFamilies.isEmpty();
        if (votes >= MALICIOUS_VOTE_FAMILIES) {
            b.level = Level.MALICIOUS;
            b.reasons.add(votes + " independent engines flagged it");
        } else if (b.strongSevereFamilies.size() >= MALICIOUS_STRONG_SEVERE_FAMILIES) {
            b.level = Level.MALICIOUS;
            b.reasons.add(b.strongSevereFamilies.size() + " major engines agree on a severe class");
        } else if (b.familyAgreement && severePresent) {
            b.level = Level.MALICIOUS;
            b.reasons.add("engines agree on a named threat family");
        } else if (votes >= 1 || b.sandboxMalicious) {
            b.level = Level.SUSPICIOUS;
            if (b.sandboxMalicious) b.reasons.add("a sandbox run was flagged malicious");
            if (b.strongSevereFamilies.size() == 1) b.reasons.add("one major engine named a severe class");
            if (votes >= 1) b.reasons.add(votes + (votes == 1 ? " engine flagged it (no agreement)"
                    : " engines flagged it (no agreement)"));
        } else if (b.engineVerdictCount == 0) {
            b.level = Level.NOT_ANALYSED;
            b.reasons.add("no engine has returned a verdict yet");
        } else if (b.engineVerdictCount < MIN_ENGINES_FOR_VERDICT) {
            b.level = Level.INCOMPLETE;
            b.reasons.add("only " + b.engineVerdictCount + " engines returned a verdict");
        } else if (b.noiseDetectionCount > 0) {
            b.level = Level.RISKWARE;
            b.reasons.add(b.noiseDetectionCount + " engines report riskware/patcher only");
        } else if (b.firstSubmissionDate > 0
                && System.currentTimeMillis() - b.firstSubmissionDate < PROVISIONAL_WINDOW_MS) {
            b.level = Level.CLEAN_PROVISIONAL;
            b.reasons.add("first seen on VirusTotal in the last 72 hours");
        } else {
            b.level = Level.CLEAN;
            b.reasons.add(b.engineVerdictCount + " engines returned a verdict, none flagged it");
        }
    }

    // ── Label classification (白い熊 tunes the word lists) ───────────────────────────────────────

    @NonNull
    public static LabelClass classify(@Nullable String label) {
        if (label == null) return LabelClass.NOISE;
        String l = label.toLowerCase(Locale.ROOT).trim();
        if (l.isEmpty()) return LabelClass.NOISE;
        if (isNoise(l)) return LabelClass.NOISE;
        if (isSevere(l)) return LabelClass.SEVERE;
        if (isDropperOrSms(l)) return LabelClass.DROPPER_SMS;
        return LabelClass.OTHER;
    }

    private static boolean isNoise(@NonNull String l) {
        return l.contains("not-a-virus")
                || l.contains("risktool")
                || l.contains("riskware")
                || l.contains("pua")
                || l.contains("pup")
                || l.contains("potentially unsafe")
                || l.contains("potentially unwanted")
                || l.contains("unwanted")
                || l.contains("hacktool")
                || l.contains("tool.")
                || l.contains("luckypatcher")
                || l.contains("packed")
                || l.contains("adware")
                || l.contains("adlibrary")
                || l.contains("artemis")
                || l.contains("evo-gen")
                || l.contains("generisk")
                || l.contains("malicious (score")
                || l.contains("trojan.gen")
                || l.contains("gen:variant")
                || l.contains("generickd");
    }

    private static boolean isSevere(@NonNull String l) {
        return l.contains("bank")            // banker, bankbot
                || l.contains("spy")         // spyware, spynote
                || l.contains("steal")       // stealer
                || hasWord(l, "rat")
                || l.contains("backdoor")
                || l.contains("bdoor")
                || l.contains("ransom")
                || l.contains("locker")
                || l.contains("keylog")
                || isNamedFamily(l);
    }

    private static boolean isDropperOrSms(@NonNull String l) {
        return l.contains("drop")            // dropper
                || l.contains("download")    // downloader
                || l.contains("sms");        // SMS sender
    }

    private static final String[] NAMED_FAMILIES = {
            "cerberus", "anubis", "joker", "hydra", "teabot", "flubot", "sharkbot", "alien",
            "ginp", "medusa", "octo", "hook", "ermac", "sova", "xenomorph", "brata", "gustuff",
            "coper", "exobot", "mysterybot", "ginp", "spynote", "hiddad", "triada", "necro",
            "harly", "fleckpe", "goldoson", "vultur", "spinok"
    };

    private static boolean isNamedFamily(@NonNull String l) {
        for (String family : NAMED_FAMILIES) {
            if (l.contains(family)) return true;
        }
        return false;
    }

    private static boolean hasWord(@NonNull String l, @NonNull String word) {
        int from = 0;
        while (true) {
            int i = l.indexOf(word, from);
            if (i < 0) return false;
            boolean leftOk = i == 0 || !Character.isLetterOrDigit(l.charAt(i - 1));
            int end = i + word.length();
            boolean rightOk = end >= l.length() || !Character.isLetterOrDigit(l.charAt(end));
            if (leftOk && rightOk) return true;
            from = i + 1;
        }
    }

    // ── Engine identity (白い熊 tunes these lists) ───────────────────────────────────────────────

    private static boolean isStrongEngine(@NonNull String engine) {
        String e = engine.toLowerCase(Locale.ROOT);
        return e.contains("kaspersky")
                || e.contains("eset")
                || e.contains("bitdefender")
                || e.contains("avast")
                || e.contains("avg")
                || e.contains("avira")
                || e.contains("sophos")
                || e.contains("drweb")
                || e.contains("fortinet")
                || e.contains("symantec mobile insight")
                || e.equals("google");
    }

    /** One vote per engine family — clones of one engine are not independent evidence. */
    @NonNull
    private static String familyOf(@NonNull String engine) {
        String e = engine.toLowerCase(Locale.ROOT);
        if (e.contains("avast") || e.contains("avg")) return "avast_avg";
        if (e.contains("mcafee") || e.contains("skyhigh")) return "mcafee_skyhigh";
        if (e.startsWith("k7")) return "k7";
        // BitDefender and its common licensees / engine clones.
        if (e.contains("bitdefender") || e.contains("emsisoft") || e.contains("f-secure")
                || e.contains("fsecure") || e.contains("gdata") || e.contains("g-data")
                || e.contains("arcabit") || e.contains("ad-aware") || e.contains("adaware")
                || e.contains("escan") || e.contains("microworld") || e.contains("vipre")
                || e.contains("nextron") || e.contains("totaldefense")) {
            return "bitdefender";
        }
        return e;
    }

    private static final class Builder {
        Level level = Level.NOT_ANALYSED;
        int totalEngines;
        int engineVerdictCount;
        int maliciousCount;
        int suspiciousCount;
        int noiseDetectionCount;
        boolean sandboxMalicious;
        boolean familyAgreement;
        @Nullable
        String suggestedLabel;
        long firstSubmissionDate;
        long lastAnalysisDate;
        final Set<String> voteFamilies = new HashSet<>();
        final Set<String> severeFamilies = new HashSet<>();
        final Set<String> strongSevereFamilies = new HashSet<>();
        final List<String> reasons = new ArrayList<>();
    }
}
