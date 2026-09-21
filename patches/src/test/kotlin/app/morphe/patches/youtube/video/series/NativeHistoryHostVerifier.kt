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
fun main(args: Array<String>) {
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
    val original = resolveNativeHistory(service, response, endpoint, account, classes::getValue)

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
        resolveNativeHistory(
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
        resolveNativeHistory(
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
        resolveNativeHistory(service, response, endpoint, account) {
            if (it == missingLabel.type) missingLabel else classes.getValue(it)
        }
    }
    val missingDispatch =
        withMethods(service, service.methods.filterNot { it == original.dispatch })
    rejects {
        resolveNativeHistory(missingDispatch, response, endpoint, account, classes::getValue)
    }
    rejects {
        resolveNativeHistory(service, emptyList(), endpoint, account, classes::getValue)
    }
    val adapter = classes.getValue(original.payload.definingClass)
    val missingPayloadConstructor =
        withMethods(adapter, adapter.methods.filterNot { it.name == "<init>" })
    rejects {
        resolveNativeHistory(service, response, endpoint, account) {
            if (it == adapter.type) missingPayloadConstructor else classes.getValue(it)
        }
    }
    println(
        "PASS: native History resolves on original and renamed bytecode; ambiguous factories, missing labels, missing dispatch and missing response payloads are rejected"
    )
}
