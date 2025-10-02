package timeUDP;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DbHelper {
    private static final String URL  =
        "jdbc:mysql://localhost:3306/udp_time?useSSL=false&serverTimezone=UTC";
    private static final String USER = "root";         
    private static final String PASS = "Hang.00000"; 

    static {
        try { 
            Class.forName("com.mysql.cj.jdbc.Driver"); 
        } catch (ClassNotFoundException e) { 
            throw new RuntimeException("Thiếu MySQL driver (mysql-connector-j).", e); 
        }
    }

    public static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    // Test trực tiếp
    public static void main(String[] args) {
        try (Connection cn = open()) {
            System.out.println("✅ Kết nối MySQL thành công!");
            System.out.println("Database hiện tại: " + cn.getCatalog());

            // Test query nhỏ
            try (Statement st = cn.createStatement();
            	     ResultSet rs = st.executeQuery("SELECT NOW() AS ts")) {
            	    if (rs.next()) {
            	        System.out.println("⏰ Giờ MySQL: " + rs.getString("ts"));
            	    }
            	}
        } catch (Exception e) {
            System.out.println("❌ Kết nối thất bại: " + e.getMessage());
            e.printStackTrace();
        }
    }
}