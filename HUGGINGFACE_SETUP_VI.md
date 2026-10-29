# Tích hợp Hugging Face tách lời thoại – ALAD-Mobile

## Phần đã thêm

- `huggingface-space/`: API FastAPI chạy mô hình Demucs thật, trả về hai stem WAV: `vocals` (lời thoại/giọng hát) và `instrumental` (nhạc nền/âm thanh còn lại).
- `HuggingFaceSeparationClient.kt`: client Android gọi `POST /separate`, nhận và giải mã hai stem. Client có timeout dài vì xử lý trên CPU không phải thời gian thực.

## Triển khai Space

1. Đăng nhập Hugging Face và tạo một **New Space**.
2. Chọn SDK **Docker**.
3. Tải toàn bộ nội dung thư mục `huggingface-space/` lên thư mục gốc của Space: `app.py`, `requirements.txt`, `Dockerfile`, `README.md`.
4. Chờ Space build; mở `https://TEN-SPACE.hf.space/health`. Kết quả đúng sẽ có `ok: true`.
5. API nhận file qua `POST https://TEN-SPACE.hf.space/separate`, multipart field tên `file`.

## Gọi từ Kotlin

```kotlin
val client = HuggingFaceSeparationClient(
    baseUrl = "https://TEN-SPACE.hf.space",
    bearerToken = null // chỉ thêm token nếu Space được bảo vệ; không hardcode token riêng tư
)
val stems = client.separate(audioFile)
// stems.vocalsWav: WAV lời thoại; stems.instrumentalWav: WAV nhạc nền/hiệu ứng
```

## Giới hạn quan trọng – không tuyên bố quá mức

- Đây là dịch vụ tách âm thanh thật bằng Demucs, không phải giao diện giả. Tuy nhiên, CPU miễn phí trên Hugging Face có thể ngủ, khởi động lạnh hoặc xử lý chậm; không bảo đảm đọc dịch trực tiếp theo thời gian thực.
- Trong dự án hiện tại, âm thanh được gửi từng khung 40 ms đến Gemini Live để dịch và tạo giọng Việt. Không thể gửi mỗi khung 40 ms riêng lẻ qua Demucs: mô hình cần đoạn âm thanh dài hơn và có độ trễ đáng kể. Client mới được thêm độc lập, **chưa được gắn vào luồng live** để tránh làm hỏng đồng bộ/âm thanh hoặc giả vờ rằng chức năng đã chạy.
- Muốn lồng tiếng live hoàn chỉnh cần xây bộ đệm theo đoạn, chuyển PCM thành WAV, gọi Space, tách stem, chuyển stem về PCM 16 kHz, đồng bộ và trộn với giọng Việt. Với Space CPU miễn phí, đây sẽ là lồng tiếng có độ trễ nhiều giây, không phải tức thời.
- Demucs tách theo nguồn âm thanh, không thể đảm bảo tách hoàn hảo mọi lời nói khỏi nhạc/hiệu ứng. Nếu bản thân video có lời thoại lẫn trong nhạc, một phần giọng có thể còn trong nhạc nền hoặc bị loại nhầm.

## Kiểm thử

Sau khi deploy, thử `/health` trước. Sau đó gửi một file WAV/MP3 ngắn đến `/separate`; phản hồi cần có `ok: true`, `vocals_wav_base64`, `instrumental_wav_base64`. Build Android cần chạy trên máy có Android SDK và tải được Gradle dependencies.

## Chế độ tách lời thoại theo đoạn khi video đang phát

Bản Fix 7 hiện tắt pipeline Hugging Face khỏi luồng dịch trực tiếp. Ứng dụng gửi các khung âm thanh thu được thẳng đến Gemini; mã nguồn tách theo đoạn 4 giây cũ đã được loại bỏ khỏi bản này. Vì không tách stem, âm thanh gốc vẫn do cài đặt phát media điều khiển.

## Dùng Space công khai abidlabs/music-separation

Bản cập nhật có thêm client Gradio API. Trong Cài đặt, nhập `https://huggingface.co/spaces/abidlabs/music-separation` hoặc `https://abidlabs-music-separation.hf.space`. Client sẽ tự chuyển URL trang sang host runtime, đọc cấu hình Gradio, tải đoạn âm thanh lên và gọi hàm tách âm thanh nếu Space công khai cho phép. Xem `GRADIO_PUBLIC_SPACE_VI.md`. Chưa xác minh được API thực tế từ môi trường hiện tại; nếu Space đổi schema hoặc chặn API, ứng dụng sẽ báo lỗi và cần điều chỉnh theo thông báo đó.
