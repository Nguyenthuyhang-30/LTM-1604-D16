<h2 align="center">
    <a href="https://dainam.edu.vn/vi/khoa-cong-nghe-thong-tin">
    🎓 Faculty of Information Technology (DaiNam University)
    </a>
</h2>
<h2 align="center">
   Đồng hồ Server – Client (đồng bộ thời gian)
</h2>
<div align="center">
    <p align="center">
        <img src="docs/aiotlab_logo.png" alt="AIoTLab Logo" width="170"/>
        <img src="docs/fitdnu_logo.png" alt="AIoTLab Logo" width="180"/>
        <img src="docs/dnu_logo.png" alt="DaiNam University Logo" width="200"/>
    </p>

[![AIoTLab](https://img.shields.io/badge/AIoTLab-green?style=for-the-badge)](https://www.facebook.com/DNUAIoTLab)
[![Faculty of Information Technology](https://img.shields.io/badge/Faculty%20of%20Information%20Technology-blue?style=for-the-badge)](https://dainam.edu.vn/vi/khoa-cong-nghe-thong-tin)
[![DaiNam University](https://img.shields.io/badge/DaiNam%20University-orange?style=for-the-badge)](https://dainam.edu.vn)

</div>

## 📖 1. Giới thiệu hệ thống 

Hệ thống đồng bộ thời gian UDP được phát triển nhằm mục tiêu đồng bộ thời gian giữa client và server thông qua giao thức UDP.

🖥️ Server:

    ⏰ Lấy thời gian chuẩn từ Internet (HTTP hoặc NTP).

    📡 Phản hồi yêu cầu đồng bộ từ client qua UDP.

    🔍 Hỗ trợ broadcast DISCOVER để client tự động tìm server.

    🕒 Hiển thị đồng hồ số và đồng hồ analog.

💻 Client:

    📤 Gửi yêu cầu đồng bộ tới server.

    📋 Hiển thị bảng kết quả (Delay, Offset).

    📈 Vẽ biểu đồ delay/offset.

    🕒 Hiển thị đồng hồ số & đồng hồ analog dựa trên thời gian server.

    💾 Xuất dữ liệu CSV và lưu kết quả vào MySQL để phân tích.

## 2. Công nghệ sử dụng
    ☕ Java (JDK 8/11+) – Ngôn ngữ chính.

    🎨 Java Swing + Nimbus Look&Feel – Xây dựng giao diện.

    📡 UDP Socket – Giao tiếp Client–Server.

    🌐 HTTP/NTP – Lấy thời gian chuẩn từ Internet.

    🗄️ MySQL + JDBC (mysql-connector-j) – Lưu trữ dữ liệu.

    🛠️ IDE: Eclipse / IntelliJ IDEA / NetBeans.

## 3. Một số hình ảnh của hệ thống
## 4. Các bước cài đặt
🔧 Bước 1. Chuẩn bị môi trường

    Cài đặt JDK 8 hoặc 11 ☕.

    Cài đặt MySQL 8.x + Workbench 🗄️.

    Tạo database udp_time
🗄️ Bước 2. Tạo bảng trong MySQL

📦 Bước 3. Thêm thư viện JDBC

    Tải mysql-connector-j-8.x.x.jar.

    Copy vào thư mục lib/ của project → Add to Build Path.
⚙️ Bước 4. Cấu hình kết nối

    Trong DbHelper.java:

    public class DbHelper {
        private static final String URL = "jdbc:mysql://localhost:3306/udp_time";
        private static final String USER = "root";
        private static final String PASS = "your_password";

        public static Connection open() throws Exception {
            return DriverManager.getConnection(URL, USER, PASS);
        }
    }

▶️ Bước 5. Chạy hệ thống

    Chạy TimeServerGUI.java → nhấn Start Server 🟢.

    Chạy TimeClientGUI.java → nhập IP Server → nhấn Run 🚀.

    Quan sát Bảng kết quả, Biểu đồ, Đồng hồ.

    Kiểm tra dữ liệu trong MySQL Workbench:

        SELECT * FROM runs ORDER BY id DESC;
        SELECT * FROM samples WHERE run_id = <id>;
## 5. Liên hệ(cá nhân)