package vn.swoosh.store.members.tuandat.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.swoosh.store.SecurityService;
import vn.swoosh.store.StoreSupport;

@RestController
@RequestMapping("/api")
public class RunnerFeaturesController {
    private final JdbcTemplate db;
    private final SecurityService security;

    public RunnerFeaturesController(JdbcTemplate db, SecurityService security) {
        this.db = db;
        this.security = security;
    }

    @GetMapping("/runner/catalogs")
    Map<String, Object> catalogs() {
        return Map.of(
            "distances", db.queryForList("""
                SELECT distance_code AS code,distance_label AS label,min_km AS minKm,max_km AS maxKm
                FROM running_distances ORDER BY min_km
                """),
            "terrains", db.queryForList("""
                SELECT terrain_code AS code,terrain_name AS name FROM terrain_types ORDER BY terrain_name
                """),
            "arches", db.queryForList("""
                SELECT arch_code AS code,arch_name AS name,support_feature_needed AS support
                FROM foot_arch_types ORDER BY arch_name
                """),
            "budgets", db.queryForList("""
                SELECT budget_code AS code,range_label AS label,min_price AS minPrice,max_price AS maxPrice
                FROM budget_ranges ORDER BY min_price
                """));
    }

    @GetMapping("/runner/profile")
    Map<String, Object> profile(HttpServletRequest request) {
        Map<String, Object> user = security.requireRole(request, "customer");
        List<Map<String, Object>> rows = db.queryForList("""
            SELECT profile.profile_id AS id,profile.profile_name AS name,profile.gender,profile.age,
                   profile.height_cm AS heightCm,profile.weight_kg AS weightKg,
                   profile.running_experience_months AS experienceMonths,profile.runner_level AS level,
                   profile.running_goal AS goal,profile.avg_session_distance_km AS avgDistanceKm,
                   profile.total_weekly_distance_km AS weeklyDistanceKm,
                   profile.sessions_per_week AS sessionsPerWeek,profile.avg_pace AS avgPace,
                   profile.strike_pattern AS strikePattern,profile.dominant_foot AS dominantFoot,
                   measurement.foot_length_mm AS footLengthMm,measurement.foot_width_mm AS footWidthMm,
                   measurement.arch_type AS archType,measurement.forefoot_width_type AS forefootWidth
            FROM runner_profiles profile
            LEFT JOIN runner_foot_measurements measurement ON measurement.profile_id=profile.profile_id
            WHERE profile.user_id=? ORDER BY profile.created_at
            """, user.get("user_id"));
        return Map.of("profiles", rows);
    }

    @PutMapping("/runner/profile")
    @Transactional
    Map<String, Object> saveProfile(@RequestBody Map<String, Object> data, HttpServletRequest request) {
        Map<String, Object> user = security.requireRole(request, "customer");
        security.requireCsrf(user, request);
        Map<String, Object> values = validateProfile(data);
        int userId = Math.toIntExact(StoreSupport.number(user.get("user_id")));
        List<Map<String, Object>> existing = db.queryForList(
            "SELECT TOP (1) profile_id,weight_kg,running_goal FROM runner_profiles WHERE user_id=? ORDER BY created_at",
            userId);
        int profileId;
        if (existing.isEmpty()) {
            profileId = db.queryForObject("""
                INSERT INTO runner_profiles(user_id,profile_name,gender,age,height_cm,weight_kg,
                    running_experience_months,runner_level,running_goal,avg_session_distance_km,
                    total_weekly_distance_km,sessions_per_week,avg_pace,strike_pattern,dominant_foot)
                OUTPUT INSERTED.profile_id VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, Integer.class, userId, values.get("name"), values.get("gender"), values.get("age"),
                values.get("heightCm"), values.get("weightKg"), values.get("experienceMonths"),
                values.get("level"), values.get("goal"), values.get("avgDistanceKm"),
                values.get("weeklyDistanceKm"), values.get("sessionsPerWeek"), values.get("avgPace"),
                values.get("strikePattern"), values.get("dominantFoot"));
            db.update("INSERT INTO runner_weight_history(profile_id,weight_kg) VALUES(?,?)",
                profileId, values.get("weightKg"));
        } else {
            profileId = Math.toIntExact(StoreSupport.number(existing.get(0).get("profile_id")));
            Object oldWeight = existing.get(0).get("weight_kg");
            Object oldGoal = existing.get(0).get("running_goal");
            db.update("""
                UPDATE runner_profiles SET profile_name=?,gender=?,age=?,height_cm=?,weight_kg=?,
                    running_experience_months=?,runner_level=?,running_goal=?,avg_session_distance_km=?,
                    total_weekly_distance_km=?,sessions_per_week=?,avg_pace=?,strike_pattern=?,
                    dominant_foot=?,updated_at=SYSUTCDATETIME() WHERE profile_id=?
                """, values.get("name"), values.get("gender"), values.get("age"), values.get("heightCm"),
                values.get("weightKg"), values.get("experienceMonths"), values.get("level"),
                values.get("goal"), values.get("avgDistanceKm"), values.get("weeklyDistanceKm"),
                values.get("sessionsPerWeek"), values.get("avgPace"), values.get("strikePattern"),
                values.get("dominantFoot"), profileId);
            if (Double.compare(((Number) oldWeight).doubleValue(),
                    ((Number) values.get("weightKg")).doubleValue()) != 0) {
                db.update("INSERT INTO runner_weight_history(profile_id,weight_kg) VALUES(?,?)",
                    profileId, values.get("weightKg"));
                logProfileChange(profileId, "weight_kg", oldWeight, values.get("weightKg"));
            }
            if (!java.util.Objects.equals(oldGoal, values.get("goal"))) {
                logProfileChange(profileId, "running_goal", oldGoal, values.get("goal"));
            }
        }
        if (values.get("footLengthMm") != null) {
            int changed = db.update("""
                UPDATE runner_foot_measurements SET foot_length_mm=?,foot_width_mm=?,arch_type=?,
                    forefoot_width_type=?,measured_at=SYSUTCDATETIME() WHERE profile_id=?
                """, values.get("footLengthMm"), values.get("footWidthMm"), values.get("archType"),
                values.get("forefootWidth"), profileId);
            if (changed == 0) {
                db.update("""
                    INSERT INTO runner_foot_measurements(profile_id,foot_length_mm,foot_width_mm,
                        arch_type,forefoot_width_type) VALUES(?,?,?,?,?)
                    """, profileId, values.get("footLengthMm"), values.get("footWidthMm"),
                    values.get("archType"), values.get("forefootWidth"));
            }
        }
        return Map.of("ok", true, "profileId", profileId);
    }

    @GetMapping("/runner/profile/weight-history")
    Map<String, Object> weightHistory(HttpServletRequest request) {
        Map<String, Object> user = security.requireRole(request, "customer");
        return Map.of("history", db.queryForList("""
            SELECT history.weight_kg AS weightKg,history.recorded_at AS recordedAt
            FROM runner_weight_history history
            JOIN runner_profiles profile ON profile.profile_id=history.profile_id
            WHERE profile.user_id=? ORDER BY history.recorded_at DESC
            """, user.get("user_id")));
    }

    @PostMapping("/recommendations")
    @Transactional
    Map<String, Object> startRecommendation(@RequestBody Map<String, Object> data,
                                            HttpServletRequest request) {
        Double weight = decimal(data.get("weightKg"), "Cân nặng không hợp lệ.");
        if (weight < 20 || weight > 250) StoreSupport.fail(400, "Cân nặng phải từ 20 đến 250 kg.");
        int distanceId = catalogId("running_distances", "distance_code", data.get("distanceCode"));
        int terrainId = catalogId("terrain_types", "terrain_code", data.get("terrainCode"));
        int archId = catalogId("foot_arch_types", "arch_code", data.get("archCode"));
        int budgetId = catalogId("budget_ranges", "budget_code", data.get("budgetCode"));
        Long userId = null;
        Integer profileId = null;
        Map<String, Object> current = security.currentSession(request);
        if (current != null && current.get("user_id") != null
                && "customer".equals(current.get("role"))) {
            security.requireCsrf(current, request);
            userId = StoreSupport.number(current.get("user_id"));
            if (data.get("profileId") != null) {
                profileId = StoreSupport.integer(data.get("profileId"), "Hồ sơ chạy không hợp lệ.");
                if (db.queryForList("SELECT 1 FROM runner_profiles WHERE profile_id=? AND user_id=?",
                        profileId, userId).isEmpty()) {
                    StoreSupport.fail(404, "Không tìm thấy hồ sơ chạy.");
                }
            }
        } else if (data.get("profileId") != null) {
            StoreSupport.fail(401, "Đăng nhập để dùng hồ sơ chạy đã lưu.");
        }
        Integer weightGroupId = db.queryForObject("""
            SELECT TOP (1) weight_group_id FROM weight_groups
            WHERE min_weight_kg<=? AND max_weight_kg>=? ORDER BY min_weight_kg DESC
            """, Integer.class, weight, weight);
        if (weightGroupId == null) StoreSupport.fail(400, "Chưa có nhóm cân nặng phù hợp.");
        int sessionId = db.queryForObject("""
            INSERT INTO recommendation_sessions(user_id,profile_id,input_weight_kg,
                selected_distance_id,selected_terrain_id,selected_arch_id,selected_budget_id)
            OUTPUT INSERTED.session_id VALUES(?,?,?,?,?,?,?)
            """, Integer.class, userId, profileId, weight, distanceId, terrainId, archId, budgetId);
        return Map.of("sessionId", sessionId, "recommendations",
            scoreShoes(weightGroupId, distanceId, terrainId, archId, budgetId));
    }

    @GetMapping("/recommendations/{sessionId}")
    Map<String, Object> getRecommendation(@PathVariable int sessionId, HttpServletRequest request) {
        Map<String, Object> session = requireOwnedSession(sessionId, request);
        if (session.get("user_id") == null) StoreSupport.fail(401, "Đăng nhập để xem kết quả đã lưu.");
        return Map.of("sessionId", sessionId,
            "results", db.queryForList("""
                SELECT results.rank_position AS rank,results.match_score AS score,
                       results.match_reason_weight AS weightReason,
                       results.match_reason_distance AS distanceReason,
                       results.match_reason_terrain AS terrainReason,
                       results.match_reason_arch AS archReason,
                       results.match_reason_budget AS budgetReason,
                       shoes.shoe_code AS shoeCode,shoes.shoe_name AS shoeName,
                       COALESCE(shoes.sale_price,shoes.base_price) AS price,
                       brands.brand_name AS brand
                FROM recommendation_results results
                JOIN running_shoes shoes ON shoes.shoe_id=results.shoe_id
                JOIN brands ON brands.brand_id=shoes.brand_id
                WHERE results.session_id=? ORDER BY results.rank_position
                """, sessionId));
    }

    @PostMapping("/recommendations/{sessionId}/save")
    @Transactional
    Map<String, Object> saveRecommendation(@PathVariable int sessionId,
                                           @RequestBody Map<String, Object> data,
                                           HttpServletRequest request) {
        Map<String, Object> user = security.requireRole(request, "customer");
        security.requireCsrf(user, request);
        Map<String, Object> session = requireOwnedSession(sessionId, request);
        if (!java.util.Objects.equals(session.get("user_id"), user.get("user_id"))) {
            StoreSupport.fail(404, "Không tìm thấy phiên tư vấn.");
        }
        Object rawCodes = data.get("shoeCodes");
        if (!(rawCodes instanceof List<?>)) {
            StoreSupport.fail(400, "Chọn từ 1 đến 5 mẫu giày để lưu kết quả.");
        }
        List<?> codes = (List<?>) rawCodes;
        if (codes.isEmpty() || codes.size() > 5) StoreSupport.fail(400, "Chọn từ 1 đến 5 mẫu giày để lưu kết quả.");
        List<Map<String, Object>> candidates = scoreShoes(
            StoreSupport.number(session.get("weight_group_id")),
            Math.toIntExact(StoreSupport.number(session.get("selected_distance_id"))),
            Math.toIntExact(StoreSupport.number(session.get("selected_terrain_id"))),
            Math.toIntExact(StoreSupport.number(session.get("selected_arch_id"))),
            Math.toIntExact(StoreSupport.number(session.get("selected_budget_id"))));
        Map<String, Map<String, Object>> byCode = new HashMap<>();
        for (Map<String, Object> candidate : candidates) {
            byCode.put(StoreSupport.text(candidate.get("shoeCode")), candidate);
        }
        List<String> shoeCodes = new ArrayList<>();
        for (Object code : codes) {
            if (!(code instanceof String value) || !byCode.containsKey(value) || shoeCodes.contains(value)) {
                StoreSupport.fail(400, "Danh sách mẫu giày không hợp lệ.");
            }
            shoeCodes.add((String) code);
        }
        db.update("DELETE FROM recommendation_results WHERE session_id=?", sessionId);
        for (int i = 0; i < shoeCodes.size(); i++) {
            Map<String, Object> recommendation = byCode.get(shoeCodes.get(i));
            int shoeId = Math.toIntExact(StoreSupport.number(recommendation.get("shoeId")));
            db.update("""
                INSERT INTO recommendation_results(session_id,shoe_id,match_score,rank_position,
                    match_reason_weight,match_reason_distance,match_reason_terrain,
                    match_reason_arch,match_reason_budget,unmatched_criteria_note)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, sessionId, shoeId, recommendation.get("score"), i + 1,
                recommendation.get("weightReason"), recommendation.get("distanceReason"),
                recommendation.get("terrainReason"), recommendation.get("archReason"),
                recommendation.get("budgetReason"), recommendation.get("note"));
            db.update("""
                IF EXISTS (SELECT 1 FROM consultation_analytics WHERE stat_date=CONVERT(date,GETDATE()) AND shoe_id=?)
                  UPDATE consultation_analytics SET recommended_count=recommended_count+1
                  WHERE stat_date=CONVERT(date,GETDATE()) AND shoe_id=?
                ELSE
                  INSERT INTO consultation_analytics(stat_date,shoe_id,recommended_count,clicked_count)
                  VALUES(CONVERT(date,GETDATE()),?,1,0)
                """, shoeId, shoeId, shoeId);
        }
        return Map.of("ok", true, "savedCount", shoeCodes.size());
    }

    private List<Map<String, Object>> scoreShoes(long weightGroupId, int distanceId,
                                                  int terrainId, int archId, int budgetId) {
        return db.queryForList("""
            SELECT shoes.shoe_id AS shoeId,shoes.shoe_code AS shoeCode,shoes.shoe_name AS shoeName,
                   brands.brand_name AS brand,COALESCE(shoes.sale_price,shoes.base_price) AS price,
                   shoes.weight_gram AS weightGram,shoes.stability_level AS stability,
                   CASE WHEN shoes.suitable_weight_group_id=? THEN 25
                        WHEN shoes.suitable_weight_group_id IS NULL THEN 12 ELSE 0 END AS weightScore,
                   CASE WHEN shoes.suitable_distance_id=? THEN 25
                        WHEN shoes.suitable_distance_id IS NULL THEN 12 ELSE 0 END AS distanceScore,
                   CASE WHEN EXISTS (
                     SELECT 1 FROM shoe_suitable_terrains suitable
                     WHERE suitable.shoe_id=shoes.shoe_id AND suitable.terrain_id=?
                   ) THEN 20 ELSE 0 END AS terrainScore,
                   CASE WHEN shoes.suitable_arch_id=? THEN 20
                        WHEN shoes.suitable_arch_id IS NULL THEN 10 ELSE 0 END AS archScore,
                   CASE WHEN COALESCE(shoes.sale_price,shoes.base_price)<=budget.max_price THEN 10 ELSE 0 END
                        AS budgetScore,
                   budget.max_price AS budgetMax
            FROM running_shoes shoes
            JOIN brands ON brands.brand_id=shoes.brand_id
            CROSS JOIN budget_ranges budget
            WHERE shoes.status='ACTIVE' AND budget.budget_id=?
            ORDER BY
              CASE WHEN shoes.suitable_weight_group_id=? THEN 25
                   WHEN shoes.suitable_weight_group_id IS NULL THEN 12 ELSE 0 END
              + CASE WHEN shoes.suitable_distance_id=? THEN 25
                     WHEN shoes.suitable_distance_id IS NULL THEN 12 ELSE 0 END
              + CASE WHEN EXISTS (SELECT 1 FROM shoe_suitable_terrains suitable
                                  WHERE suitable.shoe_id=shoes.shoe_id AND suitable.terrain_id=?) THEN 20 ELSE 0 END
              + CASE WHEN shoes.suitable_arch_id=? THEN 20
                     WHEN shoes.suitable_arch_id IS NULL THEN 10 ELSE 0 END
              + CASE WHEN COALESCE(shoes.sale_price,shoes.base_price)<=budget.max_price THEN 10 ELSE 0 END DESC,
              shoes.shoe_name
            """, weightGroupId, distanceId, terrainId, archId, budgetId,
            weightGroupId, distanceId, terrainId, archId).stream().limit(5).map(this::recommendationJson).toList();
    }

    private Map<String, Object> recommendationJson(Map<String, Object> row) {
        int weightScore = Math.toIntExact(StoreSupport.number(row.get("weightScore")));
        int distanceScore = Math.toIntExact(StoreSupport.number(row.get("distanceScore")));
        int terrainScore = Math.toIntExact(StoreSupport.number(row.get("terrainScore")));
        int archScore = Math.toIntExact(StoreSupport.number(row.get("archScore")));
        int budgetScore = Math.toIntExact(StoreSupport.number(row.get("budgetScore")));
        int score = weightScore + distanceScore + terrainScore + archScore + budgetScore;
        Map<String, Object> result = new HashMap<>();
        result.put("shoeId", row.get("shoeId"));
        result.put("shoeCode", row.get("shoeCode"));
        result.put("shoeName", row.get("shoeName"));
        result.put("brand", row.get("brand"));
        result.put("price", row.get("price"));
        result.put("weightGram", row.get("weightGram"));
        result.put("stability", row.get("stability"));
        result.put("score", score);
        result.put("weightReason", weightScore >= 20 ? "Phù hợp với nhóm cân nặng." : "Thông số cân nặng chưa tối ưu.");
        result.put("distanceReason", distanceScore >= 20 ? "Phù hợp với cự ly đã chọn." : "Chưa có dữ liệu cự ly chuyên biệt.");
        result.put("terrainReason", terrainScore > 0 ? "Có đánh giá phù hợp địa hình." : "Chưa có dữ liệu địa hình.");
        result.put("archReason", archScore >= 20 ? "Phù hợp với kiểu bàn chân." : "Thông số vòm chân chưa được khai báo.");
        result.put("budgetReason", budgetScore > 0 ? "Nằm trong ngân sách." : "Giá vượt ngân sách đã chọn.");
        result.put("note", score < 70 ? "Một số tiêu chí chưa có dữ liệu phù hợp." : "");
        return result;
    }

    private Map<String, Object> requireOwnedSession(int sessionId, HttpServletRequest request) {
        Map<String, Object> user = security.requireRole(request, "customer", "admin");
        List<Map<String, Object>> rows = db.queryForList("""
            SELECT session_id,user_id,input_weight_kg,selected_distance_id,
                   selected_terrain_id,selected_arch_id,selected_budget_id
            FROM recommendation_sessions WHERE session_id=?
            """, sessionId);
        if (rows.isEmpty()) StoreSupport.fail(404, "Không tìm thấy phiên tư vấn.");
        Map<String, Object> session = rows.get(0);
        if (!"admin".equals(user.get("role"))
                && !java.util.Objects.equals(session.get("user_id"), user.get("user_id"))) {
            StoreSupport.fail(404, "Không tìm thấy phiên tư vấn.");
        }
        double weight = ((Number) session.get("input_weight_kg")).doubleValue();
        Integer weightGroupId = db.queryForObject("""
            SELECT TOP (1) weight_group_id FROM weight_groups
            WHERE min_weight_kg<=? AND max_weight_kg>=?
            ORDER BY min_weight_kg DESC
            """, Integer.class, weight, weight);
        session.put("weight_group_id", weightGroupId);
        return session;
    }

    private int catalogId(String table, String codeColumn, Object rawCode) {
        String code = StoreSupport.text(rawCode).trim().toUpperCase();
        if (!code.matches("[A-Z0-9_]{2,50}")) StoreSupport.fail(400, "Thông tin tư vấn không hợp lệ.");
        List<Integer> ids = db.queryForList("SELECT id FROM (SELECT " + tableIdColumn(table)
                + " AS id," + codeColumn + " AS code FROM " + table + ") catalog WHERE code=?",
            Integer.class, code);
        if (ids.isEmpty()) StoreSupport.fail(400, "Không tìm thấy lựa chọn tư vấn.");
        return ids.get(0);
    }

    private static String tableIdColumn(String table) {
        return switch (table) {
            case "running_distances" -> "distance_id";
            case "terrain_types" -> "terrain_id";
            case "foot_arch_types" -> "arch_type_id";
            case "budget_ranges" -> "budget_id";
            default -> throw new IllegalArgumentException("Unsupported catalog table.");
        };
    }

    private Map<String, Object> validateProfile(Map<String, Object> data) {
        Map<String, Object> values = new HashMap<>();
        String name = requiredText(data.get("name"), 2, 100, "Tên hồ sơ không hợp lệ.");
        String gender = optionalText(data.get("gender"), 10, "Giới tính không hợp lệ.");
        if (gender != null && !List.of("Nam", "Nữ", "Khác").contains(gender)) {
            StoreSupport.fail(400, "Giới tính không hợp lệ.");
        }
        Integer age = optionalInteger(data.get("age"), 6, 100, "Tuổi không hợp lệ.");
        Double height = optionalDecimal(data.get("heightCm"), 100, 250, "Chiều cao không hợp lệ.");
        Double weight = decimal(data.get("weightKg"), "Cân nặng không hợp lệ.");
        if (weight < 20 || weight > 250) StoreSupport.fail(400, "Cân nặng phải từ 20 đến 250 kg.");
        Integer experience = optionalInteger(data.getOrDefault("experienceMonths", 0), 0, 1200,
            "Kinh nghiệm chạy không hợp lệ.");
        String level = optionalText(data.getOrDefault("level", "Beginner"), 50, "Cấp độ chạy không hợp lệ.");
        if (!List.of("Beginner", "Intermediate", "Advanced").contains(level)) {
            StoreSupport.fail(400, "Cấp độ chạy không hợp lệ.");
        }
        String goal = optionalText(data.get("goal"), 100, "Mục tiêu chạy không hợp lệ.");
        Double avgDistance = optionalDecimal(data.get("avgDistanceKm"), 0.1, 200, "Cự ly chạy không hợp lệ.");
        Double weeklyDistance = optionalDecimal(data.get("weeklyDistanceKm"), 0, 500, "Cự ly tuần không hợp lệ.");
        Integer sessions = optionalInteger(data.get("sessionsPerWeek"), 1, 30, "Số buổi chạy không hợp lệ.");
        String pace = optionalText(data.get("avgPace"), 20, "Pace trung bình không hợp lệ.");
        String strike = optionalText(data.get("strikePattern"), 50, "Kiểu tiếp đất không hợp lệ.");
        String dominant = optionalText(data.get("dominantFoot"), 10, "Chân thuận không hợp lệ.");
        if (dominant != null && !List.of("Trái", "Phải").contains(dominant)) {
            StoreSupport.fail(400, "Chân thuận không hợp lệ.");
        }
        values.put("name", name);
        values.put("gender", gender);
        values.put("age", age);
        values.put("heightCm", height);
        values.put("weightKg", weight);
        values.put("experienceMonths", experience);
        values.put("level", level);
        values.put("goal", goal);
        values.put("avgDistanceKm", avgDistance);
        values.put("weeklyDistanceKm", weeklyDistance);
        values.put("sessionsPerWeek", sessions);
        values.put("avgPace", pace);
        values.put("strikePattern", strike);
        values.put("dominantFoot", dominant);

        boolean hasMeasurement = data.get("footLengthMm") != null || data.get("footWidthMm") != null
            || data.get("archType") != null || data.get("forefootWidth") != null;
        if (hasMeasurement) {
            Double length = decimal(data.get("footLengthMm"), "Chiều dài bàn chân không hợp lệ.");
            Double width = decimal(data.get("footWidthMm"), "Chiều rộng bàn chân không hợp lệ.");
            if (length < 150 || length > 350 || width < 50 || width > 180) {
                StoreSupport.fail(400, "Thông số đo bàn chân nằm ngoài khoảng cho phép.");
            }
            String arch = requiredText(data.get("archType"), 2, 50, "Kiểu vòm chân không hợp lệ.");
            String forefootWidth = optionalText(data.get("forefootWidth"), 50, "Độ rộng bàn chân không hợp lệ.");
            values.put("footLengthMm", length);
            values.put("footWidthMm", width);
            values.put("archType", arch);
            values.put("forefootWidth", forefootWidth);
        } else {
            values.put("footLengthMm", null);
        }
        return values;
    }

    private void logProfileChange(int profileId, String field, Object oldValue, Object newValue) {
        db.update("""
            INSERT INTO runner_profile_logs(profile_id,field_changed,old_value,new_value)
            VALUES(?,?,?,?)
            """, profileId, field, StoreSupport.text(oldValue), StoreSupport.text(newValue));
    }

    private static String requiredText(Object raw, int min, int max, String message) {
        if (!(raw instanceof String value)) StoreSupport.fail(400, message);
        String text = ((String) raw).trim();
        if (text.length() < min || text.length() > max) StoreSupport.fail(400, message);
        return text;
    }

    private static String optionalText(Object raw, int max, String message) {
        if (raw == null || StoreSupport.text(raw).isBlank()) return null;
        String value = StoreSupport.text(raw).trim();
        if (value.length() > max) StoreSupport.fail(400, message);
        return value;
    }

    private static Integer optionalInteger(Object raw, int min, int max, String message) {
        if (raw == null || StoreSupport.text(raw).isBlank()) return null;
        int value = StoreSupport.integer(raw, message);
        if (value < min || value > max) StoreSupport.fail(400, message);
        return value;
    }

    private static Double optionalDecimal(Object raw, double min, double max, String message) {
        if (raw == null || StoreSupport.text(raw).isBlank()) return null;
        double value = decimal(raw, message);
        if (value < min || value > max) StoreSupport.fail(400, message);
        return value;
    }

    private static Double decimal(Object raw, String message) {
        if (!(raw instanceof Number number)) StoreSupport.fail(400, message);
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value)) StoreSupport.fail(400, message);
        return value;
    }
}
