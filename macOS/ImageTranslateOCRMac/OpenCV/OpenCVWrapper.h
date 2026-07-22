#import <AppKit/AppKit.h>

NS_ASSUME_NONNULL_BEGIN

@interface OpenCVWrapper : NSObject
+ (BOOL)isAvailable;
+ (nullable CGImageRef)createInpaintedImage:(CGImageRef)image
                                    regions:(NSArray<NSValue *> *)regions
                                    precise:(BOOL)precise CF_RETURNS_RETAINED;
@end

NS_ASSUME_NONNULL_END
