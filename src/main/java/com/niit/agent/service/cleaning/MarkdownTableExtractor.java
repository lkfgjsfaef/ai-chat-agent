package com.niit.agent.service.cleaning;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提取 Markdown 表格，将每一行展开为自包含的文本 chunk。
 * 正文不做任何修改，表格行 chunk 作为纯增量合并到最终的 Document 列表中，
 * 避免修改正文引入的上下文丢失风险。
 *
 * 输入示例：
 * | 姓名 | 年龄 |
 * |------|------|
 * | 张三 | 30   |
 *
 * 输出：
 * - 整表摘要 chunk
 * - 每行自包含 chunk: "表1 > 第1行：姓名=张三，年龄=30"
 * - 正文原封不动
 */
public class MarkdownTableExtractor {

    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\|?\\s*:?---+:?\\s*\\|.*$");
    private static final Pattern TABLE_ROW = Pattern.compile(
            "^\\|.*\\|$");
    private static final Pattern CELL_SPLIT = Pattern.compile(
            "\\|\\s*([^|]*?)\\s*(?=\\|)");

    private static final int MAX_CELL_CHARS = 200;
    private static final int MAX_TABLE_ROWS = 200;

    /**
     * 提取表格并生成自包含行 chunk，同时在正文中将表格替换为占位符避免重复分块。
     */
    public TableExtractionResult extract(String text) {
        if (text == null || text.isEmpty()) {
            return new TableExtractionResult(text, List.of());
        }

        String[] lines = text.split("\n", -1);
        List<TableInfo> tables = detectTables(lines);

        if (tables.isEmpty()) {
            return new TableExtractionResult(text, List.of());
        }

        List<TableChunk> chunks = new ArrayList<>();
        // 用集合记录被表格占用的行号
        boolean[] tableLineFlags = new boolean[lines.length];
        int tableIndex = 0;
        for (TableInfo table : tables) {
            String tableLabel = "表" + (tableIndex + 1) + ": " + table.label;
            chunks.addAll(buildTableChunks(table, tableLabel));
            for (int i = table.startLine; i <= table.endLine; i++) {
                tableLineFlags[i] = true;
            }
            tableIndex++;
        }

        // 重建正文：表格行替换为占位符
        StringBuilder cleanedBody = new StringBuilder();
        int pendingTableIdx = 0;
        for (int i = 0; i < lines.length; i++) {
            if (tableLineFlags[i]) {
                // 仅在表格起始行插入占位符
                if (i == 0 || !tableLineFlags[i - 1]) {
                    if (cleanedBody.length() > 0 && cleanedBody.charAt(cleanedBody.length() - 1) != '\n') {
                        cleanedBody.append('\n');
                    }
                    cleanedBody.append("[详见表").append(pendingTableIdx + 1).append(": ");
                    cleanedBody.append(tables.get(pendingTableIdx).label).append("]\n");
                    pendingTableIdx++;
                }
            } else {
                cleanedBody.append(lines[i]).append('\n');
            }
        }

        return new TableExtractionResult(cleanedBody.toString(), chunks);
    }

    private List<TableInfo> detectTables(String[] lines) {
        List<TableInfo> tables = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            if (i + 2 < lines.length
                    && TABLE_ROW.matcher(lines[i]).matches()
                    && TABLE_SEPARATOR.matcher(lines[i + 1]).matches()
                    && TABLE_ROW.matcher(lines[i + 2]).matches()) {

                int startLine = i;
                List<List<String>> allRows = new ArrayList<>();

                List<String> header = parseRow(lines[i]);
                allRows.add(header);
                i += 2;

                while (i < lines.length && TABLE_ROW.matcher(lines[i]).matches()) {
                    allRows.add(parseRow(lines[i]));
                    i++;
                    if (allRows.size() > MAX_TABLE_ROWS) break;
                }

                String label = buildTableLabel(header);
                tables.add(new TableInfo(startLine, i - 1, label, header, allRows.subList(1, allRows.size())));
            } else {
                i++;
            }
        }
        return tables;
    }

    private List<String> parseRow(String line) {
        List<String> cells = new ArrayList<>();
        Matcher m = CELL_SPLIT.matcher(line + " ");
        while (m.find()) {
            String cell = m.group(1).trim();
            if (cell.length() > MAX_CELL_CHARS) {
                cell = cell.substring(0, MAX_CELL_CHARS) + "...";
            }
            cells.add(cell);
        }
        return cells;
    }

    private String buildTableLabel(List<String> header) {
        if (header.isEmpty()) return "表格";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, header.size()); i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(header.get(i));
        }
        return sb.append("等").toString();
    }

    private List<TableChunk> buildTableChunks(TableInfo table, String tableLabel) {
        List<TableChunk> chunks = new ArrayList<>();

        // 整表摘要
        StringBuilder summary = new StringBuilder();
        summary.append("[").append(tableLabel).append("]\n");
        summary.append("包含 ").append(table.dataRows.size()).append(" 条记录");
        if (!table.header.isEmpty()) {
            summary.append("，列：").append(String.join("、", table.header));
        }
        summary.append("。");
        chunks.add(new TableChunk(summary.toString(), tableLabel, "摘要"));

        // 每行自包含 chunk：表头粘合到每一行
        for (int i = 0; i < table.dataRows.size(); i++) {
            List<String> row = table.dataRows.get(i);
            StringBuilder rowText = new StringBuilder();
            rowText.append(tableLabel).append(" > 第").append(i + 1).append("行：");
            if (table.header.size() == row.size()) {
                for (int j = 0; j < row.size(); j++) {
                    if (j > 0) rowText.append("，");
                    rowText.append(table.header.get(j)).append("=").append(row.get(j));
                }
            } else {
                rowText.append(String.join(" | ", row));
            }
            chunks.add(new TableChunk(rowText.toString(), tableLabel, "第" + (i + 1) + "行"));
        }

        return chunks;
    }

    public record TableChunk(String content, String tableLabel, String section) {}

    public record TableExtractionResult(String cleanedText, List<TableChunk> tableChunks) {}

    private record TableInfo(int startLine, int endLine, String label,
                             List<String> header, List<List<String>> dataRows) {}
}
