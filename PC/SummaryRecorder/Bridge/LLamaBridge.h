#ifndef LLamaBridge_h
#define LLamaBridge_h

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface LLamaBridgeObjC : NSObject
- (nullable instancetype)initWithModelPath:(NSString *)modelPath error:(NSError **)error;
- (void)summarize:(NSString *)text
     systemPrompt:(NSString *)systemPrompt
       completion:(void (^)(NSString * _Nullable summary, NSError * _Nullable error))completion;
- (void)cancel;
@end

NS_ASSUME_NONNULL_END

#endif /* LLamaBridge_h */
