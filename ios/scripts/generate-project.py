#!/usr/bin/env python3
"""Regenerate the checked-in Xcode project deterministically, without XcodeGen/CocoaPods."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
objects = {}


def identity(name):
    return hashlib.sha256(name.encode()).hexdigest()[:24].upper()


def quoted(value):
    return json.dumps(str(value), ensure_ascii=False)


def add(name, value):
    key = identity(name)
    objects[key] = value
    return key


def array(values):
    return "(" + ", ".join(values) + ")"


def settings(values):
    return "{ " + " ".join(f"{key} = {quoted(value)};" for key, value in values.items()) + " }"


def configurations(name, common, debug=None):
    refs = []
    for kind in ("Debug", "Release"):
        values = dict(common)
        values.update({"SWIFT_OPTIMIZATION_LEVEL": "-Onone" if kind == "Debug" else "-O",
                       "SWIFT_COMPILATION_MODE": "singlefile" if kind == "Debug" else "wholemodule",
                       "DEBUG_INFORMATION_FORMAT": "dwarf" if kind == "Debug" else "dwarf-with-dsym"})
        if kind == "Debug":
            values.update({"SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG", "ONLY_ACTIVE_ARCH": "YES", **(debug or {})})
        refs.append(add(f"{name}-{kind}", f"{{ isa = XCBuildConfiguration; name = {kind}; buildSettings = {settings(values)}; }}"))
    return add(f"{name}-configs", f"{{ isa = XCConfigurationList; buildConfigurations = {array(refs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }}")


def file_ref(path, file_type):
    return add(f"file-{path}", f"{{ isa = PBXFileReference; lastKnownFileType = {file_type}; path = {quoted(path)}; sourceTree = SOURCE_ROOT; }}")


def build_file(target, path, ref, attributes=""):
    return add(f"build-{target}-{path}", f"{{ isa = PBXBuildFile; fileRef = {ref}; {attributes} }}")


def phase(name, kind, files, extra=""):
    return add(name, f"{{ isa = {kind}; buildActionMask = 2147483647; files = {array(files)}; runOnlyForDeploymentPostprocessing = 0; {extra} }}")


app = identity("target-app")
project = identity("project")
framework_path = "Vendor/MobileVLCKit-binary/MobileVLCKit.xcframework"
framework = file_ref(framework_path, "wrapper.xcframework")
groups = []
targets = []
products = []
target_specs = [("app", "DomruTechnics", "Sources", "com.apple.product-type.application", "app"),
                ("tests", "DomruTechnicsTests", "Tests", "com.apple.product-type.bundle.unit-test", "xctest"),
                ("ui-tests", "DomruTechnicsUITests", "UITests", "com.apple.product-type.bundle.ui-testing", "xctest")]
for key, name, folder, product_type, extension in target_specs:
    source_refs = []
    source_builds = []
    for path in sorted((ROOT / folder).rglob("*.swift")):
        relative = path.relative_to(ROOT).as_posix()
        ref = file_ref(relative, "sourcecode.swift")
        source_refs.append(ref)
        source_builds.append(build_file(key, relative, ref))
    groups.append(add(f"group-{folder}", f"{{ isa = PBXGroup; children = {array(source_refs)}; name = {folder}; sourceTree = \"<group>\"; }}"))
    source_phase = phase(f"{key}-sources", "PBXSourcesBuildPhase", source_builds)
    framework_builds = [build_file(key, framework_path, framework)] if key == "app" else []
    framework_phase = phase(f"{key}-frameworks", "PBXFrameworksBuildPhase", framework_builds)
    resource_builds = []
    if key == "app":
        refs = []
        for path, file_type in [("Resources/Assets.xcassets", "folder.assetcatalog"),
                                ("Resources/demo-camera.flv", "file"),
                                ("Resources/MobileVLCKit-COPYING.txt", "text"),
                                ("Resources/PrivacyInfo.xcprivacy", "text.xml")]:
            ref = file_ref(path, file_type)
            refs.append(ref)
            resource_builds.append(build_file(key, path, ref))
        groups.append(add("group-resources", f"{{ isa = PBXGroup; children = {array(refs)}; name = Resources; sourceTree = \"<group>\"; }}"))
    resource_phase = phase(f"{key}-resources", "PBXResourcesBuildPhase", resource_builds)
    phases = [source_phase, framework_phase, resource_phase]
    dependencies = []
    if key == "app":
        embed = build_file("embed", framework_path, framework, "settings = { ATTRIBUTES = (CodeSignOnCopy, RemoveHeadersOnCopy); };")
        phases.append(phase("embed-frameworks", "PBXCopyFilesBuildPhase", [embed], 'dstPath = ""; dstSubfolderSpec = 10; name = "Embed Frameworks";'))
    else:
        proxy = add(f"proxy-{key}", f"{{ isa = PBXContainerItemProxy; containerPortal = {project}; proxyType = 1; remoteGlobalIDString = {app}; remoteInfo = DomruTechnics; }}")
        dependencies = [add(f"dependency-{key}", f"{{ isa = PBXTargetDependency; target = {app}; targetProxy = {proxy}; }}")]
    product = add(f"product-{key}", f"{{ isa = PBXFileReference; explicitFileType = {'wrapper.application' if key == 'app' else 'wrapper.cfbundle'}; includeInIndex = 0; path = {name}.{extension}; sourceTree = BUILT_PRODUCTS_DIR; }}")
    products.append(product)
    common = {"PRODUCT_NAME": "$(TARGET_NAME)", "PRODUCT_BUNDLE_IDENTIFIER": "ru.domru.technics" + ("" if key == "app" else "." + key),
              "SWIFT_VERSION": "5.0", "IPHONEOS_DEPLOYMENT_TARGET": "15.0", "TARGETED_DEVICE_FAMILY": "1,2",
              "CODE_SIGN_STYLE": "Automatic", "GENERATE_INFOPLIST_FILE": "YES",
              "LD_RUNPATH_SEARCH_PATHS": "$(inherited) @executable_path/Frameworks @loader_path/Frameworks"}
    if key == "app":
        common.update({"INFOPLIST_FILE": "Resources/Info.plist", "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon",
                       "MARKETING_VERSION": "0.3.12", "CURRENT_PROJECT_VERSION": "16",
                       "INFOPLIST_KEY_UIApplicationSceneManifest_Generation": "YES", "INFOPLIST_KEY_UILaunchScreen_Generation": "YES",
                       "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator", "SUPPORTS_MACCATALYST": "NO",
                       "ENABLE_PREVIEWS": "YES", "ENABLE_TESTABILITY": "YES"})
    elif key == "tests":
        common.update({"TEST_HOST": "$(BUILT_PRODUCTS_DIR)/DomruTechnics.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/DomruTechnics",
                       "BUNDLE_LOADER": "$(TEST_HOST)", "IPHONEOS_DEPLOYMENT_TARGET": "17.0"})
    else:
        common["TEST_TARGET_NAME"] = "DomruTechnics"
        # XCTest из Xcode 27 требует iOS 17; минимальная система самого приложения остаётся iOS 15.
        common["IPHONEOS_DEPLOYMENT_TARGET"] = "17.0"
    configs = configurations(key, common)
    targets.append(add(f"target-{key}", f"{{ isa = PBXNativeTarget; buildConfigurationList = {configs}; buildPhases = {array(phases)}; buildRules = (); dependencies = {array(dependencies)}; name = {name}; productName = {name}; productReference = {product}; productType = {quoted(product_type)}; }}"))

product_group = add("products", f"{{ isa = PBXGroup; children = {array(products)}; name = Products; sourceTree = \"<group>\"; }}")
groups.extend([framework, product_group])
main_group = add("main-group", f"{{ isa = PBXGroup; children = {array(groups)}; sourceTree = \"<group>\"; }}")
configs = configurations("project", {"SDKROOT": "iphoneos", "CLANG_ENABLE_MODULES": "YES", "CLANG_ENABLE_OBJC_ARC": "YES",
                                      "CLANG_WARN_DOCUMENTATION_COMMENTS": "YES", "GCC_WARN_UNUSED_VARIABLE": "YES",
                                      "GCC_WARN_UNUSED_FUNCTION": "YES", "ENABLE_USER_SCRIPT_SANDBOXING": "YES"})
add("project", f"{{ isa = PBXProject; attributes = {{ BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 2700; }}; buildConfigurationList = {configs}; compatibilityVersion = \"Xcode 14.0\"; developmentRegion = ru; hasScannedForEncodings = 0; knownRegions = (ru, en, Base); mainGroup = {main_group}; productRefGroup = {product_group}; projectDirPath = \"\"; projectRoot = \"\"; targets = {array(targets)}; }}")
directory = ROOT / "DomruTechnics.xcodeproj"
directory.mkdir(exist_ok=True)
(directory / "project.pbxproj").write_text("// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n" +
                                         "\n".join(f"{key} = {value};" for key, value in objects.items()) +
                                         f"\n}}; rootObject = {project}; }}\n", encoding="utf-8")

scheme = directory / "xcshareddata" / "xcschemes"
scheme.mkdir(parents=True, exist_ok=True)


def reference(key, name, extension):
    return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{identity("target-" + key)}" BuildableName="{name}.{extension}" BlueprintName="{name}" ReferencedContainer="container:DomruTechnics.xcodeproj"/>'


app_ref = reference("app", "DomruTechnics", "app")
test_refs = "".join(f'<TestableReference skipped="NO">{reference(key, name, "xctest")}</TestableReference>' for key, name in [("tests", "DomruTechnicsTests"), ("ui-tests", "DomruTechnicsUITests")])
(scheme / "DomruTechnics.xcscheme").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2700" version="1.3">
 <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{app_ref}</BuildActionEntry></BuildActionEntries></BuildAction>
 <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables>{test_refs}</Testables></TestAction>
 <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></LaunchAction>
 <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></ProfileAction>
 <AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''', encoding="utf-8")
print("Generated DomruTechnics.xcodeproj")
