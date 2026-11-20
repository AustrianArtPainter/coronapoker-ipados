#import <UIKit/UIKit.h>
#import <WebKit/WebKit.h>
#include <jni.h>

// All view mutations run on the main queue. An obsolete runtime cannot dismiss
// a view that its successor has installed.
static WKWebView *player;
static NSString *currentID;
static jlong currentOwner;
static id backgroundObserver;

static void closePlayer(void) {
    [player stopLoading];
    [player removeFromSuperview];
    player = nil;
    currentID = nil;
    if (backgroundObserver) {
        [NSNotificationCenter.defaultCenter removeObserver:backgroundObserver];
        backgroundObserver = nil;
    }
}

JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSVideo_hide(
        JNIEnv *env, jclass cls, jlong owner) {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (owner == currentOwner) closePlayer();
    });
}

JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSVideo_show(
        JNIEnv *env, jclass cls, jlong owner, jstring video, jfloat x, jfloat y,
        jfloat width, jfloat height, jfloat opacity) {
    const char *bytes = (*env)->GetStringUTFChars(env, video, NULL);
    if (!bytes) return;
    NSString *videoID = [NSString stringWithUTF8String:bytes];
    (*env)->ReleaseStringUTFChars(env, video, bytes);
    if (videoID.length != 11 || [videoID rangeOfString:@"[^A-Za-z0-9_-]"
            options:NSRegularExpressionSearch].location != NSNotFound) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (owner < currentOwner || UIApplication.sharedApplication.applicationState != UIApplicationStateActive) return;
        UIView *host = nil;
        for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
            if (![scene isKindOfClass:UIWindowScene.class] || scene.activationState != UISceneActivationStateForegroundActive) continue;
            for (UIWindow *window in ((UIWindowScene *)scene).windows)
                if (window.isKeyWindow) { host = window.rootViewController.view; break; }
            if (host) break;
        }
        if (!host) return;
        if (owner != currentOwner) closePlayer();
        currentOwner = owner;
        if (!player) {
            WKWebViewConfiguration *config = [WKWebViewConfiguration new];
            config.allowsInlineMediaPlayback = YES;
            config.allowsPictureInPictureMediaPlayback = NO;
            player = [[WKWebView alloc] initWithFrame:CGRectZero configuration:config];
            player.scrollView.scrollEnabled = NO;
            [host addSubview:player];
            backgroundObserver = [NSNotificationCenter.defaultCenter addObserverForName:UIApplicationDidEnterBackgroundNotification
                    object:nil queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification *note) { closePlayer(); }];
        }
        CGSize size = host.bounds.size;
        player.frame = CGRectMake(x*size.width, y*size.height, width*size.width, height*size.height);
        player.alpha = opacity;
        if (![currentID isEqualToString:videoID]) {
            currentID = videoID;
            NSString *url = [NSString stringWithFormat:@"https://www.youtube.com/embed/%@?playsinline=1&mute=1", videoID];
            NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:url]];
            [request setValue:[@"https://" stringByAppendingString:NSBundle.mainBundle.bundleIdentifier.lowercaseString]
                    forHTTPHeaderField:@"Referer"];
            [player loadRequest:request];
        }
    });
}
