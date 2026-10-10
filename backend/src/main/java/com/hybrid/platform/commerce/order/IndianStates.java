package com.hybrid.platform.commerce.order;

import java.util.LinkedHashMap;
import java.util.Map;

/** GST state / union territory codes (first two digits of a GSTIN). */
public final class IndianStates {

    public static final Map<String, String> BY_CODE;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("35", "Andaman and Nicobar Islands");
        m.put("37", "Andhra Pradesh");
        m.put("12", "Arunachal Pradesh");
        m.put("18", "Assam");
        m.put("10", "Bihar");
        m.put("04", "Chandigarh");
        m.put("22", "Chhattisgarh");
        m.put("26", "Dadra and Nagar Haveli and Daman and Diu");
        m.put("07", "Delhi");
        m.put("30", "Goa");
        m.put("24", "Gujarat");
        m.put("06", "Haryana");
        m.put("02", "Himachal Pradesh");
        m.put("01", "Jammu and Kashmir");
        m.put("20", "Jharkhand");
        m.put("29", "Karnataka");
        m.put("32", "Kerala");
        m.put("38", "Ladakh");
        m.put("31", "Lakshadweep");
        m.put("23", "Madhya Pradesh");
        m.put("27", "Maharashtra");
        m.put("14", "Manipur");
        m.put("17", "Meghalaya");
        m.put("15", "Mizoram");
        m.put("13", "Nagaland");
        m.put("21", "Odisha");
        m.put("34", "Puducherry");
        m.put("03", "Punjab");
        m.put("08", "Rajasthan");
        m.put("11", "Sikkim");
        m.put("33", "Tamil Nadu");
        m.put("36", "Telangana");
        m.put("16", "Tripura");
        m.put("09", "Uttar Pradesh");
        m.put("05", "Uttarakhand");
        m.put("19", "West Bengal");
        BY_CODE = java.util.Collections.unmodifiableMap(m);
    }

    private IndianStates() {
    }

    public static boolean isValid(String code) {
        return code != null && BY_CODE.containsKey(code);
    }

    public static String name(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /** Matches a state name (case-insensitive, ignoring "&" vs "and") to its GST code, or null. */
    public static String codeForName(String name) {
        if (name == null || name.isBlank()) return null;
        String wanted = normalise(name);
        for (Map.Entry<String, String> e : BY_CODE.entrySet()) {
            if (normalise(e.getValue()).equals(wanted)) return e.getKey();
        }
        return null;
    }

    private static String normalise(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replace("&", "and").replaceAll("[^a-z]", "");
    }
}
