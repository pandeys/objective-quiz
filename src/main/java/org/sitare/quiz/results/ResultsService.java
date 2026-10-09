package org.sitare.quiz.results;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Score sheet for faculty and its Excel export (one row per student: roll number, name, total marks). */
@Service
public class ResultsService {

    public record ResultRow(String rollNo, String name, String status, BigDecimal score, BigDecimal maxScore,
                            int secondLogins) {
        public String statusLabel() {
            return label(status);
        }
    }

    private final JdbcClient jdbc;

    public ResultsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Everyone on the roster; students who never started show as ABSENT. */
    public List<ResultRow> results(long quizId) {
        return jdbc.sql("""
                        SELECT s.roll_no, s.name,
                               COALESCE(a.status, 'ABSENT') AS status,
                               a.score, a.max_score,
                               COALESCE(a.multi_session_count, 0) AS second_logins
                          FROM quiz_roster r
                          JOIN student s ON s.id = r.student_id
                          LEFT JOIN attempt a ON a.quiz_id = r.quiz_id AND a.student_id = r.student_id
                         WHERE r.quiz_id = :q
                         ORDER BY s.roll_no
                        """)
                .param("q", quizId)
                .query((rs, n) -> new ResultRow(rs.getString("roll_no"), rs.getString("name"), rs.getString("status"),
                        rs.getBigDecimal("score"), rs.getBigDecimal("max_score"), rs.getInt("second_logins")))
                .list();
    }

    public byte[] excel(String quizTitle, long quizId) throws IOException {
        List<ResultRow> rows = results(quizId);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Marks");
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);

            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue(quizTitle);
            title.getCell(0).setCellStyle(bold);

            Row header = sheet.createRow(2);
            String[] headings = {"Roll number", "Name", "Total marks", "Out of", "Status"};
            for (int i = 0; i < headings.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(headings[i]);
                c.setCellStyle(bold);
            }

            int r = 3;
            for (ResultRow row : rows) {
                Row x = sheet.createRow(r++);
                x.createCell(0).setCellValue(row.rollNo());
                x.createCell(1).setCellValue(row.name());
                if (row.score() != null) {
                    x.createCell(2).setCellValue(row.score().doubleValue());
                } else {
                    x.createCell(2).setCellValue(row.status().equals("ABSENT") ? "Absent" : "Not submitted");
                }
                if (row.maxScore() != null) {
                    x.createCell(3).setCellValue(row.maxScore().doubleValue());
                }
                x.createCell(4).setCellValue(label(row.status()));
            }
            // Fixed widths (in 1/256 of a character): autoSizeColumn needs desktop fonts that servers often lack.
            int[] widths = {16, 32, 14, 10, 28};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    public static String label(String status) {
        return switch (status) {
            case "SUBMITTED" -> "Submitted";
            case "AUTO_SUBMITTED" -> "Auto-submitted at time end";
            case "IN_PROGRESS" -> "In progress";
            default -> "Absent";
        };
    }
}
