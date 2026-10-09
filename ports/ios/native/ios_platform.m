#import <UIKit/UIKit.h>
#import <Security/Security.h>
#import <AVFoundation/AVFoundation.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#import <objc/message.h>
#include <jni.h>

static NSString *CPString(JNIEnv *env, jstring value) {
    if (!value) return nil;
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    NSString *result = [[NSString alloc] initWithCharacters:(const unichar *)chars length:(NSUInteger)(*env)->GetStringLength(env, value)];
    (*env)->ReleaseStringChars(env, value, chars);
    return result;
}
static void CPError(JNIEnv *env, NSString *message) {
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type) (*env)->ThrowNew(env, type, message.UTF8String ?: "Native platform error");
}
static jstring CPJavaString(JNIEnv *env, NSString *value) {
    if (!value) return NULL;
    NSUInteger size = value.length;
    if (size > INT32_MAX) { CPError(env, @"Native string exceeds Java limit"); return NULL; }
    unichar *characters = malloc((size ?: 1) * sizeof(unichar));
    if (!characters) { CPError(env, @"Unable to allocate native string"); return NULL; }
    [value getCharacters:characters range:NSMakeRange(0, size)];
    jstring result = (*env)->NewString(env, (const jchar *)characters, (jsize)size);
    free(characters);
    return result;
}
static jbyteArray CPBytes(JNIEnv *env, NSData *data) {
    if (!data) return NULL;
    if (data.length > INT32_MAX) { CPError(env, @"Native payload exceeds Java array limit"); return NULL; }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)data.length);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)data.length, data.bytes);
    return result;
}
static void CPMain(void (^operation)(void)) {
    if (NSThread.isMainThread) operation();
    else dispatch_sync(dispatch_get_main_queue(), operation);
}
static NSDictionary *CPIdentityQuery(NSString *account) {
    return @{(__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
             (__bridge id)kSecAttrService: @"com.tonikelope.coronapoker.ios.identity.v1",
             (__bridge id)kSecAttrAccount: account};
}
JNIEXPORT jbyteArray JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_readIdentity(JNIEnv *env, jclass type, jstring account) {
    @autoreleasepool {
        NSMutableDictionary *query = [CPIdentityQuery(CPString(env, account)) mutableCopy];
        query[(__bridge id)kSecReturnData] = @YES;
        query[(__bridge id)kSecMatchLimit] = (__bridge id)kSecMatchLimitOne;
        CFTypeRef value = NULL;
        OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &value);
        if (status == errSecItemNotFound) return NULL;
        if (status != errSecSuccess) { CPError(env, [NSString stringWithFormat:@"Keychain read failed (%d)", (int)status]); return NULL; }
        NSData *data = CFBridgingRelease(value);
        return CPBytes(env, data);
    }
}
JNIEXPORT jboolean JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_createIdentity(JNIEnv *env, jclass type, jstring account, jbyteArray bytes) {
    @autoreleasepool {
        if (!bytes) { CPError(env, @"Identity record is missing"); return JNI_FALSE; }
        jsize length = (*env)->GetArrayLength(env, bytes);
        if (length < 64 || length > 1024) { CPError(env, @"Invalid identity record size"); return JNI_FALSE; }
        jbyte *buffer = (*env)->GetByteArrayElements(env, bytes, NULL);
        NSData *data = [NSData dataWithBytes:buffer length:(NSUInteger)length];
        (*env)->ReleaseByteArrayElements(env, bytes, buffer, JNI_ABORT);
        NSMutableDictionary *query = [CPIdentityQuery(CPString(env, account)) mutableCopy];
        query[(__bridge id)kSecValueData] = data;
        query[(__bridge id)kSecAttrAccessible] = (__bridge id)kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly;
        OSStatus status = SecItemAdd((__bridge CFDictionaryRef)query, NULL);
        if (status == errSecDuplicateItem) return JNI_FALSE;
        if (status != errSecSuccess) { CPError(env, [NSString stringWithFormat:@"Keychain write failed (%d)", (int)status]); return JNI_FALSE; }
        return JNI_TRUE;
    }
}
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_protectDirectory(JNIEnv *env, jclass type, jstring value) {
    @autoreleasepool {
        NSString *path = CPString(env, value);
        NSError *error = nil;
        NSDictionary *attributes = @{NSFileProtectionKey: NSFileProtectionCompleteUntilFirstUserAuthentication};
        NSFileManager *manager = NSFileManager.defaultManager;
        if (![manager createDirectoryAtPath:path withIntermediateDirectories:YES attributes:attributes error:&error]
            || ![manager setAttributes:attributes ofItemAtPath:path error:&error]) { CPError(env, error.localizedDescription); return; }
        NSDirectoryEnumerator *entries = [manager enumeratorAtPath:path];
        for (NSString *entry in entries) {
            NSString *child = [path stringByAppendingPathComponent:entry];
            if (![manager setAttributes:attributes ofItemAtPath:child error:&error]) { CPError(env, error.localizedDescription); return; }
        }
    }
}

@interface CPImagePickerDelegate : NSObject <UIDocumentPickerDelegate>
@property(nonatomic, strong) dispatch_semaphore_t completion;
@property(nonatomic, strong) NSString *selectedPath;
@property(nonatomic, strong) NSError *failure;
@end
@implementation CPImagePickerDelegate
- (void)documentPicker:(UIDocumentPickerViewController *)controller didPickDocumentsAtURLs:(NSArray<NSURL *> *)urls {
    NSURL *url = urls.firstObject;
    if (url) {
        NSString *directory = [NSHomeDirectory() stringByAppendingPathComponent:@"Library/Application Support/ImportedAvatars"];
        NSError *error = nil;
        [NSFileManager.defaultManager createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:@{NSFileProtectionKey:NSFileProtectionCompleteUntilFirstUserAuthentication} error:&error];
        NSNumber *size = nil;
        [url getResourceValue:&size forKey:NSURLFileSizeKey error:&error];
        if (error || size.unsignedLongLongValue > 256 * 1024) {
            self.failure = error ?: [NSError errorWithDomain:@"CoronaPoker" code:1 userInfo:@{NSLocalizedDescriptionKey:@"Avatar exceeds 256 KiB limit"}];
        } else {
            NSString *filename = [NSString stringWithFormat:@"%@.%@", NSUUID.UUID.UUIDString, url.pathExtension];
            NSString *target = [directory stringByAppendingPathComponent:filename];
            if ([NSFileManager.defaultManager copyItemAtURL:url toURL:[NSURL fileURLWithPath:target] error:&error]) {
                [NSFileManager.defaultManager setAttributes:@{NSFileProtectionKey:NSFileProtectionCompleteUntilFirstUserAuthentication} ofItemAtPath:target error:&error];
                if (!error) self.selectedPath = target;
            }
            self.failure = error;
        }
    }
    dispatch_semaphore_signal(self.completion);
}
- (void)documentPickerWasCancelled:(UIDocumentPickerViewController *)controller {
    dispatch_semaphore_signal(self.completion);
}
@end
static UIViewController *CPRootController(void) {
    UIWindow *window = UIApplication.sharedApplication.keyWindow;
    if (!window) for (UIWindow *candidate in UIApplication.sharedApplication.windows) if (candidate.isKeyWindow) { window = candidate; break; }
    UIViewController *controller = window.rootViewController;
    while (controller.presentedViewController) controller = controller.presentedViewController;
    return controller;
}
JNIEXPORT jfloat JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_keyboardOcclusion(JNIEnv *env, jclass type) {
    static CGRect keyboard = {{0,0},{0,0}};
    static id changeObserver, hideObserver;
    static dispatch_once_t once;
    __block CGFloat fraction = 0;
    CPMain(^{
        dispatch_once(&once, ^{
            changeObserver = [NSNotificationCenter.defaultCenter addObserverForName:UIKeyboardWillChangeFrameNotification object:nil queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification *note) {
                keyboard = [note.userInfo[UIKeyboardFrameEndUserInfoKey] CGRectValue];
            }];
            hideObserver = [NSNotificationCenter.defaultCenter addObserverForName:UIKeyboardWillHideNotification object:nil queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification *note) {
                keyboard = CGRectZero;
            }];
        });
        UIView *view = UIApplication.sharedApplication.keyWindow.rootViewController.view;
        if (!view || CGRectIsEmpty(keyboard) || view.bounds.size.height <= 0) return;
        CGRect local = [view convertRect:keyboard fromView:nil];
        CGRect overlap = CGRectIntersection(view.bounds,local);
        // Floating keyboards do not cover the full bottom edge of the view.
        if (!CGRectIsNull(overlap) && overlap.size.width >= view.bounds.size.width * 0.8
            && fabs(CGRectGetMaxY(overlap)-CGRectGetMaxY(view.bounds)) < 2)
            fraction = overlap.size.height/view.bounds.size.height;
    });
    return (jfloat)fraction;
}
JNIEXPORT jfloatArray JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_safeAreaInsets(JNIEnv *env, jclass type) {
    __block UIEdgeInsets insets = UIEdgeInsetsZero;
    __block CGSize size = {1,1};
    CPMain(^{
        UIView *view = UIApplication.sharedApplication.keyWindow.rootViewController.view;
        if (!view || view.bounds.size.width <= 0 || view.bounds.size.height <= 0) return;
        insets = view.safeAreaInsets;
        size = view.bounds.size;
    });
    jfloat values[4] = {(jfloat)(insets.left/size.width),(jfloat)(insets.top/size.height),
        (jfloat)(insets.right/size.width),(jfloat)(insets.bottom/size.height)};
    jfloatArray result = (*env)->NewFloatArray(env,4);
    if (result) (*env)->SetFloatArrayRegion(env,result,0,4,values);
    return result;
}
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_setFrameRate(JNIEnv *env, jclass type, jint rate) {
    CPMain(^{
        UIViewController *controller = CPRootController();
        SEL setter = NSSelectorFromString(@"setPreferredFramesPerSecond:");
        if ([controller respondsToSelector:setter])
            ((void (*)(id, SEL, NSInteger))objc_msgSend)(controller, setter, MAX(1, MIN(120, rate)));
    });
}
JNIEXPORT jstring JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_selectImage(JNIEnv *env, jclass type) {
    @autoreleasepool {
        if (NSThread.isMainThread) { CPError(env, @"File selection must run on the background selection worker"); return NULL; }
        CPImagePickerDelegate *delegate = [CPImagePickerDelegate new];
        delegate.completion = dispatch_semaphore_create(0);
        __block UIDocumentPickerViewController *picker;
        CPMain(^{
            UIViewController *presenter = CPRootController();
            if (!presenter) { delegate.failure = [NSError errorWithDomain:@"CoronaPoker" code:2 userInfo:@{NSLocalizedDescriptionKey:@"No active game window"}]; dispatch_semaphore_signal(delegate.completion); return; }
            picker = [[UIDocumentPickerViewController alloc] initForOpeningContentTypes:@[UTTypePNG, UTTypeJPEG, UTTypeGIF] asCopy:YES];
            picker.allowsMultipleSelection = NO;
            picker.delegate = delegate;
            [presenter presentViewController:picker animated:YES completion:nil];
        });
        dispatch_semaphore_wait(delegate.completion, DISPATCH_TIME_FOREVER);
        if (delegate.failure) { CPError(env, delegate.failure.localizedDescription); return NULL; }
        return CPJavaString(env, delegate.selectedPath);
    }
}
JNIEXPORT jboolean JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_copyImage(JNIEnv *env, jclass type, jstring value) {
    @autoreleasepool {
        UIImage *image = [UIImage imageWithContentsOfFile:CPString(env, value)];
        if (!image) return JNI_FALSE;
        CPMain(^{ UIPasteboard.generalPasteboard.image = image; });
        return JNI_TRUE;
    }
}
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_idleTimerDisabled(JNIEnv *env, jclass type, jboolean disabled) {
    CPMain(^{ UIApplication.sharedApplication.idleTimerDisabled = disabled == JNI_TRUE; });
}

static AVAudioRecorder *CPRecorder;
static NSString *CPRecordingPath;
JNIEXPORT jboolean JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_startMicrophone(JNIEnv *env, jclass type, jstring input) {
    @autoreleasepool {
        if (NSThread.isMainThread) { CPError(env, @"Microphone setup must use the capture worker"); return JNI_FALSE; }
        AVAudioSession *session = AVAudioSession.sharedInstance;
        if (session.recordPermission == AVAudioSessionRecordPermissionUndetermined) {
            dispatch_semaphore_t permission = dispatch_semaphore_create(0);
            [session requestRecordPermission:^(BOOL granted) { dispatch_semaphore_signal(permission); }];
            dispatch_semaphore_wait(permission, DISPATCH_TIME_FOREVER);
        }
        if (session.recordPermission != AVAudioSessionRecordPermissionGranted) return JNI_FALSE;
        NSError *error = nil;
        if (![session setCategory:AVAudioSessionCategoryPlayAndRecord withOptions:AVAudioSessionCategoryOptionDefaultToSpeaker | AVAudioSessionCategoryOptionAllowBluetoothHFP error:&error]
            || ![session setActive:YES error:&error]) { CPError(env, error.localizedDescription); return JNI_FALSE; }
        NSString *uid = CPString(env, input);
        if (uid.length) for (AVAudioSessionPortDescription *port in session.availableInputs)
            if ([port.UID isEqualToString:uid]) { [session setPreferredInput:port error:&error]; break; }
        @synchronized(AVAudioRecorder.class) {
            if (CPRecorder) { CPError(env, @"Another voice note is recording"); return JNI_FALSE; }
            CPRecordingPath = [NSTemporaryDirectory() stringByAppendingPathComponent:[NSUUID.UUID.UUIDString stringByAppendingString:@".wav"]];
            NSDictionary *settings = @{AVFormatIDKey:@(kAudioFormatLinearPCM), AVSampleRateKey:@16000,
                AVNumberOfChannelsKey:@1, AVLinearPCMBitDepthKey:@16, AVLinearPCMIsBigEndianKey:@NO,
                AVLinearPCMIsFloatKey:@NO, AVLinearPCMIsNonInterleaved:@NO};
            CPRecorder = [[AVAudioRecorder alloc] initWithURL:[NSURL fileURLWithPath:CPRecordingPath] settings:settings error:&error];
            if (!CPRecorder || ![CPRecorder prepareToRecord] || ![CPRecorder recordForDuration:15]) {
                CPRecorder = nil; CPError(env, error.localizedDescription ?: @"Microphone capture could not start"); return JNI_FALSE;
            }
        }
        return JNI_TRUE;
    }
}
JNIEXPORT jbyteArray JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_stopMicrophone(JNIEnv *env, jclass type) {
    @autoreleasepool {
        NSData *wave;
        NSError *error = nil;
        @synchronized(AVAudioRecorder.class) {
            if (!CPRecorder) return NULL;
            [CPRecorder stop]; CPRecorder = nil;
            NSDictionary *attributes = [NSFileManager.defaultManager attributesOfItemAtPath:CPRecordingPath error:&error];
            if ([attributes[NSFileSize] unsignedLongLongValue] <= 512 * 1024)
                wave = [NSData dataWithContentsOfFile:CPRecordingPath options:0 error:&error];
            [NSFileManager.defaultManager removeItemAtPath:CPRecordingPath error:nil];
            CPRecordingPath = nil;
        }
        [AVAudioSession.sharedInstance setCategory:AVAudioSessionCategoryPlayback error:nil];
        [AVAudioSession.sharedInstance setActive:YES error:nil];
        if (error) { CPError(env, error.localizedDescription); return NULL; }
        return CPBytes(env, wave);
    }
}
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_abortMicrophone(JNIEnv *env, jclass type) {
    @autoreleasepool { @synchronized(AVAudioRecorder.class) {
        [CPRecorder stop]; CPRecorder = nil;
        if (CPRecordingPath) [NSFileManager.defaultManager removeItemAtPath:CPRecordingPath error:nil];
        CPRecordingPath = nil;
    }
    [AVAudioSession.sharedInstance setCategory:AVAudioSessionCategoryPlayback error:nil];
    }
}
JNIEXPORT jdouble JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_microphoneSeconds(JNIEnv *env, jclass type) {
    @synchronized(AVAudioRecorder.class) { return CPRecorder.currentTime; }
}
static jobjectArray CPPorts(JNIEnv *env, NSArray<AVAudioSessionPortDescription *> *ports) {
    jclass string = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)ports.count, string, NULL);
    for (NSUInteger index = 0; index < ports.count; index++) {
        NSString *entry = [NSString stringWithFormat:@"%@\t%@", ports[index].UID, ports[index].portName];
        jstring value = CPJavaString(env, entry);
        (*env)->SetObjectArrayElement(env, result, (jsize)index, value);
        (*env)->DeleteLocalRef(env, value);
    }
    return result;
}
JNIEXPORT jobjectArray JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_microphoneInputs(JNIEnv *env, jclass type) {
    @autoreleasepool { return CPPorts(env, AVAudioSession.sharedInstance.availableInputs ?: @[]); }
}
JNIEXPORT jobjectArray JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_audioOutputs(JNIEnv *env, jclass type) {
    @autoreleasepool { return CPPorts(env, AVAudioSession.sharedInstance.currentRoute.outputs ?: @[]); }
}
JNIEXPORT jboolean JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_useMicrophone(JNIEnv *env, jclass type, jstring value) {
    @autoreleasepool {
        AVAudioSession *session = AVAudioSession.sharedInstance;
        NSString *uid = CPString(env, value);
        if (!uid.length) return [session setPreferredInput:nil error:nil];
        for (AVAudioSessionPortDescription *port in session.availableInputs)
            if ([port.UID isEqualToString:uid]) return [session setPreferredInput:port error:nil];
        return JNI_FALSE;
    }
}
