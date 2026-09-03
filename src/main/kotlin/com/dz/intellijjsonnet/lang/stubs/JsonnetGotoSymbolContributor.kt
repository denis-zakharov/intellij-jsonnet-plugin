package com.dz.intellijjsonnet.lang.stubs

import com.dz.intellijjsonnet.lang.psi.JsonnetBind
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex

/**
 * "Navigate > Symbol" over the stub index (see [JsonnetBindIndex]/
 * [JsonnetFieldIndex]) — the concrete, user-visible feature the stub-index
 * layer exists to power. Find Usages/Rename don't need this index (they
 * already work off IntelliJ's default reference search, per the plan doc's
 * Phase 4 status), so this is where indexed lookups actually pay off: jumping
 * to a top-level `local`/field by name without opening every candidate file.
 */
class JsonnetGotoSymbolContributor : ChooseByNameContributor {

    override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> {
        val scope = searchScope(project, includeNonProjectItems)
        val names = LinkedHashSet<String>()
        StubIndex.getInstance().processAllKeys(JsonnetBindIndex.KEY, project) { names.add(it); true }
        StubIndex.getInstance().processAllKeys(JsonnetFieldIndex.KEY, project) { names.add(it); true }
        return names.toTypedArray()
    }

    override fun getItemsByName(
        name: String,
        pattern: String,
        project: Project,
        includeNonProjectItems: Boolean,
    ): Array<NavigationItem> {
        val scope = searchScope(project, includeNonProjectItems)
        val items = ArrayList<NavigationItem>()
        StubIndex.getElements(JsonnetBindIndex.KEY, name, project, scope, JsonnetBind::class.java)
            .filterIsInstanceTo(items)
        StubIndex.getElements(JsonnetFieldIndex.KEY, name, project, scope, JsonnetField::class.java)
            .filterIsInstanceTo(items)
        return items.toTypedArray()
    }

    private fun searchScope(project: Project, includeNonProjectItems: Boolean): GlobalSearchScope =
        if (includeNonProjectItems) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
}
