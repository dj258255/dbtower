package io.dbtower.finops.internal;

/** FinOps 후보 근거 문구용 소소한 포맷 도우미(사람이 읽는 바이트 표기). */
final class FinOpsFormat {

    private FinOpsFormat() {
    }

    /** 바이트를 사람이 읽는 단위로(예: 1536 → "1.5 KB"). null이면 "?". */
    static String bytes(Long value) {
        if (value == null) {
            return "?";
        }
        double v = value;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return u == 0 ? (long) v + " " + units[u]
                : String.format("%.1f %s", v, units[u]);
    }
}
