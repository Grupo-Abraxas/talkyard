/**
 * Copyright (c) 2012, 2018 Kaj Magnus Lindberg
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
import debiki._
import debiki.EdHttp._
import ed.server.spam.SpamChecker
import ed.server._
import javax.inject.Inject
import play.api.mvc._
import play.api.libs.json._


/** Logs in guest users, creates them first, if needed.
  */
class LoginAsGuestController @Inject()(cc: ControllerComponents, edContext: EdContext)
  extends EdController(cc, edContext) {

  import context.globals
  import context.security.createSessionIdAndXsrfToken


  def loginGuest: Action[JsValue] = AsyncPostJsonAction(RateLimits.Login, maxBytes = 1000) { request =>
    val json = request.body.as[JsObject]
    val name = (json \ "name").as[String].trim
    val email = (json \ "email").as[String].trim

    val settings = request.dao.getWholeSiteSettings()

    throwForbiddenIf(!settings.allowSignup, "TyE0SIGNUP03", "Creation of new accounts is disabled")
    throwForbiddenIf(!settings.isGuestLoginAllowed,
      "TyE4K5FW2", "Guest login disabled; you cannot login as guest here")

    throwForbiddenIf(User nameIsWeird name, "TyE82CW19", "Weird name. Please use no weird characters")
    throwForbiddenIf(name.isEmpty, "TyE872Y90", "Please fill in your name")
    throwForbiddenIf(email.nonEmpty && User.emailIsWeird(email),
      "TyE04HK83", "Weird email. Please use a real email address")

    globals.spamChecker.detectRegistrationSpam(request, name = name, email = email) map {
        isSpamReason =>
      SpamChecker.throwForbiddenIfSpam(isSpamReason, "EdE5KJU3_")

      val (browserId, newBrowserIdCookie) = context.security.getBrowserIdCreateIfNeeded(request)

      val loginAttempt = GuestLoginAttempt(
        ip = request.ip,
        date = globals.now().toJavaDate,
        name = name,
        email = email,
        guestCookie = browserId.cookieValue)

      val guestUser = request.dao.loginAsGuest(loginAttempt)

      val (_, _, sidAndXsrfCookies) = createSessionIdAndXsrfToken(request.siteId, guestUser.id)

      OkSafeJson(Json.obj(
        "userCreatedAndLoggedIn" -> JsTrue,
        "emailVerifiedAndLoggedIn" -> JsFalse)).withCookies(newBrowserIdCookie ::: sidAndXsrfCookies: _*)
    }
  }

}
