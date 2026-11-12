#!/usr/bin/env python3
"""Regenerates ToneMic.xcodeproj from whatever is in ToneMic/Sources.

An .xcodeproj lists every source file explicitly, so adding a file means editing the project.
Rather than hand-patch the pbxproj each time (and forget, and get "cannot find type X in scope"),
this rebuilds it from the directory. Run it after adding or removing a source file.
"""
import os, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ToneMic")
TEAM = "TTT8628BZJ"
BUNDLE_ID = "ca.exmachina.tonemic"
DEPLOYMENT_TARGET = "17.0"

srcs = sorted(f for f in os.listdir(os.path.join(ROOT, "Sources")) if f.endswith(".swift"))
if not srcs:
    sys.exit("no sources found")

def oid(n): return "AA%022X" % n
ids, k = {}, 1
for s in srcs:
    ids[("file", s)] = oid(k); k += 1
    ids[("build", s)] = oid(k); k += 1
PRODUCT, GRP_ROOT, GRP_SRC, GRP_PROD, TARGET, PROJECT = (oid(k+i) for i in range(6)); k += 6
PH_SRC, PH_FRM, PH_RES, CFG_LIST_P, CFG_LIST_T = (oid(k+i) for i in range(5)); k += 5
CFG_PD, CFG_PR, CFG_TD, CFG_TR, PLIST = (oid(k+i) for i in range(5)); k += 5
ASSETS, ASSETS_BUILD = (oid(k+i) for i in range(2)); k += 2
has_assets = os.path.isdir(os.path.join(ROOT, "Assets.xcassets"))

build_files = "\n".join(
    f"\t\t{ids[('build',s)]} /* {s} in Sources */ = {{isa = PBXBuildFile; fileRef = {ids[('file',s)]} /* {s} */; }};"
    for s in srcs)
file_refs = "\n".join(
    f'\t\t{ids[("file",s)]} /* {s} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {s}; sourceTree = "<group>"; }};'
    for s in srcs)
src_children = "\n".join(f"\t\t\t\t{ids[('file',s)]} /* {s} */," for s in srcs)
src_phase = "\n".join(f"\t\t\t\t{ids[('build',s)]} /* {s} in Sources */," for s in srcs)

COMMON = f"""				CLANG_ENABLE_MODULES = YES;
				CLANG_ENABLE_OBJC_ARC = YES;
				ENABLE_STRICT_OBJC_MSGSEND = YES;
				IPHONEOS_DEPLOYMENT_TARGET = {DEPLOYMENT_TARGET};
				SDKROOT = iphoneos;
				SWIFT_VERSION = 5.0;"""

TARGET_COMMON = f"""				ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;
				CODE_SIGN_STYLE = Automatic;
				CURRENT_PROJECT_VERSION = 1;
				DEVELOPMENT_TEAM = {TEAM};
				GENERATE_INFOPLIST_FILE = NO;
				INFOPLIST_FILE = Info.plist;
				LD_RUNPATH_SEARCH_PATHS = (
					"$(inherited)",
					"@executable_path/Frameworks",
				);
				MARKETING_VERSION = 0.1;
				PRODUCT_BUNDLE_IDENTIFIER = {BUNDLE_ID};
				PRODUCT_NAME = "$(TARGET_NAME)";
				SWIFT_EMIT_LOC_STRINGS = YES;
				TARGETED_DEVICE_FAMILY = "1,2";"""

assets_ref = (f'\t\t{ASSETS} /* Assets.xcassets */ = {{isa = PBXFileReference; '
              'lastKnownFileType = folder.assetcatalog; path = Assets.xcassets; '
              'sourceTree = "<group>"; };') if has_assets else ""
assets_child = f"\t\t\t\t{ASSETS} /* Assets.xcassets */," if has_assets else ""
assets_build = (f"\t\t\t\t{ASSETS_BUILD} /* Assets.xcassets in Resources */,") if has_assets else ""
assets_buildfile = (f'\t\t{ASSETS_BUILD} /* Assets.xcassets in Resources */ = '
                    f'{{isa = PBXBuildFile; fileRef = {ASSETS} /* Assets.xcassets */; }};') if has_assets else ""

pbx = f"""// !$*UTF8*$!
{{
	archiveVersion = 1;
	classes = {{}};
	objectVersion = 56;
	objects = {{

/* Begin PBXBuildFile section */
{build_files}
{assets_buildfile}
/* End PBXBuildFile section */

/* Begin PBXFileReference section */
{file_refs}
		{PLIST} /* Info.plist */ = {{isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = "<group>"; }};
{assets_ref}
		{PRODUCT} /* ToneMic.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = ToneMic.app; sourceTree = BUILT_PRODUCTS_DIR; }};
/* End PBXFileReference section */

/* Begin PBXFrameworksBuildPhase section */
		{PH_FRM} = {{
			isa = PBXFrameworksBuildPhase;
			buildActionMask = 2147483647;
			files = ();
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXFrameworksBuildPhase section */

/* Begin PBXGroup section */
		{GRP_ROOT} = {{
			isa = PBXGroup;
			children = (
				{GRP_SRC} /* Sources */,
				{PLIST} /* Info.plist */,
{assets_child}
				{GRP_PROD} /* Products */,
			);
			sourceTree = "<group>";
		}};
		{GRP_SRC} /* Sources */ = {{
			isa = PBXGroup;
			children = (
{src_children}
			);
			path = Sources;
			sourceTree = "<group>";
		}};
		{GRP_PROD} /* Products */ = {{
			isa = PBXGroup;
			children = (
				{PRODUCT} /* ToneMic.app */,
			);
			name = Products;
			sourceTree = "<group>";
		}};
/* End PBXGroup section */

/* Begin PBXNativeTarget section */
		{TARGET} /* ToneMic */ = {{
			isa = PBXNativeTarget;
			buildConfigurationList = {CFG_LIST_T} /* Build configuration list for PBXNativeTarget "ToneMic" */;
			buildPhases = (
				{PH_SRC},
				{PH_FRM},
				{PH_RES},
			);
			buildRules = ();
			dependencies = ();
			name = ToneMic;
			productName = ToneMic;
			productReference = {PRODUCT} /* ToneMic.app */;
			productType = "com.apple.product-type.application";
		}};
/* End PBXNativeTarget section */

/* Begin PBXProject section */
		{PROJECT} /* Project object */ = {{
			isa = PBXProject;
			attributes = {{
				BuildIndependentTargetsInParallel = 1;
				LastSwiftUpdateCheck = 2630;
				LastUpgradeCheck = 2630;
				TargetAttributes = {{
					{TARGET} = {{ CreatedOnToolsVersion = 26.3; }};
				}};
			}};
			buildConfigurationList = {CFG_LIST_P} /* Build configuration list for PBXProject "ToneMic" */;
			compatibilityVersion = "Xcode 14.0";
			developmentRegion = en;
			hasScannedForEncodings = 0;
			knownRegions = ( en, Base );
			mainGroup = {GRP_ROOT};
			productRefGroup = {GRP_PROD} /* Products */;
			projectDirPath = "";
			projectRoot = "";
			targets = (
				{TARGET} /* ToneMic */,
			);
		}};
/* End PBXProject section */

/* Begin PBXResourcesBuildPhase section */
		{PH_RES} = {{
			isa = PBXResourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
{assets_build}
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXResourcesBuildPhase section */

/* Begin PBXSourcesBuildPhase section */
		{PH_SRC} = {{
			isa = PBXSourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
{src_phase}
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXSourcesBuildPhase section */

/* Begin XCBuildConfiguration section */
		{CFG_PD} /* Debug */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{COMMON}
				ONLY_ACTIVE_ARCH = YES;
				SWIFT_OPTIMIZATION_LEVEL = "-Onone";
				SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG;
				GCC_OPTIMIZATION_LEVEL = 0;
				DEBUG_INFORMATION_FORMAT = dwarf;
			}};
			name = Debug;
		}};
		{CFG_PR} /* Release */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{COMMON}
				SWIFT_OPTIMIZATION_LEVEL = "-O";
				DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym";
				VALIDATE_PRODUCT = YES;
			}};
			name = Release;
		}};
		{CFG_TD} /* Debug */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{TARGET_COMMON}
			}};
			name = Debug;
		}};
		{CFG_TR} /* Release */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
{TARGET_COMMON}
			}};
			name = Release;
		}};
/* End XCBuildConfiguration section */

/* Begin XCConfigurationList section */
		{CFG_LIST_P} /* Build configuration list for PBXProject "ToneMic" */ = {{
			isa = XCConfigurationList;
			buildConfigurations = (
				{CFG_PD} /* Debug */,
				{CFG_PR} /* Release */,
			);
			defaultConfigurationIsVisible = 0;
			defaultConfigurationName = Release;
		}};
		{CFG_LIST_T} /* Build configuration list for PBXNativeTarget "ToneMic" */ = {{
			isa = XCConfigurationList;
			buildConfigurations = (
				{CFG_TD} /* Debug */,
				{CFG_TR} /* Release */,
			);
			defaultConfigurationIsVisible = 0;
			defaultConfigurationName = Release;
		}};
/* End XCConfigurationList section */
	}};
	rootObject = {PROJECT} /* Project object */;
}}
"""
out = os.path.join(ROOT, "ToneMic.xcodeproj")
os.makedirs(out, exist_ok=True)
open(os.path.join(out, "project.pbxproj"), "w").write(pbx)
print("regenerated with %d sources: %s" % (len(srcs), ", ".join(srcs)))
