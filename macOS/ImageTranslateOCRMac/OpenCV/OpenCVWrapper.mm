#import "OpenCVWrapper.h"

#if __has_include(<opencv2/opencv.hpp>)
#import <opencv2/opencv.hpp>
#import <opencv2/imgcodecs.hpp>
#import <opencv2/photo.hpp>
#define IMAGE_TRANSLATE_HAS_OPENCV 1
#else
#define IMAGE_TRANSLATE_HAS_OPENCV 0
#endif

@implementation OpenCVWrapper

+ (BOOL)isAvailable {
    return IMAGE_TRANSLATE_HAS_OPENCV == 1;
}

+ (CGImageRef)createInpaintedImage:(CGImageRef)cgImage
                           regions:(NSArray<NSValue *> *)regions
                           precise:(BOOL)precise {
#if IMAGE_TRANSLATE_HAS_OPENCV
    NSBitmapImageRep *inputRep = [[NSBitmapImageRep alloc] initWithCGImage:cgImage];
    NSData *inputData = [inputRep representationUsingType:NSBitmapImageFileTypePNG properties:@{}];
    if (inputData == nil) return CGImageRetain(cgImage);
    const uint8_t *inputBytes = static_cast<const uint8_t *>(inputData.bytes);
    std::vector<uint8_t> encodedInput(inputBytes, inputBytes + inputData.length);
    cv::Mat bgr = cv::imdecode(encodedInput, cv::IMREAD_COLOR);
    if (bgr.empty()) return CGImageRetain(cgImage);
    const int width = bgr.cols;
    const int height = bgr.rows;
    cv::Mat gray;
    cv::cvtColor(bgr, gray, cv::COLOR_BGR2GRAY);
    cv::Mat mask = cv::Mat::zeros(bgr.size(), CV_8UC1);

    for (NSValue *value in regions) {
        CGRect input = value.rectValue;
        int padding = precise ? 2 : 4;
        int left = std::max(0, (int)floor(CGRectGetMinX(input)) - padding);
        int top = std::max(0, (int)floor(CGRectGetMinY(input)) - padding);
        int right = std::min(width, (int)ceil(CGRectGetMaxX(input)) + padding);
        int bottom = std::min(height, (int)ceil(CGRectGetMaxY(input)) + padding);
        cv::Rect region(left, top, right - left, bottom - top);
        if (region.width <= 0 || region.height <= 0) continue;
        if (!precise) {
            cv::rectangle(mask, region, cv::Scalar(255), cv::FILLED);
            continue;
        }

        cv::Mat roiGray = gray(region);
        cv::Mat roiMask = mask(region);
        cv::Mat darkMask, lightMask;
        int blockSize = std::min(15, std::min(region.width, region.height));
        if (blockSize % 2 == 0) blockSize--;
        if (blockSize >= 3) {
            cv::adaptiveThreshold(roiGray, darkMask, 255, cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                                  cv::THRESH_BINARY_INV, blockSize, 4.0);
            cv::adaptiveThreshold(roiGray, lightMask, 255, cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                                  cv::THRESH_BINARY, blockSize, -4.0);
            double area = region.area();
            double darkRatio = cv::countNonZero(darkMask) / area;
            double lightRatio = cv::countNonZero(lightMask) / area;
            auto score = [](double ratio) {
                return ratio >= 0.015 && ratio <= 0.42 ? std::abs(ratio - 0.18) : DBL_MAX;
            };
            cv::Mat selected = score(lightRatio) < score(darkRatio) ? lightMask : darkMask;
            if (std::min(score(lightRatio), score(darkRatio)) != DBL_MAX) selected.copyTo(roiMask);
        } else {
            cv::rectangle(mask, region, cv::Scalar(255), cv::FILLED);
        }
    }
    if (precise) {
        cv::Mat kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3));
        cv::dilate(mask, mask, kernel);
    }

    cv::Mat repaired;
    cv::inpaint(bgr, mask, repaired, precise ? 2.0 : 4.0, cv::INPAINT_TELEA);
    std::vector<uint8_t> encodedOutput;
    if (!cv::imencode(".png", repaired, encodedOutput)) return CGImageRetain(cgImage);
    NSData *outputData = [NSData dataWithBytes:encodedOutput.data() length:encodedOutput.size()];
    NSBitmapImageRep *outputRep = [[NSBitmapImageRep alloc] initWithData:outputData];
    CGImageRef outputImage = outputRep.CGImage;
    return outputImage != NULL ? CGImageRetain(outputImage) : CGImageRetain(cgImage);
#else
    return CGImageRetain(cgImage);
#endif
}

@end
