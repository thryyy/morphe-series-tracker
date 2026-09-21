package app.morphe.patches.youtube.video.series

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.zip.ZipFile

/** Optional inspection of an actual patched APK; no test code is packaged in the bundle. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Pass the APK produced by the integrated bundle" }
    val classes = mutableListOf<ClassDef>()
    ZipFile(args[0]).use { apk ->
        apk.entries()
            .asSequence()
            .filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
            .forEach { entry ->
                apk.getInputStream(entry).buffered().use {
                    classes += DexBackedDexFile.fromInputStream(Opcodes.getDefault(), it).classes
                }
            }
    }
    check(classes.isNotEmpty())
    check(classes.size == classes.map { it.type }.toSet().size) { "Duplicate DEX definitions" }
    check(classes.none { it.type.startsWith("Lapp/seriestracker/") }) {
        "Standalone extension leaked into APK"
    }
    val own = classes.filter { it.type.startsWith(OUR_PREFIX) }
    check(own.any { it.type == "${OUR_PREFIX}SeriesTrackerPatch;" }) { "Series extension missing" }
    check(
        own.none {
            it.type.contains("Probe") ||
                it.type.contains("Diagnostics") ||
                it.type.contains("AddOn")
        }
    )

    fun Method.calls() =
        implementation
            ?.instructions
            ?.mapNotNull {
                (it as? ReferenceInstruction)?.reference as? MethodReference
            }
            .orEmpty()
    val controller = classes.single { "${OUR_PREFIX}PlaybackBridge\$Source;" in it.interfaces }
    listOf("seriesTrackerVideoId" to "Ljava/lang/String;", "seriesTrackerPosition" to "J")
        .forEach { (name, type) ->
            val bridge = controller.methods.single { it.name == name && it.returnType == type }
            val call = bridge.calls().single()
            check(call.definingClass == controller.type)
            check(
                controller.methods.any {
                    it.signature() == call.signature() && it.implementation != null
                }
            )
            if (type == "J") check(call.name == "patch_getVideoTime")
        }
    val outside = classes.filterNot { it.type.startsWith(OUR_PREFIX) }.flatMap { it.methods }
    // The switcher must receive the complete native page, not only its scrolling content.
    val browseCreate = outside.single { method ->
        method.calls().any { it.name == "seriesTrackerHistoryView" }
    }
    val browseInstructions = browseCreate.implementation!!.instructions.toList()
    val wrapIndex = browseInstructions.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name ==
            "seriesTrackerHistoryView"
    }
    check(wrapIndex >= 2) { "History page wrapper missing" }
    val nativeWrapper = (browseInstructions[wrapIndex - 2] as? ReferenceInstruction)
        ?.reference as? MethodReference
    check(nativeWrapper?.returnType == "Landroid/view/View;" &&
        nativeWrapper.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;")) {
        "History switcher must follow the native toolbar wrapper"
    }
    val pageResult = browseInstructions[wrapIndex - 1]
    val switchCall = browseInstructions[wrapIndex] as FiveRegisterInstruction
    check(pageResult.opcode == Opcode.MOVE_RESULT_OBJECT &&
        (pageResult as OneRegisterInstruction).registerA == switchCall.registerD) {
        "History switcher must consume the complete native page result"
    }
    val entry = "${OUR_PREFIX}SeriesTrackerPatch;"
    val constructors = outside.filter { method ->
        method.calls().any { it.definingClass == entry && it.name == "newVideoStarted" }
    }
    check(
        constructors.size == 1 &&
            constructors.single().definingClass == controller.type &&
            constructors.single().name == "<init>"
    ) {
        "Shared controller hook missing or duplicated"
    }
    check(
        outside.sumOf { method ->
            method.calls().count {
                it.definingClass == entry && it.name == "videoTimeChanged"
            }
        } == 1
    ) {
        "Shared progress hook missing or duplicated"
    }
    val sessionOwners = outside.filter { method ->
        method.calls().any {
            it.definingClass == "${OUR_PREFIX}PlaybackSession;" && it.name == "attach"
        }
    }
    check(sessionOwners.size == 1)
    val calls = sessionOwners.single().calls()
    val sessionIndex = calls.indexOfFirst { it.definingClass == "${OUR_PREFIX}PlaybackSession;" }
    check(
        sessionIndex > 0 &&
            calls[sessionIndex - 1].definingClass == "Landroid/media/session/MediaSession;" &&
            calls[sessionIndex - 1].name == "setMetadata"
    )
    check(classes.count { "${OUR_PREFIX}RecordingPrivacy\$Source;" in it.interfaces } == 2)
    val history = "${OUR_PREFIX}NativeHistoryTransport;"
    check(
        own.none {
            it.type.contains("NativePlayerTransport") || it.type.contains("RemoteProgressPolicy")
        }
    ) {
        "Obsolete per-video metadata sync leaked into APK"
    }
    val request = classes.single {
        "${OUR_PREFIX}NativeHistoryTransport\$Request;" in it.interfaces
    }
    val identityCalls = request.methods.single { it.name == "seriesTrackerRequestIdentity" }.calls()
    val identityGetter = identityCalls.first()
    check(identityGetter.parameterTypes.isEmpty() && identityGetter.returnType.startsWith("L"))
    check(
        identityCalls.count {
            it.definingClass == identityGetter.returnType && it.parameterTypes.isEmpty()
        } == 2
    )
    check(identityCalls.last().definingClass == "${OUR_PREFIX}RecordingPrivacy\$Identity;")
    val historyService = classes.single {
        "${OUR_PREFIX}NativeHistoryTransport\$Source;" in it.interfaces
    }
    val wrappers =
        historyService.methods.filter { method ->
            method.calls().any { it.definingClass == history && it.name == "after" }
        }
    check(wrappers.size == 2)
    for (wrapper in wrappers) {
        val calls = wrapper.calls()
        check(calls.size == 3)
        check(calls[0].definingClass == history && calls[0].name == "before")
        check(
            calls[1].definingClass == historyService.type &&
                calls[1].name == "seriesTrackerNative" + wrapper.name
        )
        check(
            calls[1].parameterTypes == wrapper.parameterTypes &&
                calls[1].returnType == wrapper.returnType
        )
        check(calls[2].definingClass == history && calls[2].name == "after")
        check(
            historyService.methods
                .single { it.signature() == calls[1].signature() }
                .implementation != null
        )
    }
    val dispatch = historyService.methods.single { it.name == "seriesTrackerHistory" }.calls()
    val authorization = dispatch.indexOfFirst { it.definingClass == history && it.name == "before" }
    val nativeDispatch = dispatch.indexOfFirst { call ->
        call.definingClass == historyService.type &&
            wrappers.any { it.signature() == call.signature() }
    }
    check(authorization >= 0 && nativeDispatch > authorization)
    check(
        historyService.methods
            .single { it.name == "seriesTrackerHistoryBytes" }
            .calls()
            .any { it.name == "getSerializedSize" }
    )
    println(
        "PASS: ${classes.size} unique classes; ${own.size} Series classes; direct playback hooks, controller, session, privacy and native History bridges; no per-video metadata sync, standalone extension or probes"
    )
}
