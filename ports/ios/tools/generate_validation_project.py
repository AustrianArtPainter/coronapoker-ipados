#!/usr/bin/env python3
"""Generate the small, dependency-free native background validation Xcode project."""
import hashlib
import plistlib
from pathlib import Path

PORT = Path(__file__).resolve().parents[1]
APP = PORT / "validation-host"
ROOT = PORT.parents[1]
NAME = "CoronaPokerHostValidation"
PROJECT = APP / (NAME + ".xcodeproj")


def ident(label):
    return hashlib.sha256(label.encode()).hexdigest()[:24].upper()


def main():
    PROJECT.mkdir(parents=True, exist_ok=True)
    info = {
        "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)", "CFBundleExecutable": "$(EXECUTABLE_NAME)",
        "CFBundleName": "$(PRODUCT_NAME)", "CFBundleDisplayName": "CP 主持验证",
        "CFBundlePackageType": "APPL", "CFBundleShortVersionString": "0.1", "CFBundleVersion": "1",
        "LSRequiresIPhoneOS": True, "UILaunchScreen": {}, "UIBackgroundModes": ["audio"],
        "NSLocalNetworkUsageDescription": "连接同一网络的电脑，验证本机锁屏后能否继续主持。",
        "UIApplicationSceneManifest": {"UIApplicationSupportsMultipleScenes": False,
            "UISceneConfigurations": {"UIWindowSceneSessionRoleApplication": [
                {"UISceneConfigurationName": "Host validation", "UISceneDelegateClassName": "$(PRODUCT_MODULE_NAME).SceneDelegate"}]}},
        "UISupportedInterfaceOrientations": ["UIInterfaceOrientationPortrait", "UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight"],
        "UISupportedInterfaceOrientations~ipad": ["UIInterfaceOrientationPortrait", "UIInterfaceOrientationPortraitUpsideDown", "UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight"],
        "ITSAppUsesNonExemptEncryption": False,
    }
    (APP / "Info.plist").write_bytes(plistlib.dumps(info))
    objects = []

    def add(label, body):
        objects.append(f"\t\t{ident(label)} = {{ {body} }};")
        return ident(label)

    sources = []
    references = []
    for name in ["App.swift", "HostProbe.swift"]:
        file_id = add(name, f'isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = "{name}"; sourceTree = "<group>";')
        references.append(file_id)
        sources.append(add(name + ":build", f"isa = PBXBuildFile; fileRef = {file_id};"))
    resource = str((ROOT / "src/main/resources/sounds/misc/background_music.mp3").relative_to(ROOT))
    # The relative reference is portable and points to the existing desktop asset.
    music_ref = add("music", f'isa = PBXFileReference; lastKnownFileType = audio.mp3; path = "../../../{resource}"; sourceTree = "<group>";')
    references.append(music_ref)
    music_build = add("music:build", f"isa = PBXBuildFile; fileRef = {music_ref};")
    product = add("product", f'isa = PBXFileReference; explicitFileType = wrapper.application; path = {NAME}.app; sourceTree = BUILT_PRODUCTS_DIR;')
    group = add("group", "isa = PBXGroup; children = (" + ",".join(references + [product]) + ',); sourceTree = "<group>";')
    source_phase = add("sources", "isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (" + ",".join(sources) + ",); runOnlyForDeploymentPostprocessing = 0;")
    resource_phase = add("resources", f"isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = ({music_build},); runOnlyForDeploymentPostprocessing = 0;")
    framework_phase = add("frameworks", "isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;")
    configs = []
    for config in ["Debug", "Release"]:
        configs.append(add(config, f'''isa = XCBuildConfiguration; name = {config}; buildSettings = {{
            SDKROOT = iphoneos; IPHONEOS_DEPLOYMENT_TARGET = 18.0; TARGETED_DEVICE_FAMILY = "1,2";
            PRODUCT_NAME = {NAME}; PRODUCT_BUNDLE_IDENTIFIER = com.tonikelope.coronapoker.ios.validation;
            INFOPLIST_FILE = Info.plist; GENERATE_INFOPLIST_FILE = NO; CODE_SIGN_STYLE = Automatic;
            DEVELOPMENT_TEAM = ""; SWIFT_VERSION = 5.0; CLANG_ENABLE_MODULES = YES;
            SWIFT_OPTIMIZATION_LEVEL = "{'-Onone' if config == 'Debug' else '-O'}";
            DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym"; ENABLE_BITCODE = NO;
            OTHER_LDFLAGS = "-lsqlite3"; }};'''))
    config_list = add("configs", "isa = XCConfigurationList; buildConfigurations = (" + ",".join(configs) + ",); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;")
    target = add("target", f"isa = PBXNativeTarget; buildConfigurationList = {config_list}; buildPhases = ({source_phase},{framework_phase},{resource_phase},); buildRules = (); dependencies = (); name = {NAME}; productName = {NAME}; productReference = {product}; productType = \"com.apple.product-type.application\";")
    project = add("project", f"isa = PBXProject; attributes = {{ LastUpgradeCheck = 2600; }}; buildConfigurationList = {config_list}; compatibilityVersion = \"Xcode 14.0\"; developmentRegion = en; knownRegions = (en,zh-Hans,Base,); mainGroup = {group}; projectDirPath = \"\"; projectRoot = \"\"; targets = ({target},);")
    (PROJECT / "project.pbxproj").write_text("// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {};\n\tobjectVersion = 56;\n\tobjects = {\n" + "\n".join(objects) + f"\n\t}};\n\trootObject = {project};\n}}\n")
    scheme_dir = PROJECT / "xcshareddata/xcschemes"
    scheme_dir.mkdir(parents=True, exist_ok=True)
    (scheme_dir / (NAME + ".xcscheme")).write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2600" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries>
    <BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">
      <BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="{NAME}.app" BlueprintName="{NAME}" ReferencedContainer="container:{NAME}.xcodeproj"/>
    </BuildActionEntry>
  </BuildActionEntries></BuildAction>
  <LaunchAction buildConfiguration="Release" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugServiceExtension="internal" allowLocationSimulation="NO">
    <BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="{NAME}.app" BlueprintName="{NAME}" ReferencedContainer="container:{NAME}.xcodeproj"/></BuildableProductRunnable>
  </LaunchAction>
  <ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''')
    print(PROJECT)


if __name__ == "__main__":
    main()
