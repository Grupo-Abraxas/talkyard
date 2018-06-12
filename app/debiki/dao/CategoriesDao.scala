/**
 * Copyright (C) 2015 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package debiki.dao

import com.debiki.core._
import com.debiki.core.Prelude._
import debiki.EdHttp._
import debiki.{TextAndHtml, TextAndHtmlMaker}
import ed.server.auth.{Authz, ForumAuthzContext, MayMaybe}
import java.{util => ju}
import scala.collection.{immutable, mutable}
import scala.collection.mutable.ArrayBuffer




/** @param shallBeDefaultCategory — if set, the root category's default category id will be
  *     updated to point to this category.
  */
case class CategoryToSave(
  sectionPageId: PageId,
  parentId: CategoryId,
  name: String,
  slug: String,
  position: Int,
  // [refactor] [5YKW294] [rename] Should no longer be a list. Change db too, from "nnn,nnn,nnn" to single int.
  newTopicTypes: immutable.Seq[PageRole],
  shallBeDefaultCategory: Boolean,
  unlisted: Boolean,
  includeInSummaries: IncludeInSummaries,
  description: String,
  anyId: Option[CategoryId] = None, // Some() if editing, < 0 if creating COULD change from Option[CategoryId] to CategoryId
  isCreatingNewForum: Boolean = false) {

  require(anyId isNot NoCategoryId, "EdE5LKAW0")
  def isNewCategory: Boolean = anyId.exists(_ < 0)

  def makeAboutTopicTitle(textAndHtmlMaker: TextAndHtmlMaker): TextAndHtml =
    textAndHtmlMaker.forTitle(s"About the $name category")

  def makeAboutTopicBody(textAndHtmlMaker: TextAndHtmlMaker): TextAndHtml =
    textAndHtmlMaker.forBodyOrComment(description) // COULD follow links? Only staff can create categories [WHENFOLLOW]

  def makeCategory(id: CategoryId, createdAt: ju.Date) = Category(
    id = id,
    sectionPageId = sectionPageId,
    parentId = Some(parentId),
    defaultCategoryId = None,
    name = name,
    slug = slug,
    position = position,
    description = None,
    newTopicTypes = newTopicTypes,
    unlisted = unlisted,
    includeInSummaries = includeInSummaries,
    createdAt = createdAt,
    updatedAt = createdAt)

}


case class CreateCategoryResult(
  category: Category,
  pagePath: PagePath,
  permissionsWithIds: immutable.Seq[PermsOnPages])


/** Loads and saves categories.
  */
trait CategoriesDao {
  self: SiteDao =>


  // The dao shouldn't live past the current HTTP request anyway.
  private var categoriesById: Map[CategoryId, Category] = _
  private var categoriesByParentId: mutable.HashMap[CategoryId, ArrayBuffer[Category]] = _
  private var rootCategories: Seq[Category] = _


  /** BUG [4GWRQA28] For now, if many sub communities: Returns a random default category.
    */
  def getDefaultCategoryId(): CategoryId = {
    COULD_OPTIMIZE // remember default category, refresh when saving a root category?
    if (rootCategories eq null) {
      loadBuildRememberCategoryMaps()
      dieIf(rootCategories eq null, "TyE2PK50")
    }
    dieIf(rootCategories.isEmpty, "TyE2FWBK5")
    rootCategories.head.defaultCategoryId getOrDie "TyE2KQBP6"
  }


  /** List categories in the site section (forum/blog/whatever) at page pageId.
    * Sorts by Category.position (which doesn't make much sense if there are sub categories).
    * Returns (categories, default-category-id).
    */
  def listMaySeeSectionCategories(pageId: PageId, includeDelted: Boolean, authzCtx: ForumAuthzContext)
        : (Seq[Category], CategoryId) = {
    // A bit dupl code (7UKWTW1)
    loadRootCategory(pageId) match {
      case Some(rootCategory) =>
        val categories = listDescendantMaySeeCategories(rootCategory.id, includeRoot = false,
          includeDelted = includeDelted, authzCtx).sortBy(_.position)
        (categories, rootCategory.defaultCategoryId getOrDie "EsE4GK02")
      case None =>
        (Nil, NoCategoryId)
    }
  }


  /** OLD: Sometimes the section page id is undefined — for example, when talking with someone
    * in a personal chat. That chat topic isn't placed in any section (e.g. blog or forum).
    * NO???: Then we want to list all categories, not just all categories in some (undefined) section.
    *
    * Returns (categories, default-category-id). (There can be only 1 default category per sub community.)
    */
  def listAllMaySeeCategories(categoryId: CategoryId, authzCtx: ForumAuthzContext)
        : (Seq[Category], Option[CategoryId]) = {
    if (rootCategories eq null) {
      loadBuildRememberCategoryMaps()
      dieIf(rootCategories eq null, "EsE4KG0W2")
    }

    if (rootCategories.isEmpty)
      return (Nil, None)

    // A bit dupl code (7UKWTW1)
    val rootCategory = loadRootCategory(categoryId) getOrDie "TyEPKDRW0"
    val categories = listDescendantMaySeeCategories(rootCategory.id, includeRoot = false,
      includeDelted = authzCtx.isStaff, authzCtx).sortBy(_.position)
    (categories, Some(rootCategory.defaultCategoryId getOrDie "TyE5JKF2"))
  }


  /** List all categories in the sub tree with categoryId as root.
    */
  private def listDescendantMaySeeCategories(categoryId: CategoryId, includeRoot: Boolean,
        includeDelted: Boolean, authzCtx: ForumAuthzContext): Seq[Category] = {
    val categories = ArrayBuffer[Category]()
    appendMaySeeCategoriesInTree(categoryId, includeRoot = includeRoot, includeDelted = includeDelted,
      authzCtx, categories)
    categories.to[immutable.Seq]
  }


  /** Lists pages placed directly in one of categoryIds.
    */
  private def loadPagesDirectlyInCategories(categoryIds: Seq[CategoryId], pageQuery: PageQuery,
        limit: Int): Seq[PagePathAndMeta] = {
    readOnlyTransaction(_.loadPagesInCategories(categoryIds, pageQuery, limit))
  }


  /** Lists pages placed in categoryId, optionally including its descendant categories.
    */
  def loadMaySeePagesInCategory(categoryId: CategoryId, includeDescendants: Boolean,
        authzCtx: ForumAuthzContext, pageQuery: PageQuery, limit: Int)
        : Seq[PagePathAndMeta] = {
    val maySeeCategoryIds =
      if (includeDescendants) {
        // (Include the start ("root") category because it might not be the root of the
        // whole section (e.g. the whole forum) but only the root of a sub section (e.g.
        // a category in the forum, wich has sub categories). The top root shouldn't
        // contain any pages, but subtree roots usually contain pages.)
        listDescendantMaySeeCategories(categoryId, includeRoot = true,
            includeDelted = pageQuery.pageFilter.includeDeleted, authzCtx).map(_.id)
      }
      else {
        SECURITY // double-think-through this:
        // No this is fine, we're nowadays testing below, with Authz.maySeePage(..).
        // unimplementedIf(!authzCtx.isStaff, "!incl hidden in forum [EsE2PGJ4]")
        Seq(categoryId)
      }

    val okCategoryIds =
      if (pageQuery.pageFilter.filterType != PageFilterType.ForActivitySummaryEmail)
        maySeeCategoryIds
      else
        maySeeCategoryIds filter { id =>
          val category = categoriesById.get(id)
          category.map(_.includeInSummaries) isNot IncludeInSummaries.NoExclude
        }

    // Although we may see these categories, we might not be allowed to see all pages therein.
    // So, both per-category and per-page authz checks.
    val pagesInclForbidden = loadPagesDirectlyInCategories(okCategoryIds, pageQuery, limit)

    // For now. COULD do some of filtering in the db query instead, so won't find 0 pages
    // just because all most-recent-pages are e.g. hidden.
    val filteredPages = pagesInclForbidden filter { page =>
      val categories = loadAncestorCategoriesRootLast(page.categoryId)
      val may = ed.server.auth.Authz.maySeePage(
        page.meta,
        user = authzCtx.requester,
        groupIds = authzCtx.groupIds,
        pageMembers = getAnyPrivateGroupTalkMembers(page.meta),
        categoriesRootLast = categories,
        permissions = authzCtx.permissions,
        maySeeUnlisted = false) // pageQuery.pageFilter.includesUnlisted
      may == MayMaybe.Yes
    }

    filteredPages
  }


  def listMaySeeTopicsInclPinned(categoryId: CategoryId, pageQuery: PageQuery,
        includeDescendantCategories: Boolean, authzCtx: ForumAuthzContext, limit: Int)
        : Seq[PagePathAndMeta] = {
    // COULD instead of PagePathAndMeta use some "ListedPage" class that also includes  [7IKA2V]
    // the popularity score, + doesn't include stuff not needed to render forum topics etc.
    SECURITY; TESTS_MISSING  // securified

    val topics: Seq[PagePathAndMeta] = loadMaySeePagesInCategory(
      categoryId, includeDescendantCategories, authzCtx,
      pageQuery, limit)

    // If sorting by bump time, sort pinned topics first. Otherwise, don't.
    // (Could maybe show pinned topics if sorting by newest-first? Flarum & Discourse don't though.)
    val topicsInclPinned = pageQuery.orderOffset match {
      case orderOffset: PageOrderOffset.ByBumpTime if orderOffset.offset.isEmpty =>
        val pinnedTopics = loadMaySeePagesInCategory(
          categoryId, includeDescendantCategories, authzCtx,
          pageQuery.copy(orderOffset = PageOrderOffset.ByPinOrderLoadOnlyPinned), limit)
        val notPinned = topics.filterNot(topic => pinnedTopics.exists(_.id == topic.id))
        val topicsSorted = (pinnedTopics ++ notPinned) sortBy { topic =>
          val meta = topic.meta
          val pinnedGlobally = meta.pinWhere.contains(PinPageWhere.Globally)
          val pinnedInThisCategory = meta.isPinned && meta.categoryId.contains(categoryId)
          val isPinned = pinnedGlobally || pinnedInThisCategory
          if (isPinned) topic.meta.pinOrder.get // 1..100
          else Long.MaxValue - topic.meta.bumpedOrPublishedOrCreatedAt.getTime // much larger
        }
        topicsSorted
      case _ => topics
    }

    topicsInclPinned
  }


  def loadAncestorCategoriesRootLast(anyCategoryId: Option[CategoryId]): immutable.Seq[Category] = {
    val id = anyCategoryId getOrElse {
      return Nil
    }
    loadAncestorCategoriesRootLast(id)
  }


  def loadAncestorCategoriesRootLast(categoryId: CategoryId): immutable.Seq[Category] = {
    val categoriesById = loadBuildRememberCategoryMaps()._1
    val categories = ArrayBuffer[Category]()
    var current = categoriesById.get(categoryId)
    while (current.isDefined) {
      categories.append(current.get)
      current = current.get.parentId flatMap categoriesById.get
    }
    categories.to[immutable.Seq]
  }


  /** Returns (category, is-default).
    */
  def loadCategory(id: CategoryId): Option[(Category, Boolean)] = {
    val catsStuff = loadBuildRememberCategoryMaps()
    val anyCategory = catsStuff._1.get(id)
    anyCategory map { category =>
      val rootCategory: Option[Category] = category.parentId.flatMap(catsStuff._1.get)
      (category, rootCategory.flatMap(_.defaultCategoryId) is category.id)
    }
  }


  // Some time later: Add a site section page id? So will load the correct category, also
  // if there're many sub communities with the same category slug.
  def loadCategoryBySlug(slug: String): Option[Category] = {
    val catsStuff = loadBuildRememberCategoryMaps()
    catsStuff._1.values.find(_.slug == slug)
  }


  def loadTheCategory(id: CategoryId): (Category, Boolean) =
    loadCategory(id) getOrElse throwNotFound("DwE8YUF0", s"No category with id $id")


  def loadRootCategory(categoryId: CategoryId): Option[Category] =
    loadAncestorCategoriesRootLast(categoryId).lastOption


  def loadSectionPageId(categoryId: CategoryId): Option[PageId] =
    loadRootCategory(categoryId).map(_.sectionPageId)


  def loadTheSectionPageId(categoryId: CategoryId): PageId =
    loadRootCategory(categoryId).map(_.sectionPageId) getOrDie "DwE804K2"

  def loadSectionPageIdsAsSeq(): Seq[PageId] = {
    loadBuildRememberCategoryMaps()
    categoriesById.values.filter(_.parentId.isEmpty).map(_.sectionPageId).toSeq
  }


  def loadAboutCategoryPageId(categoryId: CategoryId): Option[PageId] = {
    readOnlyTransaction(_.loadAboutCategoryPageId(categoryId))
  }


  private def loadRootCategory(pageId: PageId): Option[Category] = {
    val categoriesById = loadBuildRememberCategoryMaps()._1
    for ((_, category) <- categoriesById) {
      if (category.sectionPageId == pageId && category.parentId.isEmpty)
        return Some(category)
    }
    None
  }


  private def appendMaySeeCategoriesInTree(rootCategoryId: CategoryId, includeRoot: Boolean,
      includeDelted: Boolean, authzCtx: ForumAuthzContext, categoryList: ArrayBuffer[Category]) {

    if (categoryList.exists(_.id == rootCategoryId)) {
      // COULD log cycle error
      return
    }

    val (categoriesById, categoriesByParentId) = loadBuildRememberCategoryMaps()
    val startCategory = categoriesById.getOrElse(rootCategoryId, {
      return
    })

    val categories = loadAncestorCategoriesRootLast(rootCategoryId)

    // (Skip the root category in this check; cannot set permissions on it. [0YWKG21])
    if (!categories.head.isRoot) {
      val may = Authz.maySeeCategory(authzCtx, categories)
      if (may.maySee isNot true)
        return
    }

    if (!includeDelted && startCategory.isDeleted)
      return

    COULD // add a seeUnlisted permission? If in a cat, a certain group should see unlisted topics.
    val onlyForStaff = startCategory.unlisted || startCategory.isDeleted  // [5JKWT42]
    if (onlyForStaff && !authzCtx.isStaff)
      return

    if (includeRoot)
      categoryList.append(startCategory)

    val childCategories = categoriesByParentId.getOrElse(rootCategoryId, {
      return
    })
    for (childCategory <- childCategories) {
      appendMaySeeCategoriesInTree(childCategory.id, includeRoot = true, includeDelted = includeDelted,
        authzCtx, categoryList)
    }
  }


  private def loadBuildRememberCategoryMaps(): (Map[CategoryId, Category],
        mutable.HashMap[CategoryId, ArrayBuffer[Category]]) = {
    if (categoriesById ne null)
      return (categoriesById, categoriesByParentId)

    categoriesByParentId = mutable.HashMap[CategoryId, ArrayBuffer[Category]]()
    categoriesById = loadCategoryMap()

    for ((_, category) <- categoriesById; parentId <- category.parentId) {
      val siblings = categoriesByParentId.getOrElseUpdate(parentId, ArrayBuffer[Category]())
      siblings.append(category)
    }

    rootCategories = categoriesById.values.filter(_.isRoot).toVector
    val anyRoot = categoriesById.values.find(_.isRoot)
    (categoriesById, categoriesByParentId)
  }

  COULD_OPTIMIZE // cache
  private def loadCategoryMap() =
    readOnlyTransaction(_.loadCategoryMap())


  def editCategory(editCategoryData: CategoryToSave, permissions: immutable.Seq[PermsOnPages],
        who: Who): Category = {
    val (oldCategory, editedCategory, permissionsChanged) = readWriteTransaction { transaction =>
      val categoryId = editCategoryData.anyId getOrDie "DwE7KPE0"
      val oldCategory = transaction.loadCategory(categoryId).getOrElse(throwNotFound(
        "DwE5FRA2", s"Category not found, id: $categoryId"))
      // Currently cannot change parent category because then topic counts will be wrong.
      // Could just remove all counts, who cares anyway
      require(oldCategory.parentId.contains(editCategoryData.parentId), "DwE903SW2")
      val editedCategory = oldCategory.copy(
        name = editCategoryData.name,
        slug = editCategoryData.slug,
        position = editCategoryData.position,
        newTopicTypes = editCategoryData.newTopicTypes,
        unlisted = editCategoryData.unlisted,
        includeInSummaries = editCategoryData.includeInSummaries,
        updatedAt = transaction.now.toJavaDate)

      if (editCategoryData.shallBeDefaultCategory) {
        setDefaultCategory(editedCategory, transaction)
      }

      transaction.updateCategoryMarkSectionPageStale(editedCategory)

      val permissionsChanged = addRemovePermsOnCategory(categoryId, permissions)(transaction)._2
      (oldCategory, editedCategory, permissionsChanged)
      // COULD create audit log entry
    }

    if (oldCategory.name != editedCategory.name || permissionsChanged) {
      // All pages in this category need to be regenerated, because the category name is
      // included on the pages. Or if permissions edited: hard to know which pages are affected,
      // so just empty the whole cache.
      emptyCache()
    }

    // Do this even if we just emptied the cache above, because then the forum page
    // will be regenerated earlier.
    refreshPageInMemCache(oldCategory.sectionPageId)
    refreshPageInMemCache(editedCategory.sectionPageId)

    editedCategory
  }


  def createCategory(newCategoryData: CategoryToSave, permissions: immutable.Seq[PermsOnPages],
        byWho: Who): CreateCategoryResult = {
    readWriteTransaction { transaction =>
      createCategoryImpl(newCategoryData, permissions, byWho)(transaction)
    }
  }


  def createCategoryImpl(newCategoryData: CategoryToSave, permissions: immutable.Seq[PermsOnPages],
        byWho: Who)(transaction: SiteTransaction): CreateCategoryResult = {

    val categoryId = transaction.nextCategoryId()  // [4GKWSR1]
    newCategoryData.anyId foreach { id =>
      if (id < 0) {
        // Fine, this means we're to choose an id here. The requester specifies
        // a category id < 0, so the permissions also being saved can identify the
        // category they are about.
      }
      else {
        dieIf(id == 0, "TyE2WBP8")
        dieIf(id != categoryId, "TyE4GKWRQ", o"""transaction.nextCategoryId() = $categoryId
            but newCategoryData.anyId is ${newCategoryData.anyId.get}, site id $siteId""")
      }
    }

    // Discourse currently has 28 categories so 65 is a lot.
    // Can remove this later, when I think I won't want to add more cat perms via db migrations.
    throwForbiddenIf(categoryId > 65, "EdE7LKG2", "Too many categories, > 65") // see [B0GKWU52]

    val category = newCategoryData.makeCategory(categoryId, transaction.now.toJavaDate)
    transaction.insertCategoryMarkSectionPageStale(category)

    val titleTextAndHtml = newCategoryData.makeAboutTopicTitle(textAndHtmlMaker)
    val bodyTextAndHtml = newCategoryData.makeAboutTopicBody(textAndHtmlMaker)

    val (aboutPagePath, _) = createPageImpl(
        PageRole.AboutCategory, PageStatus.Published, anyCategoryId = Some(categoryId),
        anyFolder = None, anySlug = Some("about-" + newCategoryData.slug), showId = true,
        titleSource = titleTextAndHtml.text,
        titleHtmlSanitized = titleTextAndHtml.safeHtml,
        bodySource = bodyTextAndHtml.text,
        bodyHtmlSanitized = bodyTextAndHtml.safeHtml,
        pinOrder = Some(ForumDao.AboutCategoryTopicPinOrder),
        pinWhere = Some(PinPageWhere.InCategory),
        byWho, spamRelReqStuff = None, transaction)

    if (newCategoryData.shallBeDefaultCategory) {
      setDefaultCategory(category, transaction)
    }

    permissions foreach { p =>
      dieIf(p.onCategoryId != newCategoryData.anyId, "EdE7UKW02")
    }
    val permsWithCatId = permissions.map(_.copy(onCategoryId = Some(categoryId)))
    val permsWithId = addRemovePermsOnCategory(categoryId, permsWithCatId)(transaction)._1

    // COULD create audit log entry

    // The forum needs to be refreshed because it has cached the category list
    // (in JSON in the cached HTML).
    if (!newCategoryData.isCreatingNewForum) {
      refreshPageInMemCache(category.sectionPageId)
    }

    CreateCategoryResult(category, aboutPagePath, permsWithId)
  }


  def deleteUndeleteCategory(categoryId: CategoryId, delete: Boolean, who: Who) {
    readWriteTransaction { transaction =>
      throwForbiddenIf(!transaction.isAdmin(who.id), "EdEGEF239S", "Not admin")
      val categoryBefore = transaction.loadCategory(categoryId) getOrElse {
        throwNotFound("EdE5FK8E2", s"No category with id $categoryId")
      }
      val categoryAfter = categoryBefore.copy(
        deletedAt = if (delete) Some(transaction.now.toJavaDate) else None)
      transaction.updateCategoryMarkSectionPageStale(categoryAfter)
    }
    // All pages in the category now needs to be rerendered.
    COULD_OPTIMIZE // only remove-from-cache / mark-as-dirty pages inside the category.
    emptyCache()
  }


  private def setDefaultCategory(category: Category, transaction: SiteTransaction) {
    val rootCategoryId = category.parentId getOrDie "EsE2PK8O4"
    val rootCategory = transaction.loadCategory(rootCategoryId) getOrDie "EsE5KG02"
    if (rootCategory.defaultCategoryId.contains(category.id))
      return
    val rootWithNewDefault = rootCategory.copy(defaultCategoryId = Some(category.id))
    // (The section page will be marked as stale anyway, doesn't matter if we do it here too.)
    transaction.updateCategoryMarkSectionPageStale(rootWithNewDefault)
  }


  private def addRemovePermsOnCategory(categoryId: CategoryId,
        permissions: immutable.Seq[PermsOnPages])(transaction: SiteTransaction)
        : (immutable.Seq[PermsOnPages], Boolean) = {
    dieIf(permissions.exists(_.onCategoryId.isNot(categoryId)), "EdE2FK0YU5")
    val permsWithIds = ArrayBuffer[PermsOnPages]()
    val oldPermissionsById: mutable.Map[PermissionId, PermsOnPages] =
      transaction.loadPermsOnCategory(categoryId).map(p => (p.id, p))(collection.breakOut)
    var wasChangesMade = false
    permissions foreach { permission =>
      var alreadyExists = false
      if (permission.id >= PermissionAlreadyExistsMinId) {
        oldPermissionsById.remove(permission.id) foreach { oldPerm =>
          alreadyExists = true
          if (oldPerm != permission) {
            wasChangesMade = true
            if (permission.isEverythingUndefined) {
              // latent BUG: not incl info about this deleted perm in the fn result [0YKAG25L]
              transaction.deletePermsOnPages(Seq(permission.id))
            }
            else {
              transaction.updatePermsOnPages(permission)
              permsWithIds.append(permission)
            }
          }
        }
      }
      if (!alreadyExists) {
        wasChangesMade = true
        val permWithId = transaction.insertPermsOnPages(permission)
        permsWithIds.append(permWithId)
      }
    }
    // latent BUG: not incl info about these deleted perms in the fn result [0YKAG25L]
    transaction.deletePermsOnPages(oldPermissionsById.keys)
    wasChangesMade ||= oldPermissionsById.nonEmpty

    (permsWithIds.toVector, wasChangesMade)
  }
}



object CategoriesDao {

  val CategoryDescriptionSource =  // [i18n]
    i"""[Replace this paragraph with a description of the category. Keep it short;
       |the description will be shown on the category list page.]
       |
       |Here, after the first paragraph, you can add more details about the category.
       |"""

}

