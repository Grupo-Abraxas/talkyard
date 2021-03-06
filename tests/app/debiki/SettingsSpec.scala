/**
 * Copyright (c) 2018 Kaj Magnus Lindberg
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


package debiki


import org.scalatest._


class SettingsSpec extends FreeSpec with MustMatchers {


  "EffectiveSettings can" - {

    import EffectiveSettings.{improveAllowEmbeddingFrom => improve}

    "improve allow-from, simple origins" in {
      improve("") mustBe ""
      improve("www.example.com") mustBe "www.example.com"
      improve("  www.example.com  ") mustBe "www.example.com"
    }

    "improve allow-from, many origins, no scheme" in {
      improve("www.example.com h.ex.co") mustBe "www.example.com h.ex.co"
      improve("www.example.com a.ex.co b.ex.co") mustBe "www.example.com a.ex.co b.ex.co"
    }

    "improve allow-from: add https if http only" in {
      improve("http://www.ex.com") mustBe "http://www.ex.com https://www.ex.com"
      improve("https://www.ex.com") mustBe "https://www.ex.com"
    }

    "improve allow-from: add https, many origins" in {
      improve("http://a.b.c http://d.e.f https://ok fine") mustBe
          "http://a.b.c https://a.b.c http://d.e.f https://d.e.f https://ok fine"
    }

    "improve allow-from: won't duplicate https" in {
      improve("http://aaa https://aaa") mustBe "http://aaa https://aaa"
      improve("http://aa https://aa http://bb https://cc http://dd https://dd") mustBe
          "http://aa https://aa http://bb https://bb https://cc http://dd https://dd"
    }

    "improve allow-from: won't duplicate https, if path" in {
      improve("http://aaa/path https://aaa/path") mustBe "http://aaa https://aaa"
    }

    "improve allow-from: removes dupl sources" in {
      improve("aaa aaa") mustBe "aaa"

      improve("http://zz qq") mustBe "http://zz https://zz qq"
      improve("http://zz https://zz qq") mustBe "http://zz https://zz qq"
      improve("https://zz http://zz qq") mustBe "https://zz http://zz qq"
      // Now the first http://zz won't be dupl to https:, because https://zz already present.
      improve("http://zz qq https://zz") mustBe "http://zz qq https://zz"
      improve("https://zz qq http://zz") mustBe "https://zz qq http://zz"

      improve("http://zz https://nn http://zz ww qq https://nn ww") mustBe
          "http://zz https://zz https://nn ww qq"
      // Now the first http://zz won't be dupl to https:, because https://zz already present.
      improve("http://zz https://nn http://zz https://zz ww qq https://nn ww") mustBe
          "http://zz https://nn https://zz ww qq"
    }

    "improve allow-from: remove paths" in {
      improve("www.ex.com/some/path") mustBe "www.ex.com"
      improve("www.ex.com:8080/path") mustBe "www.ex.com:8080"
      improve("http://www.ex.com/some/path") mustBe "http://www.ex.com https://www.ex.com"
      improve("https://www.ex.com/some/path") mustBe "https://www.ex.com"
      improve("https://www.ex.com:8000/some/path") mustBe "https://www.ex.com:8000"
    }

    "improve allow-from: all at once" in {
      improve("http://aaa http://bbb/path https://ccc https://ddd/path eee fff/path ggg:8080") mustBe
          "http://aaa https://aaa http://bbb https://bbb https://ccc https://ddd eee fff ggg:8080"
    }

  }

}


