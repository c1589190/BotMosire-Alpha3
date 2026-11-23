package io.github.c1589190.bma3.qqofficial.shareqr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Pure Java QR PNG renderer backed by ZXing core and {@code java.desktop}.
 *
 * <p>The project's jlink image already contains {@code java.desktop}. ZXing's JavaSE helper module
 * is intentionally not used: only {@code zxing-core} is needed to produce the module, and tests
 * decode the PNG with core's {@code RGBLuminanceSource}.</p>
 */
public final class OfficialQqQrPngRenderer {

    public static final int DEFAULT_SIZE = 512;
    public static final int MIN_SIZE = 128;
    public static final int MAX_SIZE = 2048;
    public static final int MAX_CONTENT_LENGTH = 2048;

    private final int size;

    public OfficialQqQrPngRenderer() {
        this(DEFAULT_SIZE);
    }

    public OfficialQqQrPngRenderer(int size) {
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException("二维码 PNG 尺寸超出允许范围");
        }
        this.size = size;
    }

    /** Render one validated URL as a square RGB PNG. No text is placed in exceptions. */
    public byte[] renderPng(String content) throws OfficialQqShareQrException {
        if (content == null || content.isBlank() || content.length() > MAX_CONTENT_LENGTH) {
            throw renderFailure();
        }
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);

        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    content, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "PNG", png)) {
                throw renderFailure();
            }
            return png.toByteArray();
        } catch (WriterException | IOException | RuntimeException failure) {
            // Do not attach the cause: writer/image exceptions could contain image or URL text.
            throw renderFailure();
        }
    }

    private static OfficialQqShareQrException renderFailure() {
        return new OfficialQqShareQrException(
                OfficialQqShareQrException.QR_RENDER_FAILED,
                "无法生成二维码 PNG");
    }
}
