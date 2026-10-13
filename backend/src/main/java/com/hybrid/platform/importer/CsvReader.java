package com.hybrid.platform.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * Spreadsheet text (CSV from Excel or Google Sheets): quoted fields with commas, "" inside quotes and line breaks
 * inside quotes. The separator (comma, semicolon or tab) is detected from the header line.
 */
final class CsvReader {

    private CsvReader() {
    }

    static List<List<String>> parse(String text) {
        String s = text == null ? "" : text;
        if (s.startsWith("﻿")) s = s.substring(1);
        char sep = separator(s);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.toString().isBlank()) {
                cell.setLength(0);
                quoted = true;
            } else if (c == sep) {
                row.add(cell.toString().strip());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().strip());
                cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        row.add(cell.toString().strip());
        if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
        return rows;
    }

    private static char separator(String s) {
        int end = s.indexOf('\n');
        String header = end < 0 ? s : s.substring(0, end);
        long commas = header.chars().filter(ch -> ch == ',').count();
        long semicolons = header.chars().filter(ch -> ch == ';').count();
        long tabs = header.chars().filter(ch -> ch == '\t').count();
        if (tabs > commas && tabs >= semicolons) return '\t';
        return semicolons > commas ? ';' : ',';
    }
}
