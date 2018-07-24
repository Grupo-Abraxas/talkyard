/**
 * Copyright (c) 2012-2017 Kaj Magnus Lindberg
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

package ed.server.http

import com.debiki.core._
import com.debiki.core.Prelude._
import debiki._
import debiki.RateLimits.NoRateLimits
import debiki.dao.LoginNotFoundException
import ed.server._
import ed.server.security._
import java.{util => ju}
import play.{api => p}
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import EdSecurity.NoCookiesHeaderName


/** Play Framework Actions for requests to Debiki's HTTP API.
  */
class PlainApiActions(
  val safeActions: SafeActions,
  val globals: Globals,
  val security: EdSecurity,
  val rateLimiter: RateLimiter) {

  import EdHttp._
  import security.DiscardingSecureCookie
  import security.DiscardingSessionCookie
  import safeActions.ExceptionAction

  def PlainApiAction[B](parser: BodyParser[B],
        rateLimits: RateLimits, allowAnyone: Boolean = false, isLogin: Boolean = false,
        isAvoidCookiesEndpoint: Boolean = false)
        : ActionBuilder[ApiRequest, B] =
    PlainApiActionImpl(parser, rateLimits, adminOnly = false, staffOnly = false,
        allowAnyone = allowAnyone, isLogin = isLogin, isAvoidCookiesEndpoint = isAvoidCookiesEndpoint)

  def PlainApiActionStaffOnly[B](parser: BodyParser[B]): ActionBuilder[ApiRequest, B] =
    PlainApiActionImpl(parser, NoRateLimits, adminOnly = false, staffOnly = true)

  def PlainApiActionAdminOnly[B](parser: BodyParser[B]): ActionBuilder[ApiRequest, B] =
    PlainApiActionImpl(parser, NoRateLimits, adminOnly = true, staffOnly = false)

  def PlainApiActionSuperAdminOnly[B](parser: BodyParser[B]): ActionBuilder[ApiRequest, B] =
    PlainApiActionImpl(parser, NoRateLimits, adminOnly = false, staffOnly = false,
        superAdminOnly = true)



  /** Checks the session id and xsrf token and looks up the user rate limits the endpoint.
    *
    * Throws Forbidden if this is a POST request with no valid xsrf token.
    * Creates a new xsrf token cookie, if there is none, or if it's invalid.
    *
    * Throws Forbidden, and deletes the session id cookie, if any login id
    * doesn't map to any login entry.
    * The SidStatusRequest.sidStatus passed to the action is either SidAbsent or a SidOk.
    */
  def PlainApiActionImpl[B](aParser: BodyParser[B],
        rateLimits: RateLimits, adminOnly: Boolean, staffOnly: Boolean,
        allowAnyone: Boolean = false,  // try to delete 'allowAnyone'? REFACTOR
        isAvoidCookiesEndpoint: Boolean = false,
        isLogin: Boolean = false, superAdminOnly: Boolean = false)
        : ActionBuilder[ApiRequest, B] =
      new ActionBuilder[ApiRequest, B] {

    override def parser: BodyParser[B] =
      aParser

    override implicit protected def executionContext: ExecutionContext =
      globals.executionContext

    def numOnly: Int = adminOnly.toZeroOne + superAdminOnly.toZeroOne + staffOnly.toZeroOne
    require(numOnly <= 1, "EsE4KYF02")
    require(!allowAnyone || numOnly == 0, "EsE8KU24K")

    override def composeAction[A](action: Action[A]): Action[A] = {
      ExceptionAction.async(action.parser) { request: Request[A] =>
        action(request)
      }
    }

    override def invokeBlock[A](request: Request[A], block: ApiRequest[A] => Future[Result])
        : Future[Result] = {

      val site = globals.lookupSiteOrThrow(request)

      // No-cookies is is for embedded comments. In an iframe, cookies frequently get blocked  [NOCOOKIES]
      // by Privacy Badger or iOS or no-3rd-party-cookies brower settings or whatever.
      // Therefore, when the embedded comments iframe page loads, isAvoidCookiesEndpoint is true
      // because we're using the embedded-comments url endpoint.
      // And, for subsequent requests — to *other* endpoints — the browser Javascript code
      // sets this no-cookies header, so we'll know, here, that we should avoid cookies.
      val hasCookiesAlready = request.cookies.nonEmpty
      val avoidCookies = isAvoidCookiesEndpoint || request.headers.get(NoCookiesHeaderName).is("true")
      val maySetCookies = hasCookiesAlready || !avoidCookies

      val (actualSidStatus, xsrfOk, newCookies) =
        security.checkSidAndXsrfToken(request, site.id, maySetCookies = maySetCookies)

      // Ignore and delete any broken session id cookie.
      val (mendedSidStatus, deleteSidCookie) =
        if (actualSidStatus.isOk) (actualSidStatus, false)
        else (SidAbsent, true)

      val (browserId, newBrowserIdCookie) =  // [5JKWQ21]
        if (!maySetCookies) {
          // Then use any xsrf token, if present? It stays the same at least until page reload,
          // and has the same format as any id cookie anyway, see timeDotRandomHashHash() [2AB85F2].
          // The token can be missing (empty) for GET requests [2WKA40].
          if (xsrfOk.value.nonEmpty)
            (Some(BrowserId(xsrfOk.value, isNew = false)), Nil)
          else
            (None, Nil)
        }
        else {
          security.getBrowserIdCookieMaybeCreate(request)
        }

      // Parts of `block` might be executed asynchronously. However any LoginNotFoundException
      // should happen before the async parts, because access control should be done
      // before any async computations are started. So I don't try to recover
      // any AsyncResult(future-result-that-might-be-a-failure) here.
      val resultOldCookies: Future[Result] =
        try {
          runBlockIfAuthOk(request, site, mendedSidStatus, xsrfOk, browserId, block)
        }
        catch {
          case _: LoginNotFoundException =>
            // This might happen if I manually deleted stuff from the
            // database during development, or if the server has fallbacked
            // to a standby database.
            throw ResultException(InternalErrorResult2(i"""
              |Internal error, please try again. For example, reload the page. [DwE034ZQ3]
              |
              |Details: A certain login id has become invalid. I just gave you a new id,
              |but you will probably need to login again.""")
              .discardingCookies(DiscardingSecureCookie(EdSecurity.SessionIdCookieName)))
        }

      val resultOkSid =
        if (newCookies.isEmpty && newBrowserIdCookie.isEmpty && !deleteSidCookie) {
          resultOldCookies
        }
        else {
          resultOldCookies map { result =>
            var resultWithCookies = result
              .withCookies(newCookies ::: newBrowserIdCookie: _*)
              .withHeaders(safeActions.MakeInternetExplorerSaveIframeCookiesHeader)
            if (deleteSidCookie) {
              resultWithCookies =
                resultWithCookies.discardingCookies(
                  DiscardingSecureCookie(EdSecurity.SessionIdCookieName))
            }
            resultWithCookies
          }
        }

      resultOkSid
    }


    def runBlockIfAuthOk[A](request: Request[A], site: SiteBrief, sidStatus: SidStatus,
          xsrfOk: XsrfOk, browserId: Option[BrowserId], block: ApiRequest[A] => Future[Result])
          : Future[Result] = {

      val dao = globals.siteDao(site.id)
      dao.perhapsBlockRequest(request, sidStatus, browserId)

      val anyUserMaybeSuspended = dao.getUserBySessionId(sidStatus)

      // Maybe the user was logged in in two different browsers, and deleted hens account
      // in one browser and got logged out there, only.
      if (anyUserMaybeSuspended.exists(_.isDeleted))
        return Future.successful(
          ForbiddenResult("TyEUSRDLD", "That account has been deleted")
            .discardingCookies(DiscardingSessionCookie))

      val isSuspended = anyUserMaybeSuspended.exists(_.isSuspendedAt(new ju.Date))

      if (isSuspended && request.method != "GET")
        return Future.successful(
            ForbiddenResult("TyESUSPENDED_", "Your account has been suspended")
              .discardingCookies(DiscardingSessionCookie))

      val anyUser =
        if (isSuspended) None
        else anyUserMaybeSuspended

      // Re the !superAdminOnly test: Do allow access for superadmin endpoints,
      // so they can reactivate this site, in case this site is the superadmin site itself.
      if (!superAdminOnly) site.status match {
        case SiteStatus.NoAdmin | SiteStatus.Active | SiteStatus.ReadAndCleanOnly =>
          // Fine
        case SiteStatus.HiddenUnlessStaff =>
          if (!anyUser.exists(_.isStaff) && !isLogin)
            throwLoginAsStaff(request)
        case SiteStatus.HiddenUnlessAdmin =>
          if (!anyUser.exists(_.isAdmin) && !isLogin)
            throwLoginAsAdmin(request)
        case SiteStatus.Deleted | SiteStatus.Purged =>
          throwForbidden("EdESITEGONE", "This site has been deleted.")
      }

      if (staffOnly && !anyUser.exists(_.isStaff) && !isLogin)
        throwLoginAsStaff(request)

      if (adminOnly && !anyUser.exists(_.isAdmin) && !isLogin)
        throwLoginAsAdmin(request)

      if (superAdminOnly) {
        globals.config.superAdmin.siteIdString match {
          case Some(siteId) if site.id.toString == siteId =>
            // Fine: this (i.e. 'site') is the superadmin site, so we're allowed to access
            // the superadmin endpoints.
          case Some(Whatever) =>
            if (globals.isProd)
              throwForbidden("EsE4KS2YR",
                s"The superadmin site id may not be set to '$Whatever' in prod mode")
          case Some(_) =>
            throwForbidden("EsE8Y0KR2", o"""This is not the superadmin site. This is site
                id ${site.id}, but the superadmin site has another id""")
          case None =>
            throwForbidden("EsE17KFE2", "No superadmin site id configured")
        }

        globals.config.superAdmin.hostname match {
          case Some(request.host) =>
            // Fine: we're accessing the superadmin endpoints via the correct hostname.
          case Some(Whatever) =>
            if (globals.isProd)
              throwForbidden("EsE5GKTS",
                s"The superadmin hostname may not be set to '$Whatever' in prod mode")
          case Some(superAdminHostname) =>
            throwForbidden(
              "EsE2KPU04", o"""Wrong hostname. Please instead access the super admin area
                  via: $superAdminHostname""")
          case None =>
            // Fine, this double check hasn't been enabled.
        }

        COULD /* Show this message in the login dialog somehow:
        def thatIs__ = o"""That is, as a user whose email address is listed in the
           '${Config.SuperAdminEmailAddressesPath}' config value in the config file 'play.conf'."""
           */

        if (globals.config.superAdmin.emailAddresses.isEmpty) {
          throwForbidden("EsE5KU02Y", o"""To access the super admin area, you first need to add
              your email address to the '${Config.SuperAdminEmailAddressesPath}' config value
              in the config file 'play.conf'. Thereafter, sign up or login as a user
              with that email.""")
        }

        anyUser match {
          case None =>
            throwLoginAsSuperAdmin(request)
          case Some(user) =>
            if (!globals.config.superAdmin.emailAddresses.contains(user.email))
              throwLoginAsSuperAdmin(request)
        }
      }

      if (!allowAnyone && !isLogin) {
        // ViewPageController has allow-anyone = true.
        val isXhr = isAjax(request)
        def goToHomepageOrIfXhrThen(block: => Unit) {
          if (isXhr) block
          else throwTemporaryRedirect("/")  ;COULD // throwLoginAsTo but undef error [5KUP02]
        }
        val siteSettings = dao.getWholeSiteSettings()

        if (!anyUser.exists(_.isApprovedOrStaff) && siteSettings.userMustBeApproved)
          goToHomepageOrIfXhrThen(throwForbidden("DwE4HKG5", "Not approved"))

        if (!anyUser.exists(_.isAuthenticated) && siteSettings.userMustBeAuthenticated)
          goToHomepageOrIfXhrThen(throwForbidden("DwE6JGY2", "Not authenticated"))

        if (anyUser.exists(_.isGuest) && !siteSettings.isGuestLoginAllowed && isXhr)
          throwForbidden("DwE7JYK4", o"""Guest access has been disabled, but you're logged in
            as a guest. Please sign up with a real account instead""")
      }

      val apiRequest = ApiRequest[A](
        site, sidStatus, xsrfOk, browserId, anyUser, dao, request)

      rateLimiter.rateLimit(rateLimits, apiRequest)

      // COULD use markers instead for site id and ip, and perhaps uri too? Dupl code [5KWC28]
      val requestUriAndIp = s"site $site, ip ${apiRequest.ip}: ${apiRequest.uri}"
      //p.Logger.debug(s"API request started [DwM6L8], " + requestUriAndIp)

      val timer = globals.metricRegistry.timer(request.path)
      val timerContext = timer.time()
      var result = try {
        block(apiRequest)
      }
      catch {
        case ex: ResultException =>
          // This is fine, probably just a 403 Forbidden exception or 404 Not Found, whatever.
          p.Logger.debug(
            s"API request result exception [EsE4K2J2]: $ex, $requestUriAndIp")
          throw ex
        case ex: Exception =>
          p.Logger.warn(s"API request unexpected exception [EsE4JYU0], $requestUriAndIp", ex)
          throw ex
      }
      finally {
        timerContext.stop()
      }

      result onComplete {
        case Success(_) =>
          //p.Logger.debug(
            //s"API request ended, status ${r.header.status} [DwM9Z2], $requestUriAndIp")
        case Failure(exception) =>
          p.Logger.debug(
            s"API request exception: ${classNameOf(exception)} [DwE4P7], $requestUriAndIp")
      }

      if (isSuspended) {
        // BUG: (old? can still happen?) We won't get here if e.g. a 403 Forbidden exception
        // was thrown because 'anyUser' was set to None. How solve that?
        result = result.map(_.discardingCookies(DiscardingSessionCookie))
      }
      result
    }
  }
}
