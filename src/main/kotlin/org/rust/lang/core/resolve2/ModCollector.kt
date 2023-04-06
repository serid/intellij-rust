/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import com.intellij.psi.PsiAnchor
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.stubs.StubElement
import com.intellij.util.containers.map2Array
import org.rust.lang.RsConstants
import org.rust.lang.RsFileType
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.macros.MacroCallBody
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.ENUM_VARIANT_NS
import org.rust.lang.core.resolve.processModDeclResolveVariants
import org.rust.lang.core.resolve2.util.DollarCrateHelper
import org.rust.lang.core.resolve2.util.DollarCrateMap
import org.rust.lang.core.resolve2.util.buildStub
import org.rust.lang.core.stubs.*
import org.rust.openapiext.fileId
import org.rust.openapiext.findFileByMaybeRelativePath
import org.rust.openapiext.pathAsPath
import org.rust.openapiext.toPsiFile
import java.nio.file.InvalidPathException

class ModCollectorContext(
    val defMap: CrateDefMap,
    val context: CollectorContext,
    val macroDepth: Int = 0,
    /**
     * called when new [RsItemElement] is found
     * default behaviour: just add it to [ModData.visibleItems]
     * behaviour when processing expanded items:
     * add it to [ModData.visibleItems] and propagate to modules which have glob import from [ModData]
     * returns true if [ModData.visibleItems] were changed
     */
    val onAddItem: (ModData, String, PerNs, Visibility) -> Boolean =
        { containingMod, name, perNs, _ -> containingMod.addVisibleItem(name, perNs) }
) {
    val isHangingMode: Boolean get() = context.isHangingMode
}

typealias LegacyMacros = Map<String, DeclMacroDefInfo>

fun collectScope(
    scope: RsItemsOwner,
    modData: ModData,
    context: ModCollectorContext,
    modMacroIndex: MacroIndex = modData.macroIndex,
    dollarCrateHelper: DollarCrateHelper? = null,
    includeMacroIndex: MacroIndex? = null,
    propagateLegacyMacros: Boolean = false,
): LegacyMacros {
    val hashCalculator = HashCalculator(modData.isEnabledByCfgInner)
        .takeIf { modData.isNormalCrate }

    val includeMacroFile = if (includeMacroIndex != null) {
        (scope as RsFile).virtualFile!!
    } else {
        null
    }

    val collector = ModCollector(modData, context, modMacroIndex, hashCalculator, dollarCrateHelper, includeMacroFile)
    collector.collectMod(scope.getOrBuildStub() ?: return emptyMap(), propagateLegacyMacros)

    if (hashCalculator != null && scope is RsFile) {
        val fileHash = hashCalculator.getFileHash()
        context.defMap.addVisitedFile(scope, modData, fileHash, includeMacroIndex)
    }

    return collector.legacyMacros
}

fun collectExpandedElements(
    scope: StubElement<out RsItemsOwner>,
    call: MacroCallInfo,
    context: ModCollectorContext,
    dollarCrateHelper: DollarCrateHelper?
) {
    val collector = ModCollector(
        call.containingMod,
        context,
        call.macroIndex,
        hashCalculator = null,
        dollarCrateHelper,
        includeMacroFile = null
    )
    collector.collectMod(scope, propagateLegacyMacros = true)
}

/**
 * Collects explicitly declared items in all modules of crate.
 * Also populates [CollectorContext.imports] and [CollectorContext.macroCalls] in [context].
 */
private class ModCollector(
    private val modData: ModData,
    private val context: ModCollectorContext,
    /**
     * When collecting explicit items in [modData] - equal to `modData.macroIndex`.
     * When collecting expanded items - equal to macroIndex of macro call.
     */
    private val parentMacroIndex: MacroIndex,
    private val hashCalculator: HashCalculator?,
    private val dollarCrateHelper: DollarCrateHelper?,
    /** containing file, if it is `include!`-ed */
    private val includeMacroFile: VirtualFile?,
) : ModVisitor {

    private val defMap: CrateDefMap = context.defMap
    private val macroDepth: Int = context.macroDepth
    private val onAddItem: (ModData, String, PerNs, Visibility) -> Boolean = context.onAddItem
    private val crate: Crate = context.context.crate
    private val project: Project = context.context.project

    /**
     * Stores collected legacy macros, as well as macros from collected mods with macro_use attribute.
     * Will be propagated to (lexically) succeeding modules.
     * See [propagateLegacyMacros].
     */
    val legacyMacros: MutableMap<String, DeclMacroDefInfo> = hashMapOf()

    fun collectMod(mod: StubElement<out RsItemsOwner>, propagateLegacyMacros: Boolean = false) {
        val visitor = if (hashCalculator != null) {
            val stdlibAttributes = defMap.stdlibAttributes.takeIf { modData.isNormalCrate && modData.isCrateRoot }
            val hashVisitor = hashCalculator.getVisitor(crate, modData.fileRelativePath, stdlibAttributes)
            CompositeModVisitor(hashVisitor, this)
        } else {
            this
        }
        ModCollectorBase.collectMod(mod, modData.isDeeplyEnabledByCfg, visitor, crate)
        if (propagateLegacyMacros) propagateLegacyMacros(modData)
    }

    override fun collectImport(import: ImportLight) {
        val usePath = if (dollarCrateHelper != null && !import.isExternCrate) {
            dollarCrateHelper.convertPath(import.usePath, import.offsetInExpansion)
        } else {
            import.usePath
        }
        context.context.imports += Import(
            containingMod = modData,
            usePath = usePath,
            nameInScope = import.nameInScope,
            visibility = convertVisibility(import.visibility, import.isDeeplyEnabledByCfg),
            isGlob = import.isGlob,
            isExternCrate = import.isExternCrate,
            isPrelude = import.isPrelude
        )

        if (import.isDeeplyEnabledByCfg && import.isExternCrate && import.isMacroUse && !context.isHangingMode) {
            defMap.importExternCrateMacros(import.usePath.single())
        }
    }

    override fun collectSimpleItem(item: SimpleItemLight) {
        val name = item.name

        val visItem = convertToVisItem(item, isModOrEnum = false, forceCfgDisabledVisibility = false)
        val perNs = PerNs.from(visItem, item.namespaces)
        onAddItem(modData, name, perNs, visItem.visibility)

        /** See also [DefCollector.treatAsIdentityMacro] */
        if (item.procMacroKind != null) {
            modData.procMacros[name] = item.procMacroKind
        }
    }

    override fun collectModOrEnumItem(item: ModOrEnumItemLight, stub: RsNamedStub) {
        val name = item.name

        // could be null if `.resolve()` on `RsModDeclItem` returns null
        val childModData = tryCollectChildModule(item, stub, item.macroIndexInParent)

        val forceCfgDisabledVisibility = childModData != null && !childModData.isEnabledByCfgInner
        val visItem = convertToVisItem(item, isModOrEnum = true, forceCfgDisabledVisibility)
        childModData?.asVisItem = visItem
        check(visItem.isModOrEnum)
        if (childModData == null) return
        val perNs = PerNs.types(visItem)
        val changed = onAddItem(modData, name, perNs, visItem.visibility)

        // We have to check `changed` to be sure that `childModules` and `visibleItems` are consistent.
        // Note that here we choose first mod if there are multiple mods with same visibility (e.g. CfgDisabled).
        if (changed) {
            modData.childModules[name] = childModData
        }
    }

    private fun convertToVisItem(item: ItemLight, isModOrEnum: Boolean, forceCfgDisabledVisibility: Boolean): VisItem {
        val visibility = if (forceCfgDisabledVisibility) {
            Visibility.CfgDisabled
        } else {
            convertVisibility(item.visibility, item.isDeeplyEnabledByCfg)
        }
        val itemPath = modData.path.append(item.name)
        val isTrait = item is SimpleItemLight && item.isTrait
        return VisItem(itemPath, visibility, isModOrEnum, isTrait)
    }

    private fun tryCollectChildModule(item: ModOrEnumItemLight, stub: RsNamedStub, index: Int): ModData? {
        if (stub is RsEnumItemStub) return collectEnumAsModData(item, stub)

        val parentOwnedDirectory = includeMacroFile?.parent ?: modData.getOwnedDirectory()
        val childMod = when (stub) {
            is RsModItemStub -> ChildMod.Inline(stub, item.name, item.hasMacroUse)
            is RsModDeclItemStub -> {
                val childModPsi = resolveModDecl(item.name, item.pathAttribute, parentOwnedDirectory) ?: return null
                val hasMacroUse = item.hasMacroUse || childModPsi.hasMacroUseInner(crate)
                ChildMod.File(childModPsi, item.name, hasMacroUse)
            }
            else -> return null
        }

        val isDeeplyEnabledByCfgOuter = item.isDeeplyEnabledByCfg
        val isEnabledByCfgInner = childMod !is ChildMod.File || childMod.file.isEnabledByCfgSelf(crate)
        val (childModData, childModLegacyMacros) = collectChildModule(
            childMod,
            isDeeplyEnabledByCfgOuter,
            isEnabledByCfgInner,
            item.pathAttribute,
            index,
            parentOwnedDirectory
        )
        if (childMod.hasMacroUse && childModData.isDeeplyEnabledByCfg) {
            modData.addLegacyMacros(childModLegacyMacros)
            legacyMacros += childModLegacyMacros
        }
        if (childModData.isRsFile && childModData.hasPathAttributeRelativeToParentFile && childModData.fileId != null) {
            recordChildFileInUnusualLocation(modData, childModData.fileId)
        }
        return childModData
    }

    private fun collectChildModule(
        childMod: ChildMod,
        isDeeplyEnabledByCfgOuter: Boolean,
        isEnabledByCfgInner: Boolean,
        pathAttribute: String?,
        index: Int,
        parentOwnedDirectory: VirtualFile?
    ): Pair<ModData, LegacyMacros> {
        ProgressManager.checkCanceled()
        val childModPath = modData.path.append(childMod.name)
        val (fileId, fileRelativePath) = when (childMod) {
            is ChildMod.File -> childMod.file.virtualFile.fileId to ""
            is ChildMod.Inline -> modData.fileId to "${modData.fileRelativePath}::${childMod.name}"
        }
        val childModData = ModData(
            parent = modData,
            crate = modData.crate,
            path = childModPath,
            macroIndex = parentMacroIndex.append(index),
            isDeeplyEnabledByCfgOuter = isDeeplyEnabledByCfgOuter,
            isEnabledByCfgInner = isEnabledByCfgInner,
            fileId = fileId,
            fileRelativePath = fileRelativePath,
            ownedDirectoryId = childMod.getOwnedDirectory(modData, parentOwnedDirectory, pathAttribute)?.fileId,
            hasPathAttribute = pathAttribute != null,
            hasMacroUse = childMod.hasMacroUse,
            crateDescription = defMap.crateDescription
        )
        for ((name, defs) in modData.legacyMacros) {
            childModData.legacyMacros[name] = defs
        }

        val childModLegacyMacros = when (childMod) {
            is ChildMod.Inline -> {
                val collector = ModCollector(
                    childModData,
                    context,
                    childModData.macroIndex,
                    hashCalculator,
                    dollarCrateHelper,
                    includeMacroFile
                )
                collector.collectMod(childMod.mod)
                collector.legacyMacros
            }
            is ChildMod.File -> collectScope(childMod.file, childModData, context)
        }
        return Pair(childModData, childModLegacyMacros)
    }

    private fun collectEnumAsModData(enum: ModOrEnumItemLight, enumStub: RsEnumItemStub): ModData {
        val enumName = enum.name
        val enumPath = modData.path.append(enumName)
        val enumData = ModData(
            parent = modData,
            crate = modData.crate,
            path = enumPath,
            macroIndex = MacroIndex(intArrayOf() /* Not used anyway */),
            isDeeplyEnabledByCfgOuter = enum.isDeeplyEnabledByCfg,
            isEnabledByCfgInner = true,
            fileId = modData.fileId,
            fileRelativePath = "${modData.fileRelativePath}::$enumName",
            ownedDirectoryId = modData.ownedDirectoryId,  // actually can use any value here
            hasPathAttribute = modData.hasPathAttribute,
            isEnum = true,
            hasMacroUse = false,
            crateDescription = defMap.crateDescription
        )
        for (variantPsi in enumStub.variants) {
            val variantName = variantPsi.name ?: continue
            val variantPath = enumPath.append(variantName)
            val isVariantDeeplyEnabledByCfg = enumData.isDeeplyEnabledByCfg && variantPsi.isEnabledByCfgSelf(crate)
            val variantVisibility = if (isVariantDeeplyEnabledByCfg) Visibility.Public else Visibility.CfgDisabled
            val variant = VisItem(variantPath, variantVisibility)
            val variantPerNs = PerNs.from(variant, ENUM_VARIANT_NS)
            enumData.addVisibleItem(variantName, variantPerNs)
        }
        return enumData
    }

    override fun collectMacroCall(call: MacroCallLight, stub: RsMacroCallStub) {
        require(modData.isDeeplyEnabledByCfg) { "for performance reasons cfg-disabled macros should not be collected" }
        val bodyHash = call.bodyHash
        if (bodyHash == null && call.path.last() != "include") return
        val path = dollarCrateHelper?.convertPath(call.path, call.pathOffsetInExpansion) ?: call.path
        val dollarCrateMap = dollarCrateHelper?.getDollarCrateMap(
            call.bodyStartOffsetInExpansion,
            call.bodyEndOffsetInExpansion
        ) ?: DollarCrateMap.EMPTY
        val macroIndex = parentMacroIndex.append(call.macroIndexInParent)
        context.context.macroCalls += MacroCallInfo(
            modData,
            macroIndex,
            path,
            MacroCallBody.FunctionLike(call.body),
            bodyHash,
            containingFileId = includeMacroFile?.fileId ?: modData.fileId,
            macroDepth,
            dollarCrateMap
        )
    }

    override fun collectProcMacroCall(call: ProcMacroCallLight) {
        require(modData.isDeeplyEnabledByCfg) { "for performance reasons cfg-disabled macros should not be collected" }
        val macroIndex = parentMacroIndex.append(call.macroIndexInParent)
        var deriveIndexCounter = -1
        val attrs = call.attrs.map2Array {
            val path = dollarCrateHelper?.convertPath(it.path, it.pathStartOffsetInExpansion) ?: it.path
            val deriveIndex = if (it.index == -1) ++deriveIndexCounter else -1
            ProcMacroCallInfo.AttrInfo(path, it.index, deriveIndex)
        }
        val dollarCrateMap = dollarCrateHelper?.getDollarCrateMap(
            call.bodyStartOffsetInExpansion,
            call.bodyEndOffsetInExpansion
        ) ?: DollarCrateMap.EMPTY
        val originalItem = call.originalItem?.let {
            val visItem = convertToVisItem(it, isModOrEnum = false, forceCfgDisabledVisibility = false)
            Triple(visItem, it.namespaces, it.procMacroKind)
        }
        context.context.macroCalls += ProcMacroCallInfo(
            modData,
            macroIndex,
            attrs,
            call.body,
            call.bodyHash,
            macroDepth,
            dollarCrateMap,
            call.endOfAttrsOffset,
            call.fixupRustSyntaxErrors,
            originalItem
        )
    }

    override fun collectMacroDef(def: MacroDefLight) {
        val bodyHash = def.bodyHash ?: return
        val macroPath = modData.path.append(def.name)
        val macroIndex = parentMacroIndex.append(def.macroIndexInParent)

        val defInfo = DeclMacroDefInfo(
            modData.crate,
            macroPath,
            macroIndex,
            def.body,
            bodyHash,
            def.hasMacroExport,
            def.hasLocalInnerMacros,
            def.hasRustcBuiltinMacro,
            project
        )
        modData.addLegacyMacro(def.name, defInfo)
        legacyMacros[def.name] = defInfo

        if (def.hasMacroExport && !context.isHangingMode) {
            val visibility = Visibility.Public
            val visItem = VisItem(macroPath, visibility)
            val perNs = PerNs.macros(visItem)
            onAddItem(defMap.root, def.name, perNs, visibility)
        }
    }

    override fun collectMacro2Def(def: Macro2DefLight) {
        // save macro body for later use
        modData.macros2[def.name] = DeclMacro2DefInfo(
            crate = modData.crate,
            path = modData.path.append(def.name),
            def.body,
            def.bodyHash,
            def.hasRustcBuiltinMacro,
            project
        )

        // add macro to scope
        val visibility = convertVisibility(def.visibility, isDeeplyEnabledByCfg = true)
        val visItem = VisItem(modData.path.append(def.name), visibility)
        val perNs = PerNs.macros(visItem)
        onAddItem(modData, def.name, perNs, visibility)
    }

    /**
     * Propagates macro defs expanded from `foo!()` to `mod2`:
     * ```
     * mod mod1;
     * foo!();
     * mod mod2;
     * ```
     */
    private fun propagateLegacyMacros(modData: ModData) {
        if (legacyMacros.isEmpty()) return
        for (childMod in modData.childModules.values) {
            if (!childMod.isEnum && MacroIndex.shouldPropagate(parentMacroIndex, childMod.macroIndex)) {
                childMod.visitDescendants {
                    it.addLegacyMacros(legacyMacros)
                }
            }
        }
        if (modData.hasMacroUse) {
            val parent = modData.parent ?: return
            parent.addLegacyMacros(legacyMacros)
            propagateLegacyMacros(parent)
        }
    }

    private fun convertVisibility(visibility: VisibilityLight, isDeeplyEnabledByCfg: Boolean): Visibility {
        if (!isDeeplyEnabledByCfg) return Visibility.CfgDisabled
        return when (visibility) {
            VisibilityLight.Public -> Visibility.Public
            VisibilityLight.RestrictedCrate -> defMap.root.visibilityInSelf
            VisibilityLight.Private -> modData.visibilityInSelf
            is VisibilityLight.Restricted -> {
                resolveRestrictedVisibility(visibility.inPath, modData) ?: defMap.root.visibilityInSelf
            }
        }
    }

    /** See also [processModDeclResolveVariants] */
    private fun resolveModDecl(name: String, pathAttribute: String?, parentOwnedDirectory: VirtualFile?): RsFile? {
        val (parentDirectory, fileNames) = if (pathAttribute == null) {
            val fileNames = arrayOf("$name.rs", "$name/mod.rs")
            if (parentOwnedDirectory == null) return null
            parentOwnedDirectory to fileNames
        } else {
            // https://doc.rust-lang.org/reference/items/modules.html#the-path-attribute
            val parentDirectory = if (modData.isRsFile) {
                // For path attributes on modules not inside inline module blocks,
                // the file path is relative to the directory the source file is located.
                val containingFile = modData.asVirtualFile() ?: return null
                containingFile.parent
            } else {
                // Paths for path attributes inside inline module blocks are relative to
                // the directory of file including the inline module components as directories.
                parentOwnedDirectory
            } ?: return null
            val explicitPath = FileUtil.toSystemIndependentName(pathAttribute)
            parentDirectory to arrayOf(explicitPath)
        }

        val virtualFiles = fileNames.mapNotNull { parentDirectory.findFileByMaybeRelativePath(it) }
        // Note: It is possible that [virtualFiles] is not empty,
        // but result is null, when e.g. file is too big (thus will be [PsiFile] and not [RsFile])
        if (virtualFiles.isEmpty() && !context.isHangingMode) {
            for (fileName in fileNames) {
                val path = try {
                    parentDirectory.pathAsPath.resolve(fileName)
                } catch (ignored: InvalidPathException) {
                    continue
                }
                defMap.missedFiles.add(path)
            }
        }
        return virtualFiles.singleOrNull()?.toPsiFile(project) as? RsFile
    }
}

@Suppress("UNCHECKED_CAST")
private fun RsItemsOwner.getOrBuildStub(): StubElement<out RsItemsOwner>? {
    if (this is RsFileBase) return getOrBuildFileStub()
    /**
     * Note that [greenStub] and [buildStub] have consistent (equal) [RsPathStub.startOffset].
     * See also [createDollarCrateHelper].
     */
    (this as? StubBasedPsiElement<*>)?.greenStub?.let { return it as StubElement<out RsItemsOwner> }
    if (this is RsBlock) return buildStub()
    if (this is RsModItem) {
        val containingFile = containingFile as? RsFile ?: return null
        // In theory `greenStub` can be null after `calcStubTree` (it is linked to PSI with soft references)
        val stubIndex = PsiAnchor.calcStubIndex(this)
        if (stubIndex == -1) return null
        return containingFile.calcStubTree().plainList[stubIndex] as StubElement<out RsItemsOwner>
    }
    return null
}

fun RsFileBase.getOrBuildFileStub(): RsFileStub? {
    val greenStubTree = greenStubTree
    val stub = when {
        greenStubTree != null -> greenStubTree.root
        this is RsFile -> calcStubTree().root
        // We don't attach [stub] to PSI for [RsReplCodeFragment]
        else -> RsFileStub.Type.builder.buildStubTree(this)
    } as? RsFileStub
    if (stub == null) RESOLVE_LOG.error("No stub for file ${viewProvider.virtualFile.path}")
    return stub
}

// `#[macro_use] extern crate <name>;` - import macros
fun CrateDefMap.importExternCrateMacros(externCrateName: String) {
    val externCrateDefMap = resolveExternCrateAsDefMap(externCrateName) ?: return
    importAllMacrosExported(externCrateDefMap)
}

// https://doc.rust-lang.org/reference/visibility-and-privacy.html#pubin-path-pubcrate-pubsuper-and-pubself
private fun resolveRestrictedVisibility(path: Array<String>, containingMod: ModData): Visibility.Restricted? {
    // Note: complex cases like `pub(in self::super::foo)` are not supported
    return if (path.all { it == "super" }) {
        val modData = containingMod.getNthParent(path.size)
        modData?.visibilityInSelf
    } else {
        // Note: Can't use `childModules` here, because it will not contain our parent modules (yet)
        val parents = containingMod.parents.toMutableList()
        parents.reverse()
        parents.getOrNull(path.size)
            ?.takeIf { path.contentEquals(it.path.segments) }
            ?.visibilityInSelf
    }
}

private fun ModData.getOwnedDirectory(): VirtualFile? {
    val ownedDirectoryId = ownedDirectoryId ?: return null
    return PersistentFS.getInstance().findFileById(ownedDirectoryId)
}

private fun ModData.asVirtualFile(): VirtualFile? {
    check(isRsFile)
    return PersistentFS.getInstance().findFileById(fileId ?: return null)
        ?: run {
            RESOLVE_LOG.error("Can't find VirtualFile for $this")
            return null
        }
}

private sealed class ChildMod(val name: String, val hasMacroUse: Boolean) {
    class Inline(val mod: RsModItemStub, name: String, hasMacroUse: Boolean) : ChildMod(name, hasMacroUse)
    class File(val file: RsFile, name: String, hasMacroUse: Boolean) : ChildMod(name, hasMacroUse)
}

/**
 * Have to pass [pathAttribute], because [RsFile.pathAttribute] triggers resolve.
 * See also: [RsMod.getOwnedDirectory]
 */
private fun ChildMod.getOwnedDirectory(
    parentMod: ModData,
    parentOwnedDirectory: VirtualFile?,
    pathAttribute: String?
): VirtualFile? {
    if (this is ChildMod.File && name == RsConstants.MOD_RS_FILE) return file.virtualFile.parent

    val (parentDirectory, path) = if (pathAttribute != null) {
        when {
            this is ChildMod.File -> return file.virtualFile.parent
            parentMod.isRsFile -> parentMod.asVirtualFile()?.parent to pathAttribute
            else -> parentOwnedDirectory to pathAttribute
        }
    } else {
        parentOwnedDirectory to name
    }
    if (parentDirectory == null) return null

    // Don't use `FileUtil#getNameWithoutExtension` to correctly process relative paths like `./foo`
    val directoryPath = FileUtil.toSystemIndependentName(path).removeSuffix(".${RsFileType.defaultExtension}")
    return parentDirectory.findFileByMaybeRelativePath(directoryPath)
}

fun recordChildFileInUnusualLocation(parent: ModData, childFileId: FileId) {
    val persistentFS = PersistentFS.getInstance()
    val childFile = persistentFS.findFileById(childFileId) ?: return
    val childDirectory = childFile.parent ?: return
    for (modData in parent.parents) {
        val containedDirectory = persistentFS.findFileById(modData.directoryContainedAllChildFiles ?: continue) ?: continue
        if (VfsUtil.isAncestor(containedDirectory, childDirectory, false)) return
        val commonAncestor = VfsUtil.getCommonAncestor(containedDirectory, childDirectory) ?: continue
        modData.directoryContainedAllChildFiles = commonAncestor.fileId
    }
}
