# Sử dụng Python 3.12 slim tối ưu dung lượng và bảo mật
FROM python:3.12-slim

# Thiết lập biến môi trường
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONUTF8=1 \
    PORT=8000 \
    SWOOSH_HOST=0.0.0.0 \
    SWOOSH_DB=/app/data/swoosh.sqlite3

WORKDIR /app

# Tạo user không có đặc quyền root để tăng bảo mật
RUN groupadd -r appuser && useradd -r -g appuser appuser

# Copy toàn bộ mã nguồn
COPY . /app

# Tạo thư mục data và phân quyền cho appuser
RUN mkdir -p /app/data && chown -R appuser:appuser /app

# Chuyển sang user không phải root
USER appuser

# Khai báo volume lưu trữ cơ sở dữ liệu SQLite bền vững
VOLUME ["/app/data"]

# Mở cổng mặc định
EXPOSE 8000

# Health check định kỳ kiểm tra sức khỏe máy chủ
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request, os; port = os.environ.get('PORT', '8000'); urllib.request.urlopen(f'http://127.0.0.1:{port}/api/health')" || exit 1

# Khởi động máy chủ
CMD ["python", "store_server.py"]
