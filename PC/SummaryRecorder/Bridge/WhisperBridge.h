#ifndef WhisperBridge_h
#define WhisperBridge_h

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface WhisperBridgeObjC : NSObject
- (nullable instancetype)initWithModelPath:(NSString *)modelPath error:(NSError **)error;
- (void)transcribeWav:(NSString *)wavPath
            completion:(void (^)(NSString * _Nullable text, NSError * _Nullable error))completion;
- (void)cancel;
@end

NS_ASSUME_NONNULL_END

#endif /* WhisperBridge_h */
