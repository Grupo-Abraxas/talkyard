/**
 * Copyright (c) 2014-2017 Kaj Magnus Lindberg
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

package controllers

import com.debiki.core._
import com.debiki.core.Prelude._
import com.github.benmanes.caffeine
import com.mohiva.play.silhouette
import com.mohiva.play.silhouette.impl.providers.oauth1.services.PlayOAuth1Service
import com.mohiva.play.silhouette.impl.providers.oauth1.TwitterProvider
import com.mohiva.play.silhouette.impl.providers.oauth2._
import com.mohiva.play.silhouette.impl.providers._
import ed.server.spam.SpamChecker
import debiki._
import debiki.EdHttp._
import ed.server._
import ed.server.http._
import javax.inject.Inject
import org.scalactic.{Bad, ErrorMessage, Good, Or}
import play.api.libs.json._
import play.{api => p}
import play.api.mvc._
import play.api.Configuration
import scala.concurrent.Future
import scala.concurrent.duration._



/** OpenAuth 1 and 2 login, provided by Silhouette, e.g. for Google, Facebook and Twitter.
  *
  * This class is a bit complicated, because it supports logging in at site X
  * via another site, say login.domain.com. This is needed because Debiki is a multitenant
  * system, but OAuth providers allow one to login only via *one* single domain. That
  * single domain is login.domain.com, and if you want to login at site X this class
  * redirects you to login.domain.com, then logs you in at the OAuth provider from
  * login.domain.com, and redirects you back to X with a session id and an XSRF token.
  */
class LoginWithOpenAuthController @Inject()(cc: ControllerComponents, edContext: EdContext)
  extends EdController(cc, edContext) {

  import context.globals
  import context.security._

  private val Separator = '|'
  private val ReturnToUrlCookieName = "dwCoReturnToUrl"
  private val ReturnToSiteCookieName = "dwCoReturnToSite"
  private val ReturnToSiteXsrfTokenCookieName = "dwCoReturnToSiteXsrfToken"
  private val IsInLoginWindowCookieName = "dwCoIsInLoginWindow"
  private val IsInLoginPopupCookieName = "dwCoIsInLoginPopup"
  private val MayCreateUserCookieName = "dwCoMayCreateUser"
  private val AuthStateCookieName = "dwCoOAuth2State"

  private val CookiesToDiscardAfterLogin: Seq[DiscardingCookie] = Seq(
    ReturnToUrlCookieName, ReturnToSiteCookieName, ReturnToSiteXsrfTokenCookieName,
    IsInLoginWindowCookieName, IsInLoginPopupCookieName, MayCreateUserCookieName,
    AuthStateCookieName).map(DiscardingSecureCookie)

  def conf: Configuration = globals.rawConf

  private val cache = caffeine.cache.Caffeine.newBuilder()
    .maximumSize(20*1000)  // change to config value, e.g. 1e9 = 1GB mem cache. Default to 50M?
    // Don't expire too quickly — the user needs time to choose & typ a username.
    // SECURITY COULD expire sooner (say 10 seconds) if just logging in, because then
    // the user need not think or type anything.
    // The user might want to review the Terms of Use, so wait for an hour, here. [4WHKTP06]
    // BUG SHOULD use Redis, so the key won't disappear after server restart.
    .expireAfterWrite(65, java.util.concurrent.TimeUnit.MINUTES)
    .build().asInstanceOf[caffeine.cache.Cache[String, OpenAuthDetails]]


  def startAuthentication(provider: String, returnToUrl: String): Action[Unit] =
        AsyncGetActionIsLogin { request =>
    startAuthenticationImpl(provider, returnToUrl, request)
  }


  def startAuthenticationImpl(provider: String, returnToUrl: String, request: GetRequest)
        : Future[Result] = {
    globals.loginOriginConfigErrorMessage foreach { message =>
      throwInternalError("DwE5WKU3", message)
    }
    var futureResult = authenticate(provider, request)
    if (returnToUrl.nonEmpty) {
      futureResult = futureResult map { result =>
        result.withCookies(
          SecureCookie(name = ReturnToUrlCookieName, value = returnToUrl, httpOnly = false))
      }
    }
    if (request.rawQueryString.contains("isInLoginPopup")) {
      futureResult = futureResult map { result =>
        result.withCookies(
          SecureCookie(name = IsInLoginPopupCookieName, value = "true", httpOnly = false))
      }
    }
    if (request.rawQueryString.contains("mayNotCreateUser")) {
      futureResult = futureResult map { result =>
        result.withCookies(
          SecureCookie(name = MayCreateUserCookieName, value = "false", httpOnly = false))
      }
    }
    futureResult
  }


  def finishAuthentication(provider: String): Action[Unit] = AsyncGetActionIsLogin { request =>
    authenticate(provider, request)
  }


  /** Authenticates a user against e.g. Facebook or Google or Twitter, using OAuth 1 or 2.
    *
    * Based on:
    *   https://github.com/mohiva/play-silhouette-seed/blob/master/
    *                     app/controllers/SocialAuthController.scala#L32
    */
  private def authenticate(providerName: String, request: GetRequest): Future[Result] = {

    if (globals.anyLoginOrigin.map(_ == originOf(request)).contains(false)) {
      // OAuth providers have been configured to send authentication data to another
      // origin (namely anyLoginOrigin.get); we need to redirect to that origin
      // and login from there.
      return loginViaLoginOrigin(providerName, request.underlying)
    }
    def settings = request.siteSettings
    val provider: SocialProvider with CommonSocialProfileBuilder = providerName match {
      case FacebookProvider.ID =>
        throwForbiddenIf(!settings.enableFacebookLogin, "TyE0FBLOGIN", "Facebook login disabled")
        facebookProvider()
      case GoogleProvider.ID =>
        throwForbiddenIf(!settings.enableGoogleLogin, "TyE0GOOGLOGIN", "Google login disabled")
        googleProvider()
      case TwitterProvider.ID =>
        throwForbiddenIf(!settings.enableTwitterLogin, "TyE0TWTTRLOGIN", "Twitter login disabled")
        twitterProvider()
      case GitHubProvider.ID =>
        throwForbiddenIf(!settings.enableGitHubLogin, "TyE0GITHLOGIN", "GitHub login disabled")
        githubProvider()
      case x =>
        return Future.successful(Results.Forbidden(s"Bad provider: `$providerName' [DwE2F0D6]"))
    }
    provider.authenticate()(request.underlying) flatMap {
      case Left(result) =>
        Future.successful(result)
      case Right(authInfo) =>
        val futureProfile: Future[CommonSocialProfile] = provider.retrieveProfile(authInfo)
        futureProfile flatMap { profile =>
          handleAuthenticationData(request, profile)
        }
    } recoverWith {
      case e: silhouette.api.exceptions.ProviderException =>
        Future.successful(Results.Forbidden(s"${e.getMessage} [DwE39DG42]"))
    }
  }


  private def handleAuthenticationData(request: GetRequest, profile: CommonSocialProfile)
        : Future[Result] = {
    p.Logger.debug(s"OAuth data received at ${originOf(request)}: $profile")

    val (anyReturnToSiteOrigin: Option[String], anyReturnToSiteXsrfToken: Option[String]) =
      request.cookies.get(ReturnToSiteCookieName) match {
        case None => (None, None)
        case Some(cookie) =>
          val (originalSiteOrigin, separatorAndXsrfToken) = cookie.value.span(_ != Separator)
          (Some(originalSiteOrigin), Some(separatorAndXsrfToken.drop(1)))
      }

    val anyReturnToUrl = request.cookies.get(ReturnToUrlCookieName).map(_.value)

    if (anyReturnToSiteOrigin.isDefined && anyReturnToUrl.isDefined) {
      // Someone has two browser tabs open? And in one tab s/he attempts to login at one site,
      // and in another tab at the site at anyLoginDomain? Don't know which login attempt
      // to continue with.
      val errorMessage = i"""Parallel logins not supported. Cookies now cleared. Try again. [EdE07G32]
        |
        |Details: Both these were defined:
        |anyReturnToSiteOrigin = $anyReturnToSiteOrigin
        |anyReturnToUrl = $anyReturnToUrl"""
      // Delete the cookies, so if the user tries again, there'll be only one cookie and things
      // will work properly.
      return Future.successful(
        Forbidden(errorMessage).discardingCookies(
          DiscardingSecureCookie(ReturnToSiteCookieName),
          DiscardingSecureCookie(ReturnToUrlCookieName)))
    }

    val oauthDetails = OpenAuthDetails(
      providerId = profile.loginInfo.providerID,
      providerKey = profile.loginInfo.providerKey,
      firstName = profile.firstName,
      fullName = profile.fullName,
      email = profile.email,
      avatarUrl = profile.avatarURL)

    val result = anyReturnToSiteOrigin match {
      case Some(originalSiteOrigin) =>
        val xsrfToken = anyReturnToSiteXsrfToken getOrDie "DwE0F4C2"
        val oauthDetailsCacheKey = nextRandomString()
        SHOULD // use Redis instead, so logins won't fail because the app server was restarted.
        cache.put(oauthDetailsCacheKey, oauthDetails)
        val continueAtOriginalSiteUrl =
          originalSiteOrigin + routes.LoginWithOpenAuthController.continueAtOriginalSite(
            oauthDetailsCacheKey, xsrfToken)
        Redirect(continueAtOriginalSiteUrl)
          .discardingCookies(DiscardingSecureCookie(ReturnToSiteCookieName))
      case None =>
        login(request, anyOauthDetails = Some(oauthDetails))
    }

    Future.successful(result)
  }


  private def login(request: GetRequest, oauthDetailsCacheKey: Option[String] = None,
        anyOauthDetails: Option[OpenAuthDetails] = None): Result = {

    def cacheKey = oauthDetailsCacheKey.getOrDie("DwE90RW215")
    val oauthDetails: OpenAuthDetails =
      anyOauthDetails.getOrElse(Option(cache.getIfPresent(cacheKey)) match {
        case None => throwForbidden("DwE76fE50", "OAuth cache value not found")
        case Some(value) =>
          // Remove to prevent another login with the same key, in case it gets leaked,
          // e.g. via a log file.
          cache.invalidate(cacheKey)
          value.asInstanceOf[OpenAuthDetails]
      })

    val loginAttempt = OpenAuthLoginAttempt(
      ip = request.ip, date = globals.now().toJavaDate, oauthDetails)

    val mayCreateNewUserCookie = request.cookies.get(MayCreateUserCookieName)
    val mayCreateNewUser = !mayCreateNewUserCookie.map(_.value).contains("false")

    val dao = request.dao

    // COULD let tryLogin() return a LoginResult and use pattern matching, not exceptions.
    val result =
      try {
        val loginGrant = dao.tryLoginAsMember(loginAttempt)
        createCookiesAndFinishLogin(request, dao.siteId, loginGrant.user)
      }
      catch {
        case DbDao.IdentityNotFoundException =>
          // Let's check if the user already exists, and if so, create an OpenAuth identity
          // and connect it to the user.
          // Details: The user might exist, although no identity was found, if the user
          // has already 1) signed up as an email + password user, or 2) accepted an invitation
          // (when the user clicks the invitation link in the invite email, a user entry
          // is created automatically, without the user having to login). Or 3) signed up
          // via e.g. a Twitter account and specified a Google email address like
          // user@whatever.com (but not gmail.com) and then later attempts to log in
          // via this Google email address instead of via Twitter.
          // Or perhaps 4) signed up via a Facebook account that uses a Google address
          // like user@whatever.com (but not gmail.com).

          // Save canonical email? [canonical-email]

          oauthDetails.email.flatMap(dao.loadMemberByEmailOrUsername) match {
            case Some(user) =>
              if (providerHasVerifiedEmail(oauthDetails)) {
                val loginGrant = dao.createIdentityConnectToUserAndLogin(user, oauthDetails)
                createCookiesAndFinishLogin(request, dao.siteId, loginGrant.user)
              }
              else {
                // There is no reliable way of knowing that the current user is really
                // the same one as the old user in the database? We don't know if the
                // OpenAuth provider has verified the email address.
                // What we can do, is to:
                // A) instruct the user to 1) login as the user in the database
                // (say, via Twitter, in case 3 above). And then 2) click
                // an Add-OpenAuth/OpenID-account button, and then login again in the
                // way s/he attempted to do right now. Then, since the user is logged
                // in at both providers (e.g. both Twitter and Google, in case 3 above)
                // we can safely connect this new OpenAuth identity to the user account
                // already in the database. This is how StackOverflow does it.
                //  See: http://stackoverflow.com/questions/6487418/
                //                  how-to-handle-multiple-openids-for-the-same-user
                // Or B) Perhaps we can ask the user to login as the Twitter user directly?
                // From here, when already logged in with the oauthDetails.
                // (Instead of first logging in via Google then Twitter).
                // Or C) Or we could just send an email address verification email?
                // But then we'd reveal the existense of the Twitter account. And what if
                // the user clicks the confirmation link in the email account without really
                // understanding what s/he is doing? I think A) is safer.
                // Anyway, for now, simply:
                // (Use "user" for the provider's account, and "account" for the account in
                // this server)
                val emailAddress = oauthDetails.email.getOrDie("EsE2FPK8")
                throwForbidden("DwE7KGE32", "\n"+o"""You already have an account with email address
                  $emailAddress, and your ${oauthDetails.providerId} user has the same
                  email address. Since you already have an account here, please don't login via
                  ${oauthDetails.providerId} —
                  instead login using your original login method, e.g. ${
                    someProvidersExcept(oauthDetails.providerId)},
                  or username and password. — I hope you remember which one.""" +
                  "\n\n" +
                  o"""The reason I do not currently let you login via the
                  ${oauthDetails.providerId} user with email $emailAddress
                  is that I don't know if ${oauthDetails.providerId}
                  has verified that the email address is really yours — because if it is not,
                  then you would get access to someone else's account, if I did let you login.""" +
                  "\n\n")
                // If the user does *not* own the email address, s/he would be able to
                // impersonate another user, when his/her new account gets associated with
                // the old one just because they both claim to use the same email address.
              }
            case None =>
              if (!dao.getWholeSiteSettings().allowSignup) {
                throwForbidden("TyE0SIGNUP02", "Creation of new accounts is disabled")
              }
              else if (mayCreateNewUser) {
                showCreateUserDialog(request, oauthDetails)
              }
              else {
                // COULD show a nice error dialog instead.
                throwForbidden("DwE5FK9R2", o"""Access denied. You don't have an account
                    at this site with ${oauthDetails.providerId} login. And you may not
                    create a new account to access this resource.""")
              }
          }
      }

    result.discardingCookies(CookiesToDiscardAfterLogin: _*)
  }


  private def someProvidersExcept(providerId: String) =
    Seq(GoogleProvider.ID, FacebookProvider.ID, TwitterProvider.ID, GitHubProvider.ID)
      .filterNot(_ equalsIgnoreCase providerId).mkString(", ")


  private def providerHasVerifiedEmail(oauthDetails: OpenAuthDetails) = {
    // Don't know about Facebook and GitHub. Twitter has no emails at all. So for now:
    // (I'm fairly sure Google knows that each Gmail address is owned by the correct user.)
    oauthDetails.providerId == GoogleProvider.ID &&
      oauthDetails.email.exists(_ endsWith "@gmail.com")
  }


  private def createCookiesAndFinishLogin(request: DebikiRequest[_], siteId: SiteId, member: Member)
        : Result = {
    request.dao.pubSub.userIsActive(request.siteId, member, request.theBrowserIdData)
    val (_, _, sidAndXsrfCookies) = createSessionIdAndXsrfToken(siteId, member.id)

    val response =
      if (isAjax(request.underlying)) {
        // We've shown but closed an OAuth provider login popup, and now we're
        // handling a create-user Ajax request from a certain showCreateUserDialog()
        // Javascript dialog. It already knows about any pending redirects.
        OkSafeJson(Json.obj(
          "userCreatedAndLoggedIn" -> JsTrue,
          "emailVerifiedAndLoggedIn" -> JsBoolean(member.emailVerifiedAt.isDefined)))
      }
      else {
        val isInLoginPopup = request.cookies.get(IsInLoginPopupCookieName).nonEmpty
        def loginPopupCallback =
          Ok(views.html.login.loginPopupCallback().body) as HTML

        request.cookies.get(ReturnToUrlCookieName) match {
          case Some(returnToUrlCookie) =>
            if (returnToUrlCookie.value.startsWith(
                LoginWithPasswordController.RedirectFromVerificationEmailOnly)) {
              // We are to redirect only from new account email address verification
              // emails, not from here.
              loginPopupCallback
            }
            else if (isInLoginPopup) {
              loginPopupCallback
            }
            else {
              // We're in a create site wizard; redirect to the next step in the wizard.
              Redirect(returnToUrlCookie.value)
            }
          case None =>
            // We're logging in an existing user in a popup window.
            loginPopupCallback
        }
      }

    response.withCookies(sidAndXsrfCookies: _*)
  }


  private def showCreateUserDialog(request: GetRequest, oauthDetails: OpenAuthDetails): Result = {
    val cacheKey = nextRandomString()
    cache.put(cacheKey, oauthDetails)
    val anyIsInLoginWindowCookieValue = request.cookies.get(IsInLoginWindowCookieName).map(_.value)
    val anyReturnToUrlCookieValue = request.cookies.get(ReturnToUrlCookieName).map(_.value)

    val result = if (anyIsInLoginWindowCookieValue.isDefined) {
      // Continue running in the login window, by returning a complete HTML page that
      // shows a create-user dialog. (( This happens for example if 1) we're in a create
      // site wizard, then there's a dedicated login step in a login window, or 2)
      // we're logging in to the admin pages, or 3) we're visiting an embedded comments
      // site and attempted to login, then a login popup window opens (better than
      // showing a login dialog somewhere inside the iframe). ))
      Ok(views.html.login.showCreateUserDialog(
        SiteTpi(request),
        serverAddress = s"//${request.host}",
        newUserName = oauthDetails.displayName,
        newUserEmail = oauthDetails.email getOrElse "",
        authDataCacheKey = cacheKey,
        anyContinueToUrl = anyReturnToUrlCookieValue))
    }
    else {
      // The request is from an OAuth provider login popup. Run some Javascript in the
      // popup that closes the popup and continues execution in the main window (the popup's
      // window.opener).
      Ok(views.html.login.closePopupShowCreateUserDialog(
        providerId = oauthDetails.providerId,
        newUserName = oauthDetails.displayName,
        newUserEmail = oauthDetails.email getOrElse "",
        authDataCacheKey = cacheKey,
        anyContinueToUrl = anyReturnToUrlCookieValue))
    }

    result.discardingCookies(
      DiscardingSecureCookie(IsInLoginWindowCookieName),
      DiscardingSecureCookie(ReturnToUrlCookieName))
  }


  def handleCreateUserDialog: Action[JsValue] = AsyncPostJsonAction(
        RateLimits.CreateUser, maxBytes = 1000,
        // Could set isLogin = true instead, see handleCreateUserDialog(..) in
        // LoginWithPasswordController, + login-dialog.ts [5PY8FD2]
        allowAnyone = true) { request: JsonPostRequest =>
    val body = request.body

    val fullName = (body \ "fullName").asOptStringNoneIfBlank
    val emailAddress = (body \ "email").as[String].trim
    val username = (body \ "username").as[String].trim
    val anyReturnToUrl = (body \ "returnToUrl").asOpt[String]

    val oauthDetailsCacheKey = (body \ "authDataCacheKey").asOpt[String] getOrElse
      throwBadReq("DwE08GM6", "Auth data cache key missing")
    val oauthDetails = Option(cache.getIfPresent(oauthDetailsCacheKey)) match {
      case Some(details: OpenAuthDetails) =>
        // Don't remove the cache key here — maybe the user specified a username that's
        // in use already. Then hen needs to be able to submit again (using the same key).
        details
      case None =>
        throwForbidden("DwE50VC4", o"""Bad auth data cache key — this happens if you wait
             rather long (many minutes) with submitting the dialog.
             Or if the server was just restarted. Please try to sign up again.""")
      case _ =>
        die("TyE2GVM0")
    }

    val emailVerifiedAt = oauthDetails.email match {
      case Some(e) if e != emailAddress =>
        throwForbidden("DwE523FU2", "Cannot change email from ones' OAuth provider email")
      case Some(e) =>
        // Twitter and GitHub provide no email, or I don't know if any email has been verified.
        // Google and Facebook emails have been verified though.
        if (oauthDetails.providerId == GoogleProvider.ID ||
            oauthDetails.providerId == FacebookProvider.ID) {
          Some(request.ctime)
        }
        else {
          None
        }
      case None =>
        None
    }

    val dao = request.dao
    val siteSettings = dao.getWholeSiteSettings()

    // Some dupl code. [2FKD05]
    if (!siteSettings.requireVerifiedEmail && emailAddress.isEmpty) {
      // Fine.
    }
    else if (emailAddress.isEmpty) {
      throwUnprocessableEntity("EdE8JUK02", "Email address missing")
    }

    anyEmailAddressError(emailAddress) foreach { errMsg =>
      throwUnprocessableEntity("TyEBADEMLADR_-OAU", s"Bad email address: $errMsg")
    }

    if (ed.server.security.ReservedNames.isUsernameReserved(username)) // [5LKKWA10]
      throwForbidden("EdE4SWWB9", s"Username is reserved: '$username'; choose another username")

    globals.spamChecker.detectRegistrationSpam(request, name = username, email = emailAddress) map {
        isSpamReason =>
      SpamChecker.throwForbiddenIfSpam(isSpamReason, "EdE2KP89")

      val becomeOwner = LoginController.shallBecomeOwner(request, emailAddress)

      val userData = // [5LKKWA10]
        NewOauthUserData.create(name = fullName, username = username, email = emailAddress,
            emailVerifiedAt = emailVerifiedAt, identityData = oauthDetails,
            isAdmin = becomeOwner, isOwner = becomeOwner) match {
          case Good(data) => data
          case Bad(errorMessage) =>
            throwUnprocessableEntity("DwE7BD08", s"$errorMessage, please try again.")
        }

      val result = try {
        val loginGrant = dao.createIdentityUserAndLogin(userData, request.theBrowserIdData)
        val newMember = loginGrant.user
        dieIf(newMember.emailVerifiedAt != emailVerifiedAt, "EdE2WEP03")
        if (emailAddress.nonEmpty && emailVerifiedAt.isEmpty) {
          TESTS_MISSING // no e2e tests for this
          val email = LoginWithPasswordController.createEmailAddrVerifEmailLogDontSend(
              newMember, anyReturnToUrl, request.host, request.dao)
          globals.sendEmail(email, dao.siteId)
        }
        if (emailVerifiedAt.isDefined || siteSettings.mayPostBeforeEmailVerified) {
          createCookiesAndFinishLogin(request, request.siteId, loginGrant.user)
        }
        else {
          OkSafeJson(Json.obj(
            "userCreatedAndLoggedIn" -> JsFalse,
            "emailVerifiedAndLoggedIn" -> JsFalse))
        }
      }
      catch {
        case _: DbDao.DuplicateUsername =>
          throwForbidden(
              "DwE6D3G8", "Username already taken, please try again with another username")
        case _: DbDao.DuplicateUserEmail =>
          // BUG SHOULD support many users per email address, if mayPostBeforeEmailVerified.
          if (emailVerifiedAt.isDefined) {
            // The user has been authenticated, so it's okay to tell him/her about the email address.
            throwForbidden(
              "DwE4BME8", "You already have an account with that email address")
          }
          // Don't indicate that there is already an account with this email.
          LoginWithPasswordController.sendYouAlreadyHaveAnAccountWithThatAddressEmail(
            request.dao, emailAddress, siteHostname = request.host, siteId = request.siteId)
          OkSafeJson(Json.obj(
            "userCreatedAndLoggedIn" -> JsFalse,
            "emailVerifiedAndLoggedIn" -> JsFalse))
      }

      // Everything went fine. Won't need to submit the dialog again, so remove the cache key.
      cache.invalidate(oauthDetailsCacheKey)

      result.discardingCookies(CookiesToDiscardAfterLogin: _*)
    }
  }


  /** Redirects to and logs in via anyLoginOrigin; then redirects back to this site, with
    * a session id and xsrf token included in the GET request.
    */
  private def loginViaLoginOrigin(providerName: String, request: Request[Unit]): Future[Result] = {
    val xsrfToken = nextRandomString()
    val loginEndpoint =
      globals.anyLoginOrigin.getOrDie("DwE830bF1") +
        routes.LoginWithOpenAuthController.loginThenReturnToOriginalSite(
          providerName, returnToOrigin = originOf(request), xsrfToken)
    Future.successful(Redirect(loginEndpoint).withCookies(
      SecureCookie(name = ReturnToSiteXsrfTokenCookieName, value = xsrfToken, httpOnly = false)))
  }


  /** Logs in, then redirects back to returnToOrigin, and specifies xsrfToken to prevent
    * XSRF attacks and session fixation attacks.
    *
    * The request origin must be the anyLoginOrigin, because that's the origin that the
    * OAuth 1 and 2 providers supposedly have been configured to use.
    */
  def loginThenReturnToOriginalSite(provider: String, returnToOrigin: String, xsrfToken: String)
        : Action[Unit] = AsyncGetActionIsLogin { request =>
    // The actual redirection back to the returnToOrigin happens in handleAuthenticationData()
    // — it checks the value of the return-to-origin cookie.
    if (globals.anyLoginOrigin.map(_ == originOf(request)) != Some(true))
      throwForbidden(
        "DwE50U2", s"You need to login via the login origin, which is: `${globals.anyLoginOrigin}'")

    val futureResponse = authenticate(provider, request)
    futureResponse map { response =>
      response.withCookies(
        SecureCookie(name = ReturnToSiteCookieName, value = s"$returnToOrigin$Separator$xsrfToken",
          httpOnly = false))
    }
  }


  def continueAtOriginalSite(oauthDetailsCacheKey: String, xsrfToken: String): Action[Unit] =
        GetActionIsLogin { request =>
    val anyXsrfTokenInSession = request.cookies.get(ReturnToSiteXsrfTokenCookieName)
    anyXsrfTokenInSession match {
      case Some(xsrfCookie) =>
        if (xsrfCookie.value != xsrfToken)
          throwForbidden("DwE53FC9", "Bad XSRF token")
      case None =>
        throwForbidden("DwE7GCV0", "No XSRF cookie")
    }
    login(request, oauthDetailsCacheKey = Some(oauthDetailsCacheKey))
      .discardingCookies(DiscardingSecureCookie(ReturnToSiteXsrfTokenCookieName))
  }


  private val HttpLayer =
    new silhouette.api.util.PlayHTTPLayer(globals.wsClient)

  private val authStuffSigner = new silhouette.crypto.JcaSigner(
    silhouette.crypto.JcaSignerSettings(
      key = globals.applicationSecret, pepper = "sil-pepper-kfw93KPUF02wF"))

  private val Crypter = new silhouette.crypto.JcaCrypter(
    silhouette.crypto.JcaCrypterSettings(key = globals.applicationSecret))

  private def csrfStateItemHandler = new silhouette.impl.providers.state.CsrfStateItemHandler(
    silhouette.impl.providers.state.CsrfStateSettings(
      cookieName = AuthStateCookieName, cookiePath = "/", cookieDomain = None,
      secureCookie = globals.secure, httpOnlyCookie = true, expirationTime = 5 minutes),
    new silhouette.impl.util.SecureRandomIDGenerator(),
    authStuffSigner)

  private val socialStateHandler =
    new silhouette.impl.providers.DefaultSocialStateHandler(
      Set(csrfStateItemHandler), authStuffSigner)

  private val OAuth1TokenSecretProvider =
    new silhouette.impl.providers.oauth1.secrets.CookieSecretProvider(
      silhouette.impl.providers.oauth1.secrets.CookieSecretSettings(
        cookieName = "dwCoOAuth1TokenSecret", secureCookie = globals.secure),
      authStuffSigner,
      Crypter,
      silhouette.api.util.Clock())


  private def googleProvider(): GoogleProvider with CommonSocialProfileBuilder =
    new GoogleProvider(HttpLayer, socialStateHandler,
      getOrThrowDisabled(globals.socialLogin.googleOAuthSettings))


  private def facebookProvider(): FacebookProvider with CommonSocialProfileBuilder =
    new FacebookProvider(HttpLayer, socialStateHandler,
      getOrThrowDisabled(globals.socialLogin.facebookOAuthSettings))

  private def twitterProvider(): TwitterProvider with CommonSocialProfileBuilder = {
    val settings = getOrThrowDisabled(globals.socialLogin.twitterOAuthSettings)
    new TwitterProvider(
      HttpLayer, new PlayOAuth1Service(settings), OAuth1TokenSecretProvider, settings)
  }

  private def githubProvider(): GitHubProvider with CommonSocialProfileBuilder =
    new GitHubProvider(HttpLayer, socialStateHandler,
      getOrThrowDisabled(globals.socialLogin.githubOAuthSettings))


  private def getOrThrowDisabled[A](anySettings: A Or ErrorMessage): A = anySettings match {
    case Good(settings) => settings
    case Bad(errorMessage) => throwForbidden("EsE5YFK02", errorMessage)
  }

}
