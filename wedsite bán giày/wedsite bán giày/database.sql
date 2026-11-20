-- ============================================================================
-- DATABASE CREATION: WEBSITE BÁN GIÀY CHẠY BỘ DÀNH CHO NGƯỜI MỚI BẮT ĐẦU (54 BẢNG)
-- Target RDBMS: SQL Server 2016+ / Azure SQL Database
-- ============================================================================

CREATE DATABASE [BeginnerRunningShoesDB];
GO

USE [BeginnerRunningShoesDB];
GO

-- ============================================================================
-- PHÂN HỆ 1: NGƯỜI DÙNG, ĐỊA CHỈ & PHÂN QUYỀN (USER & RBAC) - 6 BẢNG
-- ============================================================================

-- 1. Bảng người dùng chung (Khách hàng & Nhân viên)
CREATE TABLE [users] (
    [user_id] INT IDENTITY(1,1) PRIMARY KEY,
    [email] NVARCHAR(255) NULL UNIQUE,
    [phone_number] VARCHAR(20) NULL UNIQUE,
    [password_hash] NVARCHAR(255) NOT NULL,
    [full_name] NVARCHAR(100) NOT NULL,
    [avatar_url] NVARCHAR(500) NULL,
    [status] NVARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK ([status] IN ('ACTIVE', 'INACTIVE', 'LOCKED')),
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE(),
    [updated_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 2. Xác thực OTP Email / SĐT
CREATE TABLE [user_verifications] (
    [verification_id] INT IDENTITY(1,1) PRIMARY KEY,
    [user_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]) ON DELETE CASCADE,
    [type] NVARCHAR(20) NOT NULL CHECK ([type] IN ('EMAIL', 'PHONE', 'RECOVERY')),
    [code] VARCHAR(10) NOT NULL,
    [expires_at] DATETIME2 NOT NULL,
    [is_verified] BIT NOT NULL DEFAULT 0
);

-- 3. Sổ địa chỉ giao hàng của khách
CREATE TABLE [shipping_addresses] (
    [address_id] INT IDENTITY(1,1) PRIMARY KEY,
    [user_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]) ON DELETE CASCADE,
    [recipient_name] NVARCHAR(100) NOT NULL,
    [phone] VARCHAR(20) NOT NULL,
    [province] NVARCHAR(100) NOT NULL,
    [district] NVARCHAR(100) NOT NULL,
    [ward] NVARCHAR(100) NOT NULL,
    [detailed_address] NVARCHAR(255) NOT NULL,
    [is_default] BIT NOT NULL DEFAULT 0
);

-- 4. Vai trò hệ thống
CREATE TABLE [roles] (
    [role_id] INT IDENTITY(1,1) PRIMARY KEY,
    [role_code] VARCHAR(50) NOT NULL UNIQUE, -- ADMIN, CONSULTANT, WAREHOUSE_STAFF, SALES_STAFF, CONTENT_STAFF
    [role_name] NVARCHAR(100) NOT NULL,
    [description] NVARCHAR(255) NULL
);

-- 5. Quyền chi tiết trong hệ thống
CREATE TABLE [permissions] (
    [permission_id] INT IDENTITY(1,1) PRIMARY KEY,
    [permission_code] VARCHAR(100) NOT NULL UNIQUE,
    [permission_name] NVARCHAR(100) NOT NULL,
    [module] VARCHAR(50) NOT NULL
);

-- 6. Gán vai trò cho người dùng
CREATE TABLE [user_roles] (
    [user_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]) ON DELETE CASCADE,
    [role_id] INT NOT NULL FOREIGN KEY REFERENCES [roles]([role_id]) ON DELETE CASCADE,
    [assigned_at] DATETIME2 NOT NULL DEFAULT GETDATE(),
    PRIMARY KEY ([user_id], [role_id])
);

-- ============================================================================
-- PHÂN HỆ 2: HỒ SƠ NGƯỜI CHẠY & CHỈ SỐ BÀN CHÂN (RUNNER PROFILES) - 5 BẢNG
-- ============================================================================

-- 7. Hồ sơ người chạy chi tiết (Phục vụ tư vấn cá nhân hóa)
CREATE TABLE [runner_profiles] (
    [profile_id] INT IDENTITY(1,1) PRIMARY KEY,
    [user_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]) ON DELETE CASCADE,
    [profile_name] NVARCHAR(100) NOT NULL DEFAULT N'Hồ sơ mặc định',
    [gender] NVARCHAR(10) NULL CHECK ([gender] IN (N'Nam', N'Nữ', N'Khác')),
    [age] INT NULL,
    [height_cm] DECIMAL(5,2) NULL,
    [weight_kg] DECIMAL(5,2) NOT NULL,
    [running_experience_months] INT DEFAULT 0,
    [runner_level] NVARCHAR(50) DEFAULT N'Beginner' CHECK ([runner_level] IN (N'Beginner', N'Intermediate', N'Advanced')),
    [running_goal] NVARCHAR(100) NULL, -- Giảm cân, Chạy 5K đầu tiên, Tập sức khỏe...
    [avg_session_distance_km] DECIMAL(5,2) NULL, -- Cự ly trung bình mỗi buổi
    [total_weekly_distance_km] DECIMAL(5,2) NULL, -- Tổng cự ly mỗi tuần
    [sessions_per_week] INT NULL, -- Số buổi mỗi tuần
    [avg_pace] VARCHAR(20) NULL, -- Pace trung bình (e.g. 6:30 min/km)
    [strike_pattern] NVARCHAR(50) NULL, -- Tiếp đất: Heels (Gót), Midfoot (Giữa), Forefoot (Mũi)
    [dominant_foot] NVARCHAR(10) NULL CHECK ([dominant_foot] IN (N'Trái', N'Phải')),
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE(),
    [updated_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 8. Lịch sử theo dõi cân nặng người chạy
CREATE TABLE [runner_weight_history] (
    [history_id] INT IDENTITY(1,1) PRIMARY KEY,
    [profile_id] INT NOT NULL FOREIGN KEY REFERENCES [runner_profiles]([profile_id]) ON DELETE CASCADE,
    [weight_kg] DECIMAL(5,2) NOT NULL,
    [recorded_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 9. Kích thước & Chỉ số bàn chân đo đạc
CREATE TABLE [runner_foot_measurements] (
    [measurement_id] INT IDENTITY(1,1) PRIMARY KEY,
    [profile_id] INT NOT NULL UNIQUE FOREIGN KEY REFERENCES [runner_profiles]([profile_id]) ON DELETE CASCADE,
    [foot_length_mm] DECIMAL(5,1) NOT NULL, -- Chiều dài bàn chân (mm)
    [foot_width_mm] DECIMAL(5,1) NOT NULL,  -- Chiều rộng bàn chân (mm)
    [arch_type] NVARCHAR(50) NOT NULL,      -- Normal Arch, Low Arch, High Arch, Flat Feet
    [forefoot_width_type] NVARCHAR(50) NULL, -- Standard, Wide, Extra Wide
    [heel_width_type] NVARCHAR(50) NULL,     -- Narrow, Standard, Wide
    [measured_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 10. Nhật ký thay đổi hồ sơ người chạy
CREATE TABLE [runner_profile_logs] (
    [log_id] INT IDENTITY(1,1) PRIMARY KEY,
    [profile_id] INT NOT NULL FOREIGN KEY REFERENCES [runner_profiles]([profile_id]) ON DELETE CASCADE,
    [field_changed] NVARCHAR(100) NOT NULL,
    [old_value] NVARCHAR(255) NULL,
    [new_value] NVARCHAR(255) NULL,
    [changed_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 11. Lưu danh sách nhiều hồ sơ người chạy (Gia đình, Bạn bè)
CREATE TABLE [runner_saved_profiles] (
    [saved_id] INT IDENTITY(1,1) PRIMARY KEY,
    [owner_user_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]),
    [profile_id] INT NOT NULL FOREIGN KEY REFERENCES [runner_profiles]([profile_id]) ON DELETE NO ACTION,
    [relationship] NVARCHAR(50) DEFAULT N'Bản thân',
    CONSTRAINT [UQ_owner_profile] UNIQUE([owner_user_id], [profile_id])
);

-- ============================================================================
-- PHÂN HỆ 3: DANH MỤC & TIÊU CHÍ TƯ VẤN CHUẨN HÓA - 5 BẢNG
-- ============================================================================

-- 12. Danh mục Phân loại Nhóm cân nặng
CREATE TABLE [weight_groups] (
    [weight_group_id] INT IDENTITY(1,1) PRIMARY KEY,
    [group_name] NVARCHAR(100) NOT NULL, -- Nhẹ (<55kg), Vừa (55-75kg), Nặng (>75kg)
    [min_weight_kg] DECIMAL(5,2) NOT NULL,
    [max_weight_kg] DECIMAL(5,2) NOT NULL,
    [cushion_requirement_level] NVARCHAR(100) NOT NULL -- Yêu cầu đệm giảm chấn
);

-- 13. Danh mục Phân loại Cự ly chạy
CREATE TABLE [running_distances] (
    [distance_id] INT IDENTITY(1,1) PRIMARY KEY,
    [distance_code] VARCHAR(50) NOT NULL UNIQUE, -- UNDER_3K, 3_5K, 5_10K, 10_21K, OVER_21K
    [distance_label] NVARCHAR(100) NOT NULL,
    [min_km] DECIMAL(4,1) NOT NULL,
    [max_km] DECIMAL(4,1) NOT NULL,
    [purpose_type] NVARCHAR(100) NOT NULL -- Chạy ngắn, Chạy trung bình, Chạy dài
);

-- 14. Danh mục Địa hình chạy
CREATE TABLE [terrain_types] (
    [terrain_id] INT IDENTITY(1,1) PRIMARY KEY,
    [terrain_code] VARCHAR(50) NOT NULL UNIQUE, -- ROAD, SIDEWALK, TREADMILL, DIRT, TRAIL, MIXED
    [terrain_name] NVARCHAR(100) NOT NULL,
    [grip_requirement] NVARCHAR(100) NULL, -- Yêu cầu độ bám đế
    [durability_requirement] NVARCHAR(100) NULL
);

-- 15. Danh mục Kiểu bàn chân & Cấu trúc Vòm chân
CREATE TABLE [foot_arch_types] (
    [arch_type_id] INT IDENTITY(1,1) PRIMARY KEY,
    [arch_code] VARCHAR(50) NOT NULL UNIQUE, -- NORMAL_ARCH, LOW_ARCH, HIGH_ARCH, FLAT_FEET, WIDE_FEET, NARROW_FEET
    [arch_name] NVARCHAR(100) NOT NULL,
    [support_feature_needed] NVARCHAR(255) NOT NULL -- Cấu trúc hỗ trợ vòm / ổn định gót
);

-- 16. Danh mục Phân khúc Ngân sách
CREATE TABLE [budget_ranges] (
    [budget_id] INT IDENTITY(1,1) PRIMARY KEY,
    [budget_code] VARCHAR(50) NOT NULL UNIQUE, -- UNDER_1M, 1M_2M, 2M_3M, 3M_4M, OVER_4M
    [range_label] NVARCHAR(100) NOT NULL,
    [min_price] DECIMAL(18,2) NOT NULL,
    [max_price] DECIMAL(18,2) NOT NULL
);

-- ============================================================================
-- PHÂN HỆ 4: DANH MỤC GIÀY RUNNING & THÔNG SỐ CHI TIẾT - 6 BẢNG
-- ============================================================================

-- 17. Bảng Thương hiệu Giày (Nike, Adidas, Asics, Hoka, Saucony, Brooks, Mizuno...)
CREATE TABLE [brands] (
    [brand_id] INT IDENTITY(1,1) PRIMARY KEY,
    [brand_name] NVARCHAR(100) NOT NULL UNIQUE,
    [logo_url] NVARCHAR(500) NULL,
    [country] NVARCHAR(100) NULL
);

-- 18. Dòng sản phẩm Giày (Pegasus, Clifton, Nimbus, Novablast, Ghost...)
CREATE TABLE [product_series] (
    [series_id] INT IDENTITY(1,1) PRIMARY KEY,
    [brand_id] INT NOT NULL FOREIGN KEY REFERENCES [brands]([brand_id]),
    [series_name] NVARCHAR(100) NOT NULL,
    [description] NVARCHAR(MAX) NULL
);

-- 19. Bảng Giày chạy bộ (Master Product)
CREATE TABLE [running_shoes] (
    [shoe_id] INT IDENTITY(1,1) PRIMARY KEY,
    [shoe_code] VARCHAR(50) NOT NULL UNIQUE,
    [shoe_name] NVARCHAR(200) NOT NULL,
    [brand_id] INT NOT NULL FOREIGN KEY REFERENCES [brands]([brand_id]),
    [series_id] INT NULL FOREIGN KEY REFERENCES [product_series]([series_id]),
    [gender_target] NVARCHAR(20) NOT NULL CHECK ([gender_target] IN (N'Nam', N'Nữ', N'Unisex')),
    [base_price] DECIMAL(18,2) NOT NULL,
    [sale_price] DECIMAL(18,2) NULL,
    [weight_gram] DECIMAL(5,1) NOT NULL,              -- Trọng lượng giày (g)
    [midsole_thickness_mm] DECIMAL(4,1) NOT NULL,    -- Độ dày đế giữa (mm)
    [heel_to_toe_drop_mm] DECIMAL(4,1) NOT NULL,     -- Độ drop gót - mũi (mm)
    [cushion_level] NVARCHAR(50) NOT NULL,           -- Soft, Medium, Firm (Độ êm)
    [stability_level] NVARCHAR(50) NOT NULL,         -- Neutral, Stability, Motion Control
    [upper_material] NVARCHAR(100) NULL,             -- Engineered Mesh, Flyknit...
    [outsole_durability_score] INT DEFAULT 8,        -- Độ bền đế ngoài (1-10)
    [energy_return_percent] DECIMAL(5,2) NULL,       -- Khả năng hoàn trả năng lượng (%)
    [waterproof_capability] BIT DEFAULT 0,           -- Chống nước (Gore-Tex)
    [suitable_weight_group_id] INT NULL FOREIGN KEY REFERENCES [weight_groups]([weight_group_id]),
    [suitable_distance_id] INT NULL FOREIGN KEY REFERENCES [running_distances]([distance_id]),
    [suitable_arch_id] INT NULL FOREIGN KEY REFERENCES [foot_arch_types]([arch_type_id]),
    [is_beginner_friendly] BIT NOT NULL DEFAULT 1,   -- Tối ưu cho người mới bắt đầu
    [status] NVARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK ([status] IN ('ACTIVE', 'DISCONTINUED', 'HIDDEN')),
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 20. Bảng Công nghệ Đệm & Đế giày (ZoomX, Lightstrike, FF Blast, EVA...)
CREATE TABLE [shoe_technologies] (
    [tech_id] INT IDENTITY(1,1) PRIMARY KEY,
    [tech_name] NVARCHAR(100) NOT NULL UNIQUE,
    [tech_type] NVARCHAR(50) NOT NULL, -- MIDSOLE, OUTSOLE, UPPER, STABILITY
    [description] NVARCHAR(MAX) NULL
);

-- 21. Bảng gán Công nghệ cho từng Mẫu giày
CREATE TABLE [shoe_tech_mappings] (
    [shoe_id] INT NOT NULL FOREIGN KEY REFERENCES [running_shoes]([shoe_id]) ON DELETE CASCADE,
    [tech_id] INT NOT NULL FOREIGN KEY REFERENCES [shoe_technologies]([tech_id]) ON DELETE CASCADE,
    PRIMARY KEY ([shoe_id], [tech_id])
);

-- 22. Bảng gán Địa hình phù hợp cho từng Giày
CREATE TABLE [shoe_suitable_terrains] (
    [shoe_id] INT NOT NULL FOREIGN KEY REFERENCES [running_shoes]([shoe_id]) ON DELETE CASCADE,
    [terrain_id] INT NOT NULL FOREIGN KEY REFERENCES [terrain_types]([terrain_id]) ON DELETE CASCADE,
    [suitability_score] INT DEFAULT 5, -- Thang điểm phù hợp 1-5
    PRIMARY KEY ([shoe_id], [terrain_id])
);

-- ============================================================================
-- PHÂN HỆ 5: BIẾN THỂ GIÀY, BẢNG SIZE & MÀU SẮC - 4 BẢNG
-- ============================================================================

-- 23. Bảng Màu sắc Giày
CREATE TABLE [shoe_colors] (
    [color_id] INT IDENTITY(1,1) PRIMARY KEY,
    [color_name] NVARCHAR(50) NOT NULL,
    [hex_code] VARCHAR(10) NULL
);

-- 24. Bảng Kích thước Giày (Size US, UK, EU, CM)
CREATE TABLE [size_charts] (
    [size_id] INT IDENTITY(1,1) PRIMARY KEY,
    [size_eu] DECIMAL(3,1) NOT NULL UNIQUE, -- 38, 38.5, 39, 40, 41, 42, 43, 44...
    [size_us] DECIMAL(3,1) NULL,
    [size_cm] DECIMAL(4,1) NOT NULL          -- Chiều dài chân tương ứng (cm)
);

-- 25. Biến thể Giày thực tế (SKU = Shoe + Color + Size)
CREATE TABLE [shoe_variants] (
    [variant_id] INT IDENTITY(1,1) PRIMARY KEY,
    [shoe_id] INT NOT NULL FOREIGN KEY REFERENCES [running_shoes]([shoe_id]) ON DELETE CASCADE,
    [color_id] INT NOT NULL FOREIGN KEY REFERENCES [shoe_colors]([color_id]),
    [size_id] INT NOT NULL FOREIGN KEY REFERENCES [size_charts]([size_id]),
    [width_option] NVARCHAR(20) DEFAULT N'Standard' CHECK ([width_option] IN (N'Narrow', N'Standard', N'Wide', N'Extra Wide')),
    [sku] VARCHAR(100) NOT NULL UNIQUE,
    [image_url] NVARCHAR(500) NULL,
    [is_active] BIT DEFAULT 1
);

-- 26. Danh sách Giày yêu thích của khách hàng
CREATE TABLE [user_wishlists] (
    [wishlist_id] INT IDENTITY(1,1) PRIMARY KEY,
    [user_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]) ON DELETE CASCADE,
    [shoe_id] INT NOT NULL FOREIGN KEY REFERENCES [running_shoes]([shoe_id]) ON DELETE CASCADE,
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE(),
    CONSTRAINT [UQ_user_shoe_wishlist] UNIQUE([user_id], [shoe_id])
);

-- ============================================================================
-- PHÂN HỆ 6: ENGINE TƯ VẤN, CHẤM ĐIỂM MATCH & SO SÁNH GIÀY - 5 BẢNG
-- ============================================================================

-- 27. Ma trận Quy tắc & Trọng số Tư vấn Giày
CREATE TABLE [recommendation_rules] (
    [rule_id] INT IDENTITY(1,1) PRIMARY KEY,
    [rule_name] NVARCHAR(150) NOT NULL,
    [weight_weight_percent] DECIMAL(5,2) DEFAULT 25.00,  -- Trọng số tiêu chí Cân nặng
    [distance_weight_percent] DECIMAL(5,2) DEFAULT 25.00,-- Trọng số tiêu chí Cự ly
    [terrain_weight_percent] DECIMAL(5,2) DEFAULT 20.00, -- Trọng số tiêu chí Địa hình
    [foot_arch_weight_percent] DECIMAL(5,2) DEFAULT 20.00,-- Trọng số tiêu chí Bàn chân
    [budget_weight_percent] DECIMAL(5,2) DEFAULT 10.00,  -- Trọng số tiêu chí Ngân sách
    [is_active] BIT DEFAULT 1
);

-- 28. Phiên tư vấn chọn giày của người dùng
CREATE TABLE [recommendation_sessions] (
    [session_id] INT IDENTITY(1,1) PRIMARY KEY,
    [user_id] INT NULL FOREIGN KEY REFERENCES [users]([user_id]) ON DELETE SET NULL,
    [profile_id] INT NULL FOREIGN KEY REFERENCES [runner_profiles]([profile_id]),
    [input_weight_kg] DECIMAL(5,2) NOT NULL,
    [selected_distance_id] INT NOT NULL FOREIGN KEY REFERENCES [running_distances]([distance_id]),
    [selected_terrain_id] INT NOT NULL FOREIGN KEY REFERENCES [terrain_types]([terrain_id]),
    [selected_arch_id] INT NOT NULL FOREIGN KEY REFERENCES [foot_arch_types]([arch_type_id]),
    [selected_budget_id] INT NOT NULL FOREIGN KEY REFERENCES [budget_ranges]([budget_id]),
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 29. Kết quả Đề xuất Giày & Lý do phù hợp (Match Results)
CREATE TABLE [recommendation_results] (
    [result_id] INT IDENTITY(1,1) PRIMARY KEY,
    [session_id] INT NOT NULL FOREIGN KEY REFERENCES [recommendation_sessions]([session_id]) ON DELETE CASCADE,
    [shoe_id] INT NOT NULL FOREIGN KEY REFERENCES [running_shoes]([shoe_id]),
    [match_score] DECIMAL(5,2) NOT NULL, -- Điểm phù hợp (0-100%)
    [rank_position] INT NOT NULL,
    [match_reason_weight] NVARCHAR(255) NULL, -- Lý do đáp ứng tiêu chí cân nặng
    [match_reason_distance] NVARCHAR(255) NULL,-- Lý do đáp ứng tiêu chí cự ly
    [match_reason_terrain] NVARCHAR(255) NULL, -- Lý do đáp ứng địa hình
    [match_reason_arch] NVARCHAR(255) NULL,    -- Lý do đáp ứng kiểu bàn chân
    [match_reason_budget] NVARCHAR(255) NULL,  -- Lý do đáp ứng ngân sách
    [unmatched_criteria_note] NVARCHAR(255) NULL -- Cảnh báo tiêu chí chưa tối ưu
);

-- 30. Danh sách các phiên So sánh Giày người dùng khởi tạo
CREATE TABLE [user_comparison_lists] (
    [comparison_id] INT IDENTITY(1,1) PRIMARY KEY,
    [user_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]) ON DELETE CASCADE,
    [title] NVARCHAR(100) DEFAULT N'Danh sách so sánh giày',
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 31. Chi tiết Mẫu giày trong bảng So sánh
CREATE TABLE [user_comparison_details] (
    [comparison_id] INT NOT NULL FOREIGN KEY REFERENCES [user_comparison_lists]([comparison_id]) ON DELETE CASCADE,
    [shoe_id] INT NOT NULL FOREIGN KEY REFERENCES [running_shoes]([shoe_id]) ON DELETE CASCADE,
    [added_at] DATETIME2 NOT NULL DEFAULT GETDATE(),
    PRIMARY KEY ([comparison_id], [shoe_id])
);

-- ============================================================================
-- PHÂN HỆ 7: QUẢN LÝ KHO, NHẬP/XUẤT & CẢNH BÁO TỒN SIZE - 4 BẢNG
-- ============================================================================

-- 32. Danh mục Kho hàng
CREATE TABLE [warehouses] (
    [warehouse_id] INT IDENTITY(1,1) PRIMARY KEY,
    [warehouse_name] NVARCHAR(100) NOT NULL,
    [address] NVARCHAR(255) NULL,
    [phone] VARCHAR(20) NULL
);

-- 33. Quản lý Tồn kho Giày theo Biến thể (SKU)
CREATE TABLE [inventory_stocks] (
    [stock_id] INT IDENTITY(1,1) PRIMARY KEY,
    [warehouse_id] INT NOT NULL FOREIGN KEY REFERENCES [warehouses]([warehouse_id]),
    [variant_id] INT NOT NULL FOREIGN KEY REFERENCES [shoe_variants]([variant_id]) ON DELETE CASCADE,
    [stock_quantity] INT NOT NULL DEFAULT 0,
    [reserved_quantity] INT NOT NULL DEFAULT 0,
    [updated_at] DATETIME2 NOT NULL DEFAULT GETDATE(),
    CONSTRAINT [UQ_warehouse_variant] UNIQUE([warehouse_id], [variant_id])
);

-- 34. Nhật ký Nhập/Xuất Kho Giày
CREATE TABLE [inventory_transactions] (
    [transaction_id] INT IDENTITY(1,1) PRIMARY KEY,
    [stock_id] INT NOT NULL FOREIGN KEY REFERENCES [inventory_stocks]([stock_id]),
    [type] VARCHAR(20) NOT NULL CHECK ([type] IN ('IMPORT', 'EXPORT_ORDER', 'EXCHANGE_RETURN', 'ADJUSTMENT')),
    [quantity] INT NOT NULL,
    [note] NVARCHAR(255) NULL,
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE(),
    [created_by] INT NULL FOREIGN KEY REFERENCES [users]([user_id])
);

-- 35. Cảnh báo Tồn kho sắp hết theo Size
CREATE TABLE [stock_alerts] (
    [alert_id] INT IDENTITY(1,1) PRIMARY KEY,
    [variant_id] INT NOT NULL FOREIGN KEY REFERENCES [shoe_variants]([variant_id]) ON DELETE CASCADE,
    [min_threshold] INT DEFAULT 3,
    [is_resolved] BIT DEFAULT 0,
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- ============================================================================
-- PHÂN HỆ 8: GIỎ HÀNG, ĐƠN ĐẶT HÀNG & VOUCHERS - 5 BẢNG
-- ============================================================================

-- 36. Giỏ hàng của khách
CREATE TABLE [cart_items] (
    [cart_id] INT IDENTITY(1,1) PRIMARY KEY,
    [user_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]) ON DELETE CASCADE,
    [variant_id] INT NOT NULL FOREIGN KEY REFERENCES [shoe_variants]([variant_id]),
    [quantity] INT NOT NULL DEFAULT 1,
    [added_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 37. Mã giảm giá / Voucher
CREATE TABLE [coupons] (
    [coupon_id] INT IDENTITY(1,1) PRIMARY KEY,
    [code] VARCHAR(50) NOT NULL UNIQUE,
    [discount_type] VARCHAR(10) NOT NULL CHECK ([discount_type] IN ('PERCENT', 'FIXED')),
    [discount_value] DECIMAL(18,2) NOT NULL,
    [min_order_value] DECIMAL(18,2) DEFAULT 0,
    [start_date] DATETIME2 NOT NULL,
    [end_date] DATETIME2 NOT NULL,
    [usage_limit] INT DEFAULT 100
);

-- 38. Đơn đặt hàng Giày
CREATE TABLE [orders] (
    [order_id] INT IDENTITY(1,1) PRIMARY KEY,
    [order_code] VARCHAR(50) NOT NULL UNIQUE,
    [user_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]),
    [recipient_name] NVARCHAR(100) NOT NULL,
    [recipient_phone] VARCHAR(20) NOT NULL,
    [shipping_address] NVARCHAR(255) NOT NULL,
    [shipping_fee] DECIMAL(18,2) DEFAULT 0,
    [subtotal_amount] DECIMAL(18,2) NOT NULL,
    [discount_amount] DECIMAL(18,2) DEFAULT 0,
    [final_amount] DECIMAL(18,2) NOT NULL,
    [order_status] NVARCHAR(30) NOT NULL DEFAULT 'PENDING'
        CHECK ([order_status] IN ('PENDING', 'CONFIRMED', 'PACKING', 'SHIPPING', 'DELIVERED', 'COMPLETED', 'CANCELLED')),
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 39. Chi tiết sản phẩm trong Đơn hàng
CREATE TABLE [order_items] (
    [order_item_id] INT IDENTITY(1,1) PRIMARY KEY,
    [order_id] INT NOT NULL FOREIGN KEY REFERENCES [orders]([order_id]) ON DELETE CASCADE,
    [variant_id] INT NOT NULL FOREIGN KEY REFERENCES [shoe_variants]([variant_id]),
    [quantity] INT NOT NULL DEFAULT 1,
    [unit_price] DECIMAL(18,2) NOT NULL
);

-- 40. Lịch sử Thay đổi Trạng thái Đơn hàng
CREATE TABLE [order_status_logs] (
    [log_id] INT IDENTITY(1,1) PRIMARY KEY,
    [order_id] INT NOT NULL FOREIGN KEY REFERENCES [orders]([order_id]) ON DELETE CASCADE,
    [old_status] NVARCHAR(30) NULL,
    [new_status] NVARCHAR(30) NOT NULL,
    [note] NVARCHAR(255) NULL,
    [changed_at] DATETIME2 NOT NULL DEFAULT GETDATE(),
    [changed_by] INT NULL FOREIGN KEY REFERENCES [users]([user_id])
);

-- ============================================================================
-- PHÂN HỆ 9: THANH TOÁN, ĐỔI SIZE & ĐỔI TRẢ HOÀN TIỀN - 4 BẢNG
-- ============================================================================

-- 41. Bảng Thanh toán Đơn hàng
CREATE TABLE [payments] (
    [payment_id] INT IDENTITY(1,1) PRIMARY KEY,
    [order_id] INT NOT NULL FOREIGN KEY REFERENCES [orders]([order_id]) ON DELETE CASCADE,
    [payment_method] VARCHAR(20) NOT NULL CHECK ([payment_method] IN ('COD', 'VNPAY', 'ZALOPAY', 'BANK_TRANSFER', 'MOMO')),
    [transaction_code] VARCHAR(100) NULL,
    [payment_status] VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK ([payment_status] IN ('PENDING', 'PAID', 'FAILED')),
    [paid_at] DATETIME2 NULL
);

-- 42. Yêu cầu Đổi Size / Đổi Màu Giày
CREATE TABLE [size_exchange_requests] (
    [exchange_id] INT IDENTITY(1,1) PRIMARY KEY,
    [order_id] INT NOT NULL FOREIGN KEY REFERENCES [orders]([order_id]),
    [old_variant_id] INT NOT NULL FOREIGN KEY REFERENCES [shoe_variants]([variant_id]),
    [new_variant_id] INT NOT NULL FOREIGN KEY REFERENCES [shoe_variants]([variant_id]),
    [reason] NVARCHAR(255) NOT NULL, -- Chật mũi, Rộng gót, Không vừa vặn...
    [status] NVARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK ([status] IN ('PENDING', 'APPROVED', 'SHIPPED', 'REJECTED')),
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 43. Yêu cầu Trả hàng & Hoàn tiền
CREATE TABLE [order_returns] (
    [return_id] INT IDENTITY(1,1) PRIMARY KEY,
    [order_id] INT NOT NULL FOREIGN KEY REFERENCES [orders]([order_id]),
    [user_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]),
    [reason] NVARCHAR(MAX) NOT NULL,
    [refund_amount] DECIMAL(18,2) NULL,
    [status] NVARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK ([status] IN ('PENDING', 'APPROVED', 'REFUNDED', 'REJECTED')),
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 44. Giao dịch Hoàn tiền
CREATE TABLE [refund_transactions] (
    [refund_trans_id] INT IDENTITY(1,1) PRIMARY KEY,
    [return_id] INT NOT NULL FOREIGN KEY REFERENCES [order_returns]([return_id]) ON DELETE CASCADE,
    [amount] DECIMAL(18,2) NOT NULL,
    [gateway_transaction_id] VARCHAR(100) NULL,
    [refunded_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- ============================================================================
-- PHÂN HỆ 10: ĐÁNH GIÁ GIÀY & NỘI DUNG HƯỚNG DẪN RUNNING - 5 BẢNG
-- ============================================================================

-- 45. Bảng Đánh giá Giày chạy bộ Chuyên sâu
CREATE TABLE [shoe_reviews] (
    [review_id] INT IDENTITY(1,1) PRIMARY KEY,
    [shoe_id] INT NOT NULL FOREIGN KEY REFERENCES [running_shoes]([shoe_id]),
    [user_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]),
    [order_id] INT NULL FOREIGN KEY REFERENCES [orders]([order_id]),
    [star_rating] INT NOT NULL CHECK ([star_rating] BETWEEN 1 AND 5),
    [cushion_rating] INT NULL CHECK ([cushion_rating] BETWEEN 1 AND 5),   -- Đánh giá độ êm
    [stability_rating] INT NULL CHECK ([stability_rating] BETWEEN 1 AND 5), -- Đánh giá độ ổn định
    [grip_rating] INT NULL CHECK ([grip_rating] BETWEEN 1 AND 5),      -- Đánh giá độ bám
    [breathability_rating] INT NULL CHECK ([breathability_rating] BETWEEN 1 AND 5), -- Độ thoáng khí
    [fit_true_to_size_rating] INT NULL CHECK ([fit_true_to_size_rating] BETWEEN 1 AND 5), -- Độ chuẩn size
    [forefoot_width_rating] INT NULL CHECK ([forefoot_width_rating] BETWEEN 1 AND 5), -- Độ rộng mũi
    [running_feeling_comment] NVARCHAR(MAX) NULL, -- Trải nghiệm cảm giác chạy thực tế
    [status] NVARCHAR(20) NOT NULL DEFAULT 'APPROVED' CHECK ([status] IN ('PENDING', 'APPROVED', 'REJECTED')),
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 46. Hình ảnh đính kèm Đánh giá giày
CREATE TABLE [review_media] (
    [media_id] INT IDENTITY(1,1) PRIMARY KEY,
    [review_id] INT NOT NULL FOREIGN KEY REFERENCES [shoe_reviews]([review_id]) ON DELETE CASCADE,
    [media_url] NVARCHAR(500) NOT NULL
);

-- 47. Khiếu nại từ khách hàng & Báo cáo đánh giá
CREATE TABLE [customer_complaints] (
    [complaint_id] INT IDENTITY(1,1) PRIMARY KEY,
    [user_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]),
    [shoe_id] INT NULL FOREIGN KEY REFERENCES [running_shoes]([shoe_id]),
    [order_id] INT NULL FOREIGN KEY REFERENCES [orders]([order_id]),
    [complaint_type] VARCHAR(50) NOT NULL,
    [content] NVARCHAR(MAX) NOT NULL,
    [status] NVARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK ([status] IN ('OPEN', 'PROCESSING', 'RESOLVED', 'REJECTED')),
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 48. Bài viết Hướng dẫn Chạy bộ cho Người mới bắt đầu (Blog Running)
CREATE TABLE [running_articles] (
    [article_id] INT IDENTITY(1,1) PRIMARY KEY,
    [title] NVARCHAR(255) NOT NULL,
    [slug] VARCHAR(255) NOT NULL UNIQUE,
    [category] NVARCHAR(100) NOT NULL, -- Hướng dẫn chọn giày, Kỹ thuật chạy, Khắc phục chấn thương...
    [content] NVARCHAR(MAX) NOT NULL,
    [thumbnail_url] NVARCHAR(500) NULL,
    [author_id] INT NULL FOREIGN KEY REFERENCES [users]([user_id]),
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE(),
    [status] NVARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' CHECK ([status] IN ('DRAFT', 'PUBLISHED'))
);

-- 49. Nội dung Hướng dẫn Đo Bàn chân & Chọn Size Giày
CREATE TABLE [guidance_contents] (
    [guidance_id] INT IDENTITY(1,1) PRIMARY KEY,
    [guide_type] VARCHAR(50) NOT NULL UNIQUE, -- HOW_TO_MEASURE_FOOT, HOW_TO_DETERMINE_ARCH, SHOE_CARE_TIPS
    [title] NVARCHAR(255) NOT NULL,
    [content_html] NVARCHAR(MAX) NOT NULL,
    [updated_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- ============================================================================
-- PHÂN HỆ 11: NHẬT KÝ THAO TÁC & BÁO CÁO THỐNG KÊ CHUYỂN ĐỔI - 5 BẢNG
-- ============================================================================

-- 50. Nhật ký Thao tác Nhân viên (Audit Log)
CREATE TABLE [staff_audit_logs] (
    [log_id] INT IDENTITY(1,1) PRIMARY KEY,
    [staff_id] INT NOT NULL FOREIGN KEY REFERENCES [users]([user_id]),
    [action] VARCHAR(50) NOT NULL,
    [target_table] VARCHAR(50) NOT NULL,
    [target_id] INT NULL,
    [description] NVARCHAR(MAX) NULL,
    [created_at] DATETIME2 NOT NULL DEFAULT GETDATE()
);

-- 51. Quản lý Banner & Slider Quảng cáo
CREATE TABLE [cms_banners] (
    [banner_id] INT IDENTITY(1,1) PRIMARY KEY,
    [title] NVARCHAR(150) NOT NULL,
    [image_url] NVARCHAR(500) NOT NULL,
    [target_url] NVARCHAR(500) NULL,
    [display_order] INT DEFAULT 0,
    [is_active] BIT DEFAULT 1
);

-- 52. Thống kê Doanh thu Bán hàng theo Ngày & Thương hiệu
CREATE TABLE [daily_sales_analytics] (
    [stat_id] INT IDENTITY(1,1) PRIMARY KEY,
    [stat_date] DATE NOT NULL,
    [brand_id] INT NOT NULL FOREIGN KEY REFERENCES [brands]([brand_id]),
    [total_shoes_sold] INT DEFAULT 0,
    [total_revenue] DECIMAL(18,2) DEFAULT 0,
    CONSTRAINT [UQ_date_brand] UNIQUE([stat_date], [brand_id])
);

-- 53. Thống kê Lượt sử dụng Chức năng Tư vấn & Mẫu giày được gợi ý nhiều nhất
CREATE TABLE [consultation_analytics] (
    [analytics_id] INT IDENTITY(1,1) PRIMARY KEY,
    [stat_date] DATE NOT NULL,
    [shoe_id] INT NOT NULL FOREIGN KEY REFERENCES [running_shoes]([shoe_id]),
    [recommended_count] INT DEFAULT 0,
    [clicked_count] INT DEFAULT 0,
    CONSTRAINT [UQ_date_shoe_consult] UNIQUE([stat_date], [shoe_id])
);

-- 54. Thống kê Tỷ lệ Chuyển đổi từ Tư vấn sang Đặt hàng (Consultation Conversion Rate)
CREATE TABLE [consultation_conversion_analytics] (
    [conversion_id] INT IDENTITY(1,1) PRIMARY KEY,
    [stat_date] DATE NOT NULL,
    [total_consultations] INT DEFAULT 0,
    [total_orders_from_consultation] INT DEFAULT 0,
    [conversion_rate_percent] DECIMAL(5,2) DEFAULT 0.00,
    CONSTRAINT [UQ_conversion_stat_date] UNIQUE([stat_date])
);
GO

-- ============================================================================
-- TẠO INDEXES TỐI ƯU HIỆU NĂNG TÌM KIẾM GIÀY & ENGINE TƯ VẤN
-- ============================================================================

-- Index lọc giày theo 5 tiêu chí tư vấn chính (Cân nặng, Cự ly, Bàn chân, Ngân sách)
CREATE INDEX IX_running_shoes_recommendation ON [running_shoes] (
    [suitable_weight_group_id], 
    [suitable_distance_id], 
    [suitable_arch_id], 
    [base_price], 
    [is_beginner_friendly]
);

-- Index cho SKU và Tra cứu Tồn kho theo Size
CREATE INDEX IX_shoe_variants_sku ON [shoe_variants] ([sku]);
CREATE INDEX IX_inventory_stocks_variant ON [inventory_stocks] ([variant_id], [stock_quantity]);

-- Index tìm kiếm nhanh theo Thương hiệu và Mã giày
CREATE INDEX IX_running_shoes_brand_code ON [running_shoes] ([brand_id], [shoe_code], [shoe_name]);

-- Index tra cứu Đơn hàng & Lịch sử Tư vấn người dùng
CREATE INDEX IX_orders_user_status ON [orders] ([user_id], [order_status]);
CREATE INDEX IX_recommendation_sessions_user ON [recommendation_sessions] ([user_id], [created_at]);
GO