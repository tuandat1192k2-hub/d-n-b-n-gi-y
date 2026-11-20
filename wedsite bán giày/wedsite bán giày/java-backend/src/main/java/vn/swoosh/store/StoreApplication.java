package vn.swoosh.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(StoreApplication.StoreProperties.class)
public class StoreApplication {
    public static void main(String[] args) {
        loadSqlServerNativeAuthentication();
        if (args.length > 0 && "create-admin".equals(args[0])) {
            createAdmin(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(StoreApplication.class, args);
    }

    private static void loadSqlServerNativeAuthentication() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) return;
        String rootSetting = System.getenv("SWOOSH_ROOT");
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path root = rootSetting == null || rootSetting.isBlank()
            ? ("java-backend".equals(workingDirectory.getFileName().toString())
                ? workingDirectory.getParent() : workingDirectory)
            : Path.of(rootSetting).toAbsolutePath();
        Path library = root.resolve("java-backend").resolve("target").resolve("native")
            .resolve("mssql-jdbc_auth-12.8.1.x64.dll");
        if (Files.isRegularFile(library)) System.load(library.toAbsolutePath().toString());
    }

    private static void createAdmin(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("Không truyền thông tin đăng nhập qua tham số dòng lệnh.");
        }
        java.io.Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Lệnh tạo Admin cần terminal tương tác để nhập thông tin an toàn.");
        }
        SpringApplication application = new SpringApplication(StoreApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run()) {
            String username = console.readLine("Tên đăng nhập Admin: ").trim();
            if (!Pattern.matches("[A-Za-z0-9._-]{3,40}", username)) {
                throw new IllegalArgumentException("Tên đăng nhập phải có 3–40 ký tự chữ, số, dấu chấm, gạch ngang hoặc gạch dưới.");
            }
            char[] password = console.readPassword("Mật khẩu (ít nhất 12 ký tự): ");
            char[] confirmation = console.readPassword("Nhập lại mật khẩu: ");
            try {
                if (password == null || password.length < 12 || password.length > 256) {
                    throw new IllegalArgumentException("Mật khẩu phải có từ 12 đến 256 ký tự.");
                }
                if (!Arrays.equals(password, confirmation)) {
                    throw new IllegalArgumentException("Mật khẩu xác nhận không khớp.");
                }
                JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                if (!jdbc.queryForList("SELECT 1 FROM users WHERE username=?", username).isEmpty()) {
                    throw new IllegalArgumentException("Tên đăng nhập đã tồn tại.");
                }
                CredentialService credentials = context.getBean(CredentialService.class);
                jdbc.update("""
                    INSERT INTO users(username,password_hash,full_name,status,created_at,updated_at)
                    VALUES(?,?,?,'ACTIVE',SYSUTCDATETIME(),SYSUTCDATETIME())
                    """, username, credentials.hash(new String(password)), username);
                Integer userId = jdbc.queryForObject(
                    "SELECT user_id FROM users WHERE username=?", Integer.class, username);
                jdbc.update("""
                    INSERT INTO user_roles(user_id,role_id,assigned_at)
                    SELECT ?,role_id,SYSUTCDATETIME() FROM roles WHERE role_code='ADMIN'
                    """, userId);
                System.out.println("Đã tạo tài khoản Admin.");
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (confirmation != null) Arrays.fill(confirmation, '\0');
            }
        }
    }

    @Bean
    DataSource dataSource(StoreProperties properties) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        source.setUrl(properties.jdbcUrl());
        return source;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource source) {
        return new DataSourceTransactionManager(source);
    }

    @Bean
    ApplicationRunner initializeDatabase(StoreDatabase database) {
        return args -> database.initialize();
    }

    @ConfigurationProperties(prefix = "swoosh")
    public record StoreProperties(Path root, String jdbcUrl, boolean cookieSecure, boolean trustProxy) {
    }
}
