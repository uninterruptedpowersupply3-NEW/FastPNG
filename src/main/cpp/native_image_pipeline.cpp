#include <jni.h>

#include <android/bitmap.h>
#include <android/data_space.h>
#include <android/imagedecoder.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <unistd.h>
#include <vector>

#define API_AT_LEAST(x) __builtin_available(android x, *)
#define REQUIRES_API(x) __attribute__((__availability__(android, introduced = x)))

namespace {

constexpr int TARGET_FORMAT_JPEG = 0;
constexpr int TARGET_FORMAT_WEBP_LOSSY = 1;

constexpr int RESULT_SUCCESS = 0;
constexpr int RESULT_NOT_AVAILABLE = -1;
constexpr int RESULT_SOURCE_OPEN_FAILED = -2;
constexpr int RESULT_DECODER_CREATE_FAILED = -3;
constexpr int RESULT_DECODER_CONFIG_FAILED = -4;
constexpr int RESULT_DECODE_FAILED = -5;
constexpr int RESULT_OUTPUT_OPEN_FAILED = -6;
constexpr int RESULT_ENCODE_FAILED = -7;
constexpr int RESULT_UNSUPPORTED = -8;

constexpr const char* LOG_TAG = "FastPNGTOWEBPNative";

struct ScopedFd {
    int value = -1;

    explicit ScopedFd(int fd) : value(fd) {}
    ~ScopedFd() {
        if (value >= 0) {
            close(value);
        }
    }

    ScopedFd(const ScopedFd&) = delete;
    ScopedFd& operator=(const ScopedFd&) = delete;
};

struct ScopedDecoder {
    AImageDecoder* value = nullptr;

    explicit ScopedDecoder(AImageDecoder* decoder) : value(decoder) {}
    ~ScopedDecoder() {
        if (value != nullptr && API_AT_LEAST(30)) {
            AImageDecoder_delete(value);
        }
    }

    ScopedDecoder(const ScopedDecoder&) = delete;
    ScopedDecoder& operator=(const ScopedDecoder&) = delete;
};

struct WriteContext {
    int fd;
    bool failed;
};

void LogError(const char* message, int code) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s (%d)", message, code);
}

int BytesPerPixel(int32_t format) {
    switch (format) {
        case ANDROID_BITMAP_FORMAT_RGB_565:
            return 2;
        case ANDROID_BITMAP_FORMAT_RGBA_8888:
            return 4;
        default:
            return 0;
    }
}

int RotatedWidth(int width, int height, int rotationDegrees) {
    return (rotationDegrees == 90 || rotationDegrees == 270) ? height : width;
}

int RotatedHeight(int width, int height, int rotationDegrees) {
    return (rotationDegrees == 90 || rotationDegrees == 270) ? width : height;
}

bool WriteCallback(void* userContext, const void* data, size_t size) {
    auto* context = static_cast<WriteContext*>(userContext);
    const auto* bytes = static_cast<const uint8_t*>(data);
    size_t written = 0;

    while (written < size) {
        const ssize_t result = write(context->fd, bytes + written, size - written);
        if (result <= 0) {
            context->failed = true;
            return false;
        }
        written += static_cast<size_t>(result);
    }

    return true;
}

void FlattenRgba8888ToWhite(uint8_t* pixels, int width, int height, size_t stride) {
    for (int y = 0; y < height; ++y) {
        auto* row = pixels + static_cast<size_t>(y) * stride;
        for (int x = 0; x < width; ++x) {
            auto* pixel = row + x * 4;
            const uint8_t alpha = pixel[3];
            if (alpha == 255) {
                continue;
            }

            pixel[0] = static_cast<uint8_t>(std::min(255, pixel[0] + (255 - alpha)));
            pixel[1] = static_cast<uint8_t>(std::min(255, pixel[1] + (255 - alpha)));
            pixel[2] = static_cast<uint8_t>(std::min(255, pixel[2] + (255 - alpha)));
            pixel[3] = 255;
        }
    }
}

std::vector<uint8_t> TransformPixels(
    const uint8_t* src,
    int width,
    int height,
    size_t srcStride,
    int rotationDegrees,
    bool flipHorizontal,
    int bytesPerPixel
) {
    const int dstWidth = RotatedWidth(width, height, rotationDegrees);
    const int dstHeight = RotatedHeight(width, height, rotationDegrees);
    const size_t dstStride = static_cast<size_t>(dstWidth) * bytesPerPixel;
    std::vector<uint8_t> dst(static_cast<size_t>(dstHeight) * dstStride);

    for (int sy = 0; sy < height; ++sy) {
        const auto* srcRow = src + static_cast<size_t>(sy) * srcStride;
        for (int sx = 0; sx < width; ++sx) {
            const int fx = flipHorizontal ? (width - 1 - sx) : sx;
            const int fy = sy;

            int dx = fx;
            int dy = fy;
            switch (rotationDegrees) {
                case 90:
                    dx = height - 1 - fy;
                    dy = fx;
                    break;
                case 180:
                    dx = width - 1 - fx;
                    dy = height - 1 - fy;
                    break;
                case 270:
                    dx = fy;
                    dy = width - 1 - fx;
                    break;
                default:
                    break;
            }

            const auto* srcPixel = srcRow + static_cast<size_t>(sx) * bytesPerPixel;
            auto* dstPixel = dst.data() + static_cast<size_t>(dy) * dstStride + static_cast<size_t>(dx) * bytesPerPixel;
            std::memcpy(dstPixel, srcPixel, bytesPerPixel);
        }
    }

    return dst;
}

int NativeTranscodeFileImpl(
    const std::string& sourcePath,
    const std::string& outputPath,
    int targetFormat,
    int quality,
    int rotationDegrees,
    bool flipHorizontal
) REQUIRES_API(30);

int NativeTranscodeFileImpl(
    const std::string& sourcePath,
    const std::string& outputPath,
    int targetFormat,
    int quality,
    int rotationDegrees,
    bool flipHorizontal
) {
    const ScopedFd sourceFd(open(sourcePath.c_str(), O_RDONLY | O_CLOEXEC));
    if (sourceFd.value < 0) {
        return RESULT_SOURCE_OPEN_FAILED;
    }

    AImageDecoder* rawDecoder = nullptr;
    int decoderResult = AImageDecoder_createFromFd(sourceFd.value, &rawDecoder);
    if (decoderResult != ANDROID_IMAGE_DECODER_SUCCESS || rawDecoder == nullptr) {
        LogError("AImageDecoder_createFromFd failed", decoderResult);
        return RESULT_DECODER_CREATE_FAILED;
    }
    const ScopedDecoder decoder(rawDecoder);

    const AImageDecoderHeaderInfo* headerInfo = AImageDecoder_getHeaderInfo(decoder.value);
    if (headerInfo == nullptr) {
        return RESULT_DECODER_CONFIG_FAILED;
    }

    const int32_t headerAlphaFlags = AImageDecoderHeaderInfo_getAlphaFlags(headerInfo);
    const bool opaque = headerAlphaFlags == ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE;
    const bool flattenForJpeg = targetFormat == TARGET_FORMAT_JPEG && !opaque;

    int32_t bitmapFormat = ANDROID_BITMAP_FORMAT_RGBA_8888;
    if (targetFormat == TARGET_FORMAT_JPEG && opaque) {
        bitmapFormat = ANDROID_BITMAP_FORMAT_RGB_565;
    }

    decoderResult = AImageDecoder_setAndroidBitmapFormat(decoder.value, bitmapFormat);
    if (decoderResult != ANDROID_IMAGE_DECODER_SUCCESS) {
        LogError("AImageDecoder_setAndroidBitmapFormat failed", decoderResult);
        if (bitmapFormat != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            bitmapFormat = ANDROID_BITMAP_FORMAT_RGBA_8888;
            decoderResult = AImageDecoder_setAndroidBitmapFormat(decoder.value, bitmapFormat);
        }
    }
    if (decoderResult != ANDROID_IMAGE_DECODER_SUCCESS) {
        return RESULT_DECODER_CONFIG_FAILED;
    }

    const int width = AImageDecoderHeaderInfo_getWidth(headerInfo);
    const int height = AImageDecoderHeaderInfo_getHeight(headerInfo);
    const size_t stride = AImageDecoder_getMinimumStride(decoder.value);
    const size_t size = static_cast<size_t>(height) * stride;

    std::vector<uint8_t> pixels(size);
    decoderResult = AImageDecoder_decodeImage(decoder.value, pixels.data(), stride, size);
    if (decoderResult != ANDROID_IMAGE_DECODER_SUCCESS) {
        LogError("AImageDecoder_decodeImage failed", decoderResult);
        return RESULT_DECODE_FAILED;
    }

    const int bytesPerPixel = BytesPerPixel(bitmapFormat);
    if (bytesPerPixel == 0) {
        return RESULT_UNSUPPORTED;
    }

    AndroidBitmapInfo bitmapInfo{};
    bitmapInfo.width = static_cast<uint32_t>(width);
    bitmapInfo.height = static_cast<uint32_t>(height);
    bitmapInfo.stride = static_cast<uint32_t>(stride);
    bitmapInfo.format = bitmapFormat;
    bitmapInfo.flags = static_cast<uint32_t>(opaque ? ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE : ANDROID_BITMAP_FLAGS_ALPHA_PREMUL);

    uint8_t* compressPixels = pixels.data();
    std::vector<uint8_t> transformedPixels;
    if (flipHorizontal || rotationDegrees != 0) {
        transformedPixels = TransformPixels(
            pixels.data(),
            width,
            height,
            stride,
            rotationDegrees,
            flipHorizontal,
            bytesPerPixel
        );
        compressPixels = transformedPixels.data();
        bitmapInfo.width = static_cast<uint32_t>(RotatedWidth(width, height, rotationDegrees));
        bitmapInfo.height = static_cast<uint32_t>(RotatedHeight(width, height, rotationDegrees));
        bitmapInfo.stride = static_cast<uint32_t>(bitmapInfo.width * bytesPerPixel);
    }

    if (flattenForJpeg && bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        FlattenRgba8888ToWhite(
            compressPixels,
            static_cast<int>(bitmapInfo.width),
            static_cast<int>(bitmapInfo.height),
            bitmapInfo.stride
        );
        bitmapInfo.flags = ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE;
    }

    int32_t dataspace = AImageDecoderHeaderInfo_getDataSpace(headerInfo);
    if (dataspace == ADATASPACE_UNKNOWN) {
        dataspace = ADATASPACE_SRGB;
    }

    const ScopedFd outputFd(open(outputPath.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666));
    if (outputFd.value < 0) {
        return RESULT_OUTPUT_OPEN_FAILED;
    }

    WriteContext context{outputFd.value, false};
    const int32_t compressFormat = targetFormat == TARGET_FORMAT_JPEG
        ? ANDROID_BITMAP_COMPRESS_FORMAT_JPEG
        : ANDROID_BITMAP_COMPRESS_FORMAT_WEBP_LOSSY;
    const int compressResult = AndroidBitmap_compress(
        &bitmapInfo,
        dataspace,
        compressPixels,
        compressFormat,
        quality,
        &context,
        WriteCallback
    );

    if (compressResult != ANDROID_BITMAP_RESULT_SUCCESS || context.failed) {
        LogError("AndroidBitmap_compress failed", compressResult);
        return RESULT_ENCODE_FAILED;
    }

    return RESULT_SUCCESS;
}

}  // namespace

extern "C"
JNIEXPORT jint JNICALL
Java_org_nvmetools_fastpngtowebpandroid_NativeImagePipeline_nativeTranscodeFile(
    JNIEnv* env,
    jobject /* this */,
    jstring sourcePath,
    jstring outputPath,
    jint targetFormat,
    jint quality,
    jint rotationDegrees,
    jboolean flipHorizontal
) {
    if (!API_AT_LEAST(30)) {
        return RESULT_NOT_AVAILABLE;
    }

    const char* sourceChars = env->GetStringUTFChars(sourcePath, nullptr);
    const char* outputChars = env->GetStringUTFChars(outputPath, nullptr);
    if (sourceChars == nullptr || outputChars == nullptr) {
        if (sourceChars != nullptr) {
            env->ReleaseStringUTFChars(sourcePath, sourceChars);
        }
        if (outputChars != nullptr) {
            env->ReleaseStringUTFChars(outputPath, outputChars);
        }
        return RESULT_UNSUPPORTED;
    }

    const std::string source(sourceChars);
    const std::string output(outputChars);
    env->ReleaseStringUTFChars(sourcePath, sourceChars);
    env->ReleaseStringUTFChars(outputPath, outputChars);

    return NativeTranscodeFileImpl(
        source,
        output,
        static_cast<int>(targetFormat),
        static_cast<int>(quality),
        static_cast<int>(rotationDegrees),
        flipHorizontal == JNI_TRUE
    );
}
