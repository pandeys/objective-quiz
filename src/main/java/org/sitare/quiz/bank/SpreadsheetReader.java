package org.sitare.quiz.bank;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Reads the first sheet of an Excel file, or a CSV file, into rows of text cells. */
public final class SpreadsheetReader {

    private SpreadsheetReader() {
    }

    public static List<List<String>> read(String filename, InputStream in) throws IOException {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return readExcel(in);
        }
        return readCsv(in);
    }

    static List<List<String>> readCsv(InputStream in) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            CSVFormat format = CSVFormat.DEFAULT.builder().setTrim(true).setIgnoreEmptyLines(true).build();
            for (CSVRecord record : format.parse(reader)) {
                List<String> row = new ArrayList<>();
                record.forEach(row::add);
                rows.add(row);
            }
        }
        // Strip a UTF-8 byte-order mark that Excel adds to CSV files.
        if (!rows.isEmpty() && !rows.get(0).isEmpty() && rows.get(0).get(0).startsWith("﻿")) {
            rows.get(0).set(0, rows.get(0).get(0).substring(1));
        }
        return rows;
    }

    static List<List<String>> readExcel(InputStream in) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row r : sheet) {
                List<String> row = new ArrayList<>();
                int last = Math.max(r.getLastCellNum(), 0);
                for (int c = 0; c < last; c++) {
                    Cell cell = r.getCell(c);
                    row.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                rows.add(row);
            }
        }
        return rows;
    }
}
