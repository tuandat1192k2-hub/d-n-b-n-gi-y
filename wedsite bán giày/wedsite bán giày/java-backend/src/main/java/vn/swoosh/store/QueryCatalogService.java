package vn.swoosh.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Danh mục 109 câu lệnh truy vấn (T-SQL) lấy từ file ULNL_cau_lenh_truy_van.xlsx.
 * Mỗi câu lưu trong resources/queries/cau-lenh-truy-van.json (stt, name, owner, sql, note).
 * Tham số dạng @ten trong T-SQL được đổi thành ? và gán theo giá trị client gửi lên.
 */
@Service
public class QueryCatalogService {
    private static final Pattern PARAM = Pattern.compile("@(?!@)([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern DECLARE = Pattern.compile("(?i)\\bDECLARE\\s+@([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern MANUAL_ONLY = Pattern.compile("(?i)\\b(BACKUP|RESTORE)\\s+DATABASE\\b");
    private static final int MAX_ROWS = 1000;

    public record Entry(int stt, String name, String owner, String sql, String note) {
    }

    private final JdbcTemplate db;
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();

    public QueryCatalogService(JdbcTemplate db, ObjectMapper mapper) throws IOException {
        this.db = db;
        try (InputStream in = new ClassPathResource("queries/cau-lenh-truy-van.json").getInputStream()) {
            List<Entry> loaded = mapper.readValue(in, new TypeReference<List<Entry>>() {
            });
            for (Entry entry : loaded) entries.put(entry.stt(), entry);
        }
    }

    public List<Map<String, Object>> list(String owner) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (owner != null && !owner.isBlank()
                    && (entry.owner() == null || !entry.owner().toLowerCase().contains(owner.toLowerCase()))) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stt", entry.stt());
            row.put("name", entry.name());
            row.put("owner", entry.owner());
            row.put("params", parameters(entry.sql()));
            row.put("runnable", !MANUAL_ONLY.matcher(entry.sql()).find());
            rows.add(row);
        }
        return rows;
    }

    public Map<String, Object> detail(int stt) {
        Entry entry = find(stt);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stt", entry.stt());
        row.put("name", entry.name());
        row.put("owner", entry.owner());
        row.put("sql", entry.sql());
        row.put("note", entry.note());
        row.put("params", parameters(entry.sql()));
        row.put("runnable", !MANUAL_ONLY.matcher(entry.sql()).find());
        return row;
    }

    /** Tên các tham số bên ngoài (không tính biến DECLARE trong script). */
    public List<String> parameters(String sql) {
        return new ArrayList<>(bind(sql).names());
    }

    /**
     * Chạy toàn bộ script của một câu truy vấn trong một giao dịch.
     * Mặc định (commit = false) giao dịch bị ROLLBACK sau khi chạy nên các câu INSERT/UPDATE/DELETE không ghi vào CSDL.
     */
    public Map<String, Object> run(int stt, Map<String, Object> values, boolean commit) {
        Entry entry = find(stt);
        if (MANUAL_ONLY.matcher(entry.sql()).find()) {
            throw new ApiException(400, "Câu lệnh sao lưu/phục hồi chỉ chạy thủ công trên SQL Server (SSMS).");
        }
        Bound bound = bind(entry.sql());
        for (String name : bound.names()) {
            if (!values.containsKey(name)) throw new ApiException(400, "Thiếu tham số @" + name + ".");
        }
        return db.execute((ConnectionCallback<Map<String, Object>>) connection -> {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Map<String, Object> result = execute(connection, bound, values);
                if (commit) connection.commit();
                else connection.rollback();
                result.put("committed", commit);
                return result;
            } catch (SQLException error) {
                connection.rollback();
                if (error.getErrorCode() >= 50000) throw new ApiException(400, error.getMessage());
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        });
    }

    private Map<String, Object> execute(Connection connection, Bound bound, Map<String, Object> values)
            throws SQLException {
        List<List<Map<String, Object>>> resultSets = new ArrayList<>();
        List<Integer> updateCounts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            int index = 1;
            for (String name : bound.order()) {
                Object value = values.get(name);
                if (value == null) statement.setNull(index++, Types.NVARCHAR);
                else statement.setObject(index++, value);
            }
            boolean isResultSet = statement.execute();
            while (true) {
                if (isResultSet) {
                    try (ResultSet rs = statement.getResultSet()) {
                        resultSets.add(rows(rs));
                    }
                } else {
                    int count = statement.getUpdateCount();
                    if (count == -1) break;
                    updateCounts.add(count);
                }
                isResultSet = statement.getMoreResults();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultSets", resultSets);
        result.put("updateCounts", updateCounts);
        return result;
    }

    private static List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < MAX_ROWS) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                Object value = rs.getObject(i);
                if (value instanceof byte[]) value = "(binary)";
                else if (value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor) {
                    value = value.toString();
                }
                row.put(meta.getColumnLabel(i), value);
            }
            rows.add(row);
        }
        return rows;
    }

    private Entry find(int stt) {
        Entry entry = entries.get(stt);
        if (entry == null) throw new ApiException(404, "Không tìm thấy câu truy vấn số " + stt + ".");
        return entry;
    }

    private record Bound(String sql, List<String> order, Set<String> names) {
    }

    /** Bỏ chú thích, đổi @tham_số thành ?, bỏ qua nội dung trong chuỗi '...' và biến DECLARE. */
    private static Bound bind(String script) {
        String code = stripComments(script);
        Set<String> declared = new LinkedHashSet<>();
        Matcher declare = DECLARE.matcher(code);
        while (declare.find()) declared.add(declare.group(1).toLowerCase());

        StringBuilder out = new StringBuilder();
        List<String> order = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '\'') {
                int end = i + 1;
                while (end < code.length()) {
                    if (code.charAt(end) == '\'') {
                        if (end + 1 < code.length() && code.charAt(end + 1) == '\'') end += 2;
                        else break;
                    } else end++;
                }
                end = Math.min(end + 1, code.length());
                out.append(code, i, end);
                i = end;
                continue;
            }
            Matcher m = PARAM.matcher(code);
            if (c == '@' && m.find(i) && m.start() == i && (i == 0 || code.charAt(i - 1) != '@')) {
                String name = m.group(1);
                if (declared.contains(name.toLowerCase())) {
                    out.append(m.group());
                } else {
                    out.append('?');
                    order.add(name);
                    names.add(name);
                }
                i = m.end();
                continue;
            }
            out.append(c);
            i++;
        }
        return new Bound(out.toString(), order, names);
    }

    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int end = i + 1;
                while (end < sql.length()) {
                    if (sql.charAt(end) == '\'') {
                        if (end + 1 < sql.length() && sql.charAt(end + 1) == '\'') end += 2;
                        else break;
                    } else end++;
                }
                end = Math.min(end + 1, sql.length());
                out.append(sql, i, end);
                i = end;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
