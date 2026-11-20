#import <UIKit/UIKit.h>
#import <WebKit/WebKit.h>
#include <jni.h>

@interface CPWebPanel : NSObject <WKNavigationDelegate>
@property(nonatomic,strong) WKWebView *view;
@property(nonatomic,strong) NSURL *entry;
@property(atomic) BOOL failed;
@end

@implementation CPWebPanel
- (void)webView:(WKWebView *)view decidePolicyForNavigationAction:(WKNavigationAction *)action
 decisionHandler:(void (^)(WKNavigationActionPolicy))decision {
    NSURL *url = action.request.URL;
    BOOL local = action.targetFrame.isMainFrame && [url.scheme isEqual:self.entry.scheme]
        && [url.host isEqual:self.entry.host] && [url.port isEqual:self.entry.port]
        && [url.path isEqual:self.entry.path];
    decision(local ? WKNavigationActionPolicyAllow : WKNavigationActionPolicyCancel);
}
- (void)webView:(WKWebView *)view didFailProvisionalNavigation:(WKNavigation *)nav withError:(NSError *)error {
    if (error.code != NSURLErrorCancelled) self.failed = YES;
}
- (void)webView:(WKWebView *)view didFailNavigation:(WKNavigation *)nav withError:(NSError *)error {
    if (error.code != NSURLErrorCancelled) self.failed = YES;
}
- (void)webViewWebContentProcessDidTerminate:(WKWebView *)view { self.failed = YES; }
@end

static NSMutableDictionary<NSNumber *,CPWebPanel *> *panels;
static UIView *gameView(void) {
    for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
        if (![scene isKindOfClass:UIWindowScene.class] || scene.activationState != UISceneActivationStateForegroundActive) continue;
        for (UIWindow *window in ((UIWindowScene *)scene).windows)
            if (window.isKeyWindow) return window.rootViewController.view;
    }
    return nil;
}
static NSString *fromJava(JNIEnv *env,jstring text) {
    const jchar *chars=(*env)->GetStringChars(env,text,NULL);
    if (!chars) return nil;
    NSString *s=[NSString stringWithCharacters:chars length:(NSUInteger)(*env)->GetStringLength(env,text)];
    (*env)->ReleaseStringChars(env,text,chars);
    return s;
}
static void onUI(dispatch_block_t task) {
    if (NSThread.isMainThread) task(); else dispatch_async(dispatch_get_main_queue(),task);
}
#define PANEL(name) Java_com_tonikelope_coronapoker_gdx_IOSWebPanel_##name
JNIEXPORT void JNICALL PANEL(display)(JNIEnv *env,jclass cls,jlong key,jstring entry,
        jfloat x,jfloat y,jfloat w,jfloat h,jfloat opacity) {
    NSURL *url=[NSURL URLWithString:fromJava(env,entry)];
    if (![url.scheme isEqual:@"http"] || ![url.host isEqual:@"127.0.0.1"] || !url.port) return;
    onUI(^{
        UIView *host=gameView();
        if (!host) return;
        CPWebPanel *panel;
        @synchronized(CPWebPanel.class) {
            if (!panels) panels=[NSMutableDictionary new];
            panel=panels[@(key)];
            if (!panel) {
                WKWebViewConfiguration *cfg=[WKWebViewConfiguration new];
                cfg.websiteDataStore=WKWebsiteDataStore.nonPersistentDataStore;
                panel=[CPWebPanel new]; panel.entry=url;
                panel.view=[[WKWebView alloc] initWithFrame:CGRectZero configuration:cfg];
                panel.view.navigationDelegate=panel;
                panels[@(key)]=panel;
                [host addSubview:panel.view];
                [panel.view loadRequest:[NSURLRequest requestWithURL:url]];
            }
        }
        CGSize size=host.bounds.size;
        panel.view.frame=CGRectMake(x*size.width,y*size.height,w*size.width,h*size.height);
        panel.view.alpha=opacity;
        panel.view.hidden=NO;
    });
}
JNIEXPORT void JNICALL PANEL(visible)(JNIEnv *env,jclass cls,jlong key,jboolean show) {
    onUI(^{ panels[@(key)].view.hidden=!show; });
}
JNIEXPORT void JNICALL PANEL(release)(JNIEnv *env,jclass cls,jlong key) {
    onUI(^{
        CPWebPanel *panel=panels[@(key)];
        [panel.view stopLoading]; [panel.view removeFromSuperview];
        @synchronized(CPWebPanel.class) { [panels removeObjectForKey:@(key)]; }
    });
}
JNIEXPORT jboolean JNICALL PANEL(loadFailed)(JNIEnv *env,jclass cls,jlong key) {
    @synchronized(CPWebPanel.class) { return panels[@(key)].failed; }
}
JNIEXPORT void JNICALL PANEL(evaluate)(JNIEnv *env,jclass cls,jlong key,jstring script) {
    NSString *js=fromJava(env,script);
    if (js) onUI(^{ [panels[@(key)].view evaluateJavaScript:js completionHandler:nil]; });
}

// The remote search page has its own ephemeral store and no access to the
// gallery's local origin, cookies, or game actions.
@interface CPSearchDocument : NSObject <WKNavigationDelegate>
@property(nonatomic,strong) WKWebView *web;
@property(nonatomic,strong) NSURL *target;
@property(atomic,copy) NSString *result;
@property(nonatomic) BOOL loaded;
@property(nonatomic) BOOL reading;
@end
@implementation CPSearchDocument
- (void)webView:(WKWebView *)web decidePolicyForNavigationAction:(WKNavigationAction *)action
 decisionHandler:(void (^)(WKNavigationActionPolicy))decision {
    NSURL *u=action.request.URL;
    BOOL local=action.targetFrame.isMainFrame && [u.scheme isEqual:@"https"] && [u.host isEqual:@"www.google.com"]
        && [u.path isEqual:@"/search"] && [u.query isEqual:self.target.query];
    if (!local && action.targetFrame.isMainFrame) {
        if ([u.host isEqual:@"consent.google.com"]) self.result=@"consent";
        else if ([u.path hasPrefix:@"/sorry/"]) self.result=@"unusual";
        else self.result=@"unavailable";
    }
    decision(local ? WKNavigationActionPolicyAllow:WKNavigationActionPolicyCancel);
}
- (void)webView:(WKWebView *)web decidePolicyForNavigationResponse:(WKNavigationResponse *)nav
 decisionHandler:(void (^)(WKNavigationResponsePolicy))decision {
    NSHTTPURLResponse *r=(NSHTTPURLResponse *)nav.response;
    if (nav.forMainFrame && [r isKindOfClass:NSHTTPURLResponse.class] && r.statusCode>=400) {
        self.result=r.statusCode==429 ? [@"rate:" stringByAppendingString:[r valueForHTTPHeaderField:@"Retry-After"] ?: @""] : @"unavailable";
        decision(WKNavigationResponsePolicyCancel); return;
    }
    decision(WKNavigationResponsePolicyAllow);
}
- (void)webView:(WKWebView *)web didFinishNavigation:(WKNavigation *)nav { self.loaded=YES; }
- (void)webView:(WKWebView *)web didFailProvisionalNavigation:(WKNavigation *)nav withError:(NSError *)e {
    if (e.code!=NSURLErrorCancelled) self.result=@"unavailable";
}
@end
static NSMutableDictionary<NSNumber *,CPSearchDocument *> *searches;
#define GALLERY(name) Java_com_tonikelope_coronapoker_gdx_PokerImageGalleryBrowser_##name
JNIEXPORT void JNICALL GALLERY(openSearch)(JNIEnv *env,jclass cls,jlong owner,jstring request) {
    NSURL *url=[NSURL URLWithString:fromJava(env,request)];
    if (![url.scheme isEqual:@"https"] || ![url.host isEqual:@"www.google.com"] || ![url.path isEqual:@"/search"]) return;
    onUI(^{
        CPSearchDocument *page=[CPSearchDocument new]; page.target=url;
        WKWebViewConfiguration *cfg=[WKWebViewConfiguration new]; cfg.websiteDataStore=WKWebsiteDataStore.nonPersistentDataStore;
        page.web=[[WKWebView alloc] initWithFrame:CGRectMake(0,0,1140,700) configuration:cfg];
        page.web.navigationDelegate=page;
        @synchronized(CPSearchDocument.class) {
            if (!searches) searches=[NSMutableDictionary new]; searches[@(owner)]=page;
        }
        [page.web loadRequest:[NSURLRequest requestWithURL:url]];
    });
}
JNIEXPORT jstring JNICALL GALLERY(searchDocument)(JNIEnv *env,jclass cls,jlong owner) {
    CPSearchDocument *page;
    @synchronized(CPSearchDocument.class) { page=searches[@(owner)]; }
    if (!page) return NULL;
    NSString *result=page.result;
    if (result) {
        if ([result hasPrefix:@"html:"]) page.result=nil;
        NSUInteger n=result.length;
        NSData *utf16=[result dataUsingEncoding:NSUTF16LittleEndianStringEncoding];
        return (*env)->NewString(env,utf16.bytes,(jsize)n);
    }
    onUI(^{
        if (page.reading || !page.loaded || page.result) return;
        page.reading=YES;
        [page.web evaluateJavaScript:@"(()=>{const html=document.documentElement.outerHTML;return html.length<=12582912?html:null})()"
               completionHandler:^(id value,NSError *error) {
            page.reading=NO;
            page.result=[value isKindOfClass:NSString.class] ? [@"html:" stringByAppendingString:value]:@"unavailable";
        }];
    });
    return NULL;
}
JNIEXPORT void JNICALL GALLERY(closeSearch)(JNIEnv *env,jclass cls,jlong owner) {
    onUI(^{
        [searches[@(owner)].web stopLoading];
        @synchronized(CPSearchDocument.class) { [searches removeObjectForKey:@(owner)]; }
    });
}
#import <ImageIO/ImageIO.h>
JNIEXPORT jbyteArray JNICALL GALLERY(thumbnail)(JNIEnv *env,jclass cls,jbyteArray bytes) {
    @autoreleasepool {
        jsize n=(*env)->GetArrayLength(env,bytes);
        NSMutableData *data=[NSMutableData dataWithLength:(NSUInteger)n];
        (*env)->GetByteArrayRegion(env,bytes,0,n,data.mutableBytes);
        if ((*env)->ExceptionCheck(env)) return NULL;
        CGImageSourceRef src=CGImageSourceCreateWithData((__bridge CFDataRef)data,NULL);
        if (!src) return NULL;
        NSDictionary *info=CFBridgingRelease(CGImageSourceCopyPropertiesAtIndex(src,0,NULL));
        CGImageRef img=NULL;
        if ([info[(__bridge NSString *)kCGImagePropertyPixelWidth] integerValue]<=16384 &&
            [info[(__bridge NSString *)kCGImagePropertyPixelHeight] integerValue]<=16384)
            img=CGImageSourceCreateThumbnailAtIndex(src,0,(__bridge CFDictionaryRef)@{
                (__bridge NSString *)kCGImageSourceCreateThumbnailFromImageAlways:@YES,
                (__bridge NSString *)kCGImageSourceThumbnailMaxPixelSize:@384,
                (__bridge NSString *)kCGImageSourceCreateThumbnailWithTransform:@YES});
        CFRelease(src);
        if (!img) return NULL;
        NSData *png=UIImagePNGRepresentation([UIImage imageWithCGImage:img]); CGImageRelease(img);
        if (!png.length) return NULL;
        jbyteArray result=(*env)->NewByteArray(env,(jsize)png.length);
        if (result) (*env)->SetByteArrayRegion(env,result,0,(jsize)png.length,png.bytes);
        return result;
    }
}
