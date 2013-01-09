/**
 * Copyright (c) 2012 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

import java.{util => ju}


object PageStuff {

  def forNewPage(path: PagePath, actions: Debate,
        publishDirectly: Boolean = false): PageStuff = {
    val meta = PageMeta.forNewPage(
      actions.id, creationDati = actions.oldestDati getOrElse new ju.Date,
      publishDirectly = publishDirectly)
    PageStuff(meta, path, actions)
  }

  def forNewEmptyPage(path: PagePath) = forNewPage(path, Debate(guid = "?"))

}


case class PageStuff( // COULD reneame to Page? if I rename Page to PageActions
                          // (well, rather, if I rename Debate to PageActions)
  meta: PageMeta,
  path: PagePath,
  actions: Debate) {

  if (path.pageId.isDefined) require(meta.pageId == path.pageId.get)
  else require(meta.pageId == "?")

  require(meta.pageId == actions.id)

  def id = meta.pageId
  def tenantId = path.tenantId

  def folder = path.folder
  def slug = path.pageSlug
  def idShownInUrl = path.showId

  def role = meta.pageRole
  def parentPageId = meta.parentPageId

  def hasIdAssigned = id != "?"

  def copyWithNewId(newId: String) =
    PageStuff(
      meta.copy(pageId = newId), path = path.copy(pageId = Some(newId)),
      actions = actions.copy(guid = newId))

}



object PageMeta {

  def forNewPage(
        pageId: String = "?",
        creationDati: ju.Date = new ju.Date,
        pageRole: PageRole = PageRole.Any,
        parentPageId: Option[String] = None,
        publishDirectly: Boolean = false) =
    PageMeta(
      pageId = pageId,
      creationDati = creationDati,
      modificationDati = creationDati,
      cachedPublTime = if (publishDirectly) Some(creationDati) else None,
      pageRole = pageRole,
      parentPageId = parentPageId,
      pageExists = false)

  def forChangedPage(originalMeta: PageMeta, changedPage: Debate): PageMeta = {
    require(changedPage.id == originalMeta.pageId)
    originalMeta.copy(
      cachedTitle = changedPage.titleText,
      modificationDati =
        changedPage.modificationDati getOrElse originalMeta.modificationDati)
  }

  case class AuthorInfo(roleId: String, displayName: String)
}



case class PageMeta(
  pageId: String,
  pageRole: PageRole = PageRole.Any,
  parentPageId: Option[String] = None,
  cachedTitle: Option[String] = None,
  creationDati: ju.Date,
  modificationDati: ju.Date,
  cachedPublTime: Option[ju.Date] = None,
  cachedSgfntMtime: Option[ju.Date] = None,
  cachedAuthors: List[PageMeta.AuthorInfo] = Nil,
  cachedCommentCount: Int = 0,
  pageExists: Boolean = true) {

  def status: PageStatus =
    if (cachedPublTime.isDefined) PageStatus.Published
    else PageStatus.Draft

}



sealed abstract class PageRole private(
  // Defaulting to `null`, since Any(Any, Any) causes a "constructor cannot
  // be passed a self reference" error.
  private val _parentRole: PageRole = null,
  private val _childRole: PageRole = null) {

  def parentRole: PageRole =
    if (_parentRole eq null) PageRole.Any else _parentRole

  def childRole: PageRole =
    if (_childRole eq null) PageRole.Any else _childRole
}


object PageRole {
  case object Any extends PageRole
  case object Homepage extends PageRole

  case object BlogMainPage extends PageRole(_childRole = BlogArticle)
  case object BlogArticle extends PageRole(_parentRole = BlogMainPage)

  case object ForumMainPage extends PageRole(_childRole = ForumThread)
  case object ForumThread extends PageRole(_parentRole = ForumMainPage)

  case object WikiMainPage extends PageRole(_childRole = WikiPage)
  case object WikiPage extends PageRole(_parentRole = WikiMainPage)
}


/* In the future: ?

sealed abstract class PageStatus

object PageStatus {
  case object Normal extends PageStatus
  case object Deleted extends PageStatus
  case object Purged extends PageStatus
}
*/


/**
 * The page status, see debiki-for-developers.txt #9vG5I.
 */
sealed abstract class PageStatus
object PageStatus {
  // COULD rename to PrivateDraft, becaus ... other pages with limited
  // visibility might be considered Drafts (e.g. pages submitted for review).
  case object Draft extends PageStatus
  //COULD rename to Normal, because access control rules might result in
  // it effectively being non-pulbished.
  case object Published extends PageStatus

  case object Deleted extends PageStatus
  val All = List(Draft, Published, Deleted)
}



