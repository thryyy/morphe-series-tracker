package app.morphe.patches.youtube.video.series

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import java.util.zip.ZipFile

/** Optional real-host regression checks; the APK and its bytecode are never committed. */
fun main(args: Array<String>): Unit = kotlinx.coroutines.runBlocking {
    for (apk in args) {
        val temporary = java.nio.file.Files.createTempDirectory("series-fingerprint-host").toFile()
        try {
            app.morphe.patcher.Patcher(app.morphe.patcher.PatcherConfig(java.io.File(apk), temporary)).use { patcher ->
                patcher += setOf(app.morphe.patcher.patch.bytecodePatch(name = "Verify Series fingerprints", default = false) {
                    execute { verifyHost(arrayOf(apk)) }
                })
                patcher().collect { result -> result.exception?.let { throw it } }
                println("Verified $apk")
            }
        } finally { temporary.deleteRecursively() }
    }
}

context(context: app.morphe.patcher.patch.BytecodePatchContext)
private fun verifyHost(args: Array<String>) {
    fun resolveFixture(
        service: ClassDef, responseAccessors: List<Method>, endpointRegistration: Method,
        accountType: String, lookup: (String) -> ClassDef,
    ): NativeHistoryContract {
        // Each fixture models a separate patch run; single-match fingerprints cache their result.
        NativeHomeRouteSetterFingerprint.clearMatch()
        return resolveNativeHistory(service, responseAccessors, endpointRegistration, accountType, lookup)
    }

    require(args.size == 1) { "Pass the original, unpatched YouTube APK" }
    val classes =
        ZipFile(args[0]).use { apk ->
            apk.entries()
                .asSequence()
                .filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                .flatMap { entry ->
                    apk.getInputStream(entry)
                        .buffered()
                        .use {
                            DexBackedDexFile.fromInputStream(Opcodes.getDefault(), it).classes
                        }
                        .asSequence()
                }
                .associateBy { it.type }
        }
    fun Method.hasString(text: String) =
        implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == text
        } == true
    fun Method.hasLiteral(value: Int) =
        implementation?.instructions?.any {
            (it as? NarrowLiteralInstruction)?.narrowLiteral == value
        } == true
    fun <T> Iterable<T>.unique(role: String): T {
        val matches = toList()
        check(matches.size == 1) {
            "Series host check: $role expected one match, found ${matches.size}: $matches"
        }
        return matches.single()
    }
    val service =
        classes.values
            .filter { c ->
                c.methods.any { it.name == "<init>" && it.hasString("browse") }
            }
            .unique("native browse service")
    val response =
        classes.values
            .filter { "Landroid/os/Parcelable;" in it.interfaces }
            .flatMap { it.methods }
            .filter { it.parameterTypes.isEmpty() && it.hasLiteral(58173949) }
            .toList()
    val endpoint =
        classes.values
            .flatMap { it.methods }
            .filter {
                it.name == "<clinit>" &&
                    it.hasLiteral(48687757) &&
                    it.implementation!!.instructions.any { instruction ->
                        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)
                            ?.name == "newSingularGeneratedExtension"
                    }
            }
            .unique("watch endpoint registration")
    val identity =
        classes.values
            .filter { c -> c.methods.any { it.hasString("AccountIdentity{getId=") } }
            .unique("account identity")
    val account =
        identity.interfaces.single { type ->
            service.methods.any {
                it.parameterTypes.size == 3 &&
                    it.parameterTypes[1] == type &&
                    it.parameterTypes.last() == "Ljava/lang/String;"
            }
        }
    val original = resolveFixture(service, response, endpoint, account, classes::getValue)

    println("HOST_CONTRACT " + args[0] + " " + listOf(original.capture, original.factory, original.dispatch, original.genericDispatch).joinToString(" | "))

    println("HOST_FIXTURE " + (listOf(args[0], account, original.request.superclass!!) +
        listOf(original.factory, original.capture, original.dispatch, original.genericDispatch,
            original.routeSetter, original.continuationSetter, original.identity, original.route,
            original.continuation, original.clickTracking, original.payload).map { it.toString() }
    ).joinToString("\t"))

    val legacy = resolveLegacyNativeHistory(service, response, endpoint, account, classes::getValue)
    fun NativeHistoryContract.members() = listOf(
        factory, capture, dispatch, genericDispatch, routeSetter, continuationSetter, identity,
        route, continuation, clickTracking, payload
    ).map { it.toString() }
    check(original.members() == legacy.members()) {
        "Fingerprint selection differs from reviewed PR: ${original.members()} vs ${legacy.members()}"
    }

    val obfuscated = Regex("L[a-z]{1,8};")
    fun renameType(type: String) =
        if (obfuscated.matches(type)) "Lrenamed/${type.substring(1)}" else type
    fun renameMember(type: String, name: String) =
        if (obfuscated.matches(type) && name.matches(Regex("[A-Za-z]{1,3}"))) "renamed_$name"
        else name
    val rewriter =
        DexRewriter(
            object : RewriterModule() {
                override fun getTypeRewriter(rewriters: Rewriters): Rewriter<String> =
                    Rewriter(::renameType)

                override fun getMethodReferenceRewriter(
                    rewriters: Rewriters
                ): Rewriter<MethodReference> = Rewriter {
                    ImmutableMethodReference(
                        renameType(it.definingClass),
                        renameMember(it.definingClass, it.name),
                        it.parameterTypes.map { type -> renameType(type.toString()) },
                        renameType(it.returnType),
                    )
                }

                override fun getFieldReferenceRewriter(
                    rewriters: Rewriters
                ): Rewriter<FieldReference> = Rewriter {
                    ImmutableFieldReference(
                        renameType(it.definingClass),
                        renameMember(it.definingClass, it.name),
                        renameType(it.type),
                    )
                }
            }
        )
    val renamed =
        classes
            .mapKeys { renameType(it.key) }
            .mapValues { rewriter.classDefRewriter.rewrite(it.value) }
    fun rewrite(method: Method) =
        renamed.getValue(renameType(method.definingClass)).methods.single {
            it.name == renameMember(method.definingClass, method.name) &&
                it.parameterTypes.map(CharSequence::toString) ==
                    method.parameterTypes.map { type -> renameType(type.toString()) } &&
                it.returnType == renameType(method.returnType)
        }
    val moved =
        resolveFixture(
            renamed.getValue(renameType(service.type)),
            response.map(::rewrite),
            rewrite(endpoint),
            renameType(account),
            renamed::getValue,
        )
    check(
        moved.dispatch.toString() ==
            rewriter.methodReferenceRewriter.rewrite(original.dispatch).toString()
    )
    check(
        moved.route.toString() == rewriter.fieldReferenceRewriter.rewrite(original.route).toString()
    )
    check(
        moved.continuation.toString() ==
            rewriter.fieldReferenceRewriter.rewrite(original.continuation).toString()
    )
    check(
        moved.payload.toString() ==
            rewriter.fieldReferenceRewriter.rewrite(original.payload).toString()
    )
    check(
        moved.identity.toString() ==
            rewriter.methodReferenceRewriter.rewrite(original.identity).toString()
    )

    fun withMethods(type: ClassDef, methods: Iterable<Method>) =
        ImmutableClassDef(
            type.type,
            type.accessFlags,
            type.superclass,
            type.interfaces,
            type.sourceFile,
            type.annotations,
            type.fields,
            methods,
        )
    fun rejects(block: () -> Unit) {
        try {
            block()
        } catch (expected: PatchException) {
            return
        }
        error("Changed host contract was accepted")
    }
    fun withInstructions(method: Method, instructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>) =
        ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
            method.accessFlags, method.annotations, method.hiddenApiRestrictions,
            com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation(
                method.implementation!!.registerCount, instructions, emptyList(), emptyList()))
    val wrongAllocation = withInstructions(original.capture, original.capture.implementation!!.instructions.map {
        if (it.opcode == com.android.tools.smali.dexlib2.Opcode.NEW_INSTANCE &&
            ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.TypeReference)?.type == original.capture.returnType)
            com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c(it.opcode,
                (it as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA,
                com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference("Ljava/lang/Object;"))
        else it
    })
    rejects { resolveFixture(withMethods(service, service.methods.map { if (it == original.capture) wrongAllocation else it }), response, endpoint, account, classes::getValue) }
    val otherGeneric = original.genericDispatch.let { method ->
        val params = if (method.parameters.size == 4) method.parameters.take(3) else method.parameters +
            com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter("Ljava/lang/Object;", emptySet(), null)
        ImmutableMethod(method.definingClass, "anotherGenericDispatch", params, method.returnType,
            method.accessFlags, method.annotations, method.hiddenApiRestrictions, method.implementation)
    }
    rejects { resolveFixture(withMethods(service, service.methods + otherGeneric), response, endpoint, account, classes::getValue) }
    if (original.factory.parameterTypes.isNotEmpty()) {
        var replacements = 0
        val wrongNullArguments = service.methods.map { method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@map method
            withInstructions(method, instructions.mapIndexed { i, instruction ->
                if (instruction.opcode == com.android.tools.smali.dexlib2.Opcode.CONST_4 &&
                    (instruction as NarrowLiteralInstruction).narrowLiteral == 0 &&
                    (instructions.getOrNull(i + 1) as? ReferenceInstruction)?.reference.toString() == original.factory.toString()) {
                    replacements++
                    com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n(
                        instruction.opcode, (instruction as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA xor 1, 0)
                } else instruction
            })
        }
        check(replacements > 0)
        rejects { resolveFixture(withMethods(service, wrongNullArguments), response, endpoint, account, classes::getValue) }
    }
    val factory = original.factory
    val duplicate =
        ImmutableMethod(
            factory.definingClass,
            "anotherFactory",
            factory.parameters,
            factory.returnType,
            factory.accessFlags,
            factory.annotations,
            factory.hiddenApiRestrictions,
            factory.implementation,
        )
    val duplicateCallers =
        if (factory.parameterTypes.isEmpty()) emptyList()
        else {
            val caller =
                service.methods.first { method ->
                    method.implementation?.instructions?.any {
                        (it as? ReferenceInstruction)?.reference.toString() == factory.toString()
                    } == true
                }
            val duplicateRewriter =
                DexRewriter(
                    object : RewriterModule() {
                        override fun getMethodReferenceRewriter(
                            rewriters: Rewriters
                        ): Rewriter<MethodReference> = Rewriter {
                            if (it.toString() == factory.toString()) duplicate else it
                        }
                    }
                )
            val rewritten = duplicateRewriter.methodRewriter.rewrite(caller)
            listOf(
                ImmutableMethod(
                    caller.definingClass,
                    "anotherFactoryCaller",
                    caller.parameters,
                    caller.returnType,
                    caller.accessFlags,
                    caller.annotations,
                    caller.hiddenApiRestrictions,
                    rewritten.implementation,
                )
            )
        }
    rejects {
        resolveFixture(
            withMethods(service, service.methods + duplicate + duplicateCallers),
            response,
            endpoint,
            account,
            classes::getValue,
        )
    }
    val missingLabel =
        withMethods(
            original.request,
            original.request.methods.filterNot { it.hasString("browseId") },
        )
    rejects {
        resolveFixture(service, response, endpoint, account) {
            if (it == missingLabel.type) missingLabel else classes.getValue(it)
        }
    }
    val missingDispatch =
        withMethods(service, service.methods.filterNot { it == original.dispatch })
    rejects {
        resolveFixture(missingDispatch, response, endpoint, account, classes::getValue)
    }
    val routeOwner = classes.getValue(original.routeSetter.definingClass)
    val extraSetter = ImmutableMethod(
        routeOwner.type, "anotherRouteSetter", original.routeSetter.parameters, "V",
        original.routeSetter.accessFlags, emptySet(), emptySet(),
        com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation(
            2, listOf(
                com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c(
                    com.android.tools.smali.dexlib2.Opcode.IPUT_OBJECT, 1, 0, original.route),
                com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x(
                    com.android.tools.smali.dexlib2.Opcode.RETURN_VOID)
            ), emptyList(), emptyList()
        )
    )
    rejects {
        resolveFixture(service, response, endpoint, account) {
            if (it == routeOwner.type) withMethods(routeOwner, routeOwner.methods + extraSetter)
            else classes.getValue(it)
        }
    }
    rejects {
        resolveFixture(service, response, endpoint, account) {
            if (it == routeOwner.type) withMethods(routeOwner,
                routeOwner.methods.filterNot { it.toString() == original.routeSetter.toString() } + extraSetter)
            else classes.getValue(it)
        }
    }
    val delegated = original.dispatch.implementation!!.instructions.mapNotNull {
        (it as? ReferenceInstruction)?.reference as? MethodReference
    }.first { it.definingClass == service.type && it.name != original.dispatch.name &&
        it.parameterTypes == original.dispatch.parameterTypes && it.returnType == original.dispatch.returnType }
    rejects {
        resolveFixture(
            withMethods(service, service.methods.filterNot { it.toString() == delegated.toString() }),
            response, endpoint, account, classes::getValue
        )
    }
    rejects {
        resolveFixture(service, emptyList(), endpoint, account, classes::getValue)
    }
    val adapter = classes.getValue(original.payload.definingClass)
    val missingPayloadConstructor =
        withMethods(adapter, adapter.methods.filterNot { it.name == "<init>" })
    rejects {
        resolveFixture(service, response, endpoint, account) {
            if (it == adapter.type) missingPayloadConstructor else classes.getValue(it)
        }
    }
    println(
        "PASS: native History resolves on original and renamed bytecode; ambiguous factories, missing labels, missing dispatch and missing response payloads are rejected"
    )
}
