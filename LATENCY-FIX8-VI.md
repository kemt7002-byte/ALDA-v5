# Fix 8 — bỏ bộ đệm 4 giây cũ

- Luồng dịch trực tiếp trong `AudioDubbingForegroundService` gửi mỗi khung PCM thu được ngay sang Gemini.
- Kích thước khung thu hiện tại là 1280 byte, tương đương khoảng 40 ms ở 16 kHz mono PCM16.
- 40 ms là kích thước khung đầu vào, không phải thời gian Gemini phản hồi hoặc độ trễ lồng tiếng đầu-cuối. Độ trễ còn phụ thuộc nhận dạng, mạng, xử lý mô hình và phát TTS/audio.
- Pipeline Hugging Face theo đoạn 4 giây đã không được dùng trong Fix 7; Fix 8 loại bỏ tệp pipeline cũ để tránh nhầm lẫn.
- Vì không tách giọng khỏi nhạc, Fix 8 không thể đồng thời loại lời thoại gốc mà giữ riêng nhạc nền.

Chưa có APK build hoặc kiểm thử trên thiết bị được xác nhận trong môi trường này.
