/// <reference path="../test-types.ts"/>

import * as _ from 'lodash';
import assert = require('assert');
import server = require('../utils/server');
import utils = require('../utils/utils');
import pagesFor = require('../utils/pages-for');
import settings = require('../utils/settings');
import make = require('../utils/make');
import logAndDie = require('../utils/log-and-die');

declare let browser: any;
declare let browserA: any;
declare let browserB: any;
declare let browserC: any;

let everyone;
let owen;
let michael;
let maria;
let memberAlice;
let alicesBrowser;
let guest;
let stranger;

let idAddress;
let forumTitle = "Forum with Private Chat";
let messageUrl;
let messageTitle = "Message title";
let messageText = "Hi I have a question";
let owensAnswer = "Yes what is it?";
let mariasQuestion = "Can I ask questions?";
let owensQuestionAnswer = "Yes if the number of questions is a prime number. 2, 3, 5, 7, 11 you know.";

let siteId;


describe("private chat", () => {

  it("initialize people", () => {
    everyone = _.assign(browser, pagesFor(browser));
    owen = _.assign(browserA, pagesFor(browserA), make.memberOwenOwner());
    michael = _.assign(browserB, pagesFor(browserB), make.memberMichael());
    maria = _.assign(browserC, pagesFor(browserC), make.memberMaria());
    // Let's reuse the same browser.
    alicesBrowser = maria;
    guest = michael;
    stranger = michael;
  });

  it("import a site", () => {
    let site: SiteData = make.forumOwnedByOwen('formal-priv-msg', { title: forumTitle });
    site.settings.allowGuestLogin = true;
    site.settings.requireVerifiedEmail = false;
    memberAlice = make.memberAdminAlice();
    site.members.push(memberAlice);
    site.members.push(make.memberMichael());
    site.members.push(make.memberMaria());
    idAddress = server.importSiteData(site);
    siteId = idAddress.id;
  });


  // Generate private message discussion
  // ------------------------------------------------------

  it("Maria opens Owen's user page", () => {
    maria.userProfilePage.openActivityFor(owen.username, idAddress.origin);
    //maria.assertPageTitleMatches(forumTitle);
    maria.complex.loginWithPasswordViaTopbar(maria);
  });

  it("... sends a formal private message", () => {
    maria.userProfilePage.clickSendMessage();
    maria.editor.editTitle(messageTitle);
    maria.editor.editText(messageText);
    maria.editor.saveWaitForNewPage();
    maria.assertPageTitleMatches(messageTitle);
    maria.assertPageBodyMatches(messageText);
    messageUrl = maria.url().value;
  });

  it("Owen logs in", () => {
    owen.go(idAddress.origin);
    owen.complex.loginWithPasswordViaTopbar(owen);
  });

  it("... he doesn't see the message in the topic list", () => {
    owen.forumTopicList.waitUntilKnowsIsEmpty();
  });

  it("... but sees a notification", () => {
    owen.topbar.assertNotfToMe();
  });

  it("... opens the message via the notf icon", () => {
    owen.topbar.openNotfToMe();
  });

  it("... and replies", () => {
    owen.complex.replyToOrigPost(owensAnswer);
    owen.topic.waitForPostNrVisible(2);
  });

  it("... he also got an email notf about this new topic", () => {
    // This tests notfs when one clicks the append-bottom-comment button.
    server.waitUntilLastEmailMatches(
        siteId, owen.emailAddress, [messageTitle, messageText], browser);
  });

  it("Maria sees the reply, replies", () => {
    maria.topic.waitForPostNrVisible(2);
    maria.topic.assertPostTextMatches(2, owensAnswer);
    maria.complex.replyToPostNr(2, mariasQuestion);
    maria.topic.waitForPostNrVisible(3);
    maria.topic.assertPostTextMatches(3, mariasQuestion);
  });

  it("... she got a notification, dismisses it", () => {
    maria.topbar.openNotfToMe();
  });

  it("... she got a notf email too", () => {
    server.waitUntilLastEmailMatches(
        siteId, maria.emailAddress, [messageTitle, owensAnswer], browser);
  });

  it("Maria leaves", () => {
    maria.go('/');
  });

  it("Owen sees Maria reply", () => {
    owen.topic.waitForPostNrVisible(3);
    owen.topic.assertPostTextMatches(3, mariasQuestion);
  });

  it("... and replies", () => {
    owen.complex.addBottomComment(owensQuestionAnswer);
    owen.topic.waitForPostNrVisible(4);
  });

  it("... he also got an email about Maria's reply", () => {
    // This tests notfs when one clicks Reply for a particular post.
    server.waitUntilLastEmailMatches(
        siteId, owen.emailAddress, [messageTitle, mariasQuestion], browser);
  });

  it("Maria gets an email with Owen's reply", () => {
    // This tests notfs when one clicks the append-bottom-comment button.
    // TESTS_MISSING No? Instead, should  be Maria that clicks append-bottom to Owen because
    // Maria is the orig-poster, she'll always get a notf, everything is a reply to her.
    server.waitUntilLastEmailMatches(
        siteId, maria.emailAddress, [messageTitle, owensQuestionAnswer], browser);
  });

  it("... and she gets an in-browser notifiction too, opens it", () => {
    maria.topbar.openNotfToMe();
  });

  it("... she sees the question answer", () => {
    maria.topic.waitForPostNrVisible(4);
    maria.topic.assertPostTextMatches(4, owensQuestionAnswer);
  });


  // Strangers have no access
  // ------------------------------------------------------

  it("A stranger doesn't see the topic in the forum topic list", () => {
    stranger.go(idAddress.origin);
    stranger.forumTopicList.waitUntilKnowsIsEmpty();
  });

  it("... and won't find it when searching", () => {
    stranger.topbar.searchFor(mariasQuestion);
    stranger.searchResultsPage.assertPhraseNotFound(mariasQuestion);
  });

  it("... and cannot accesss it via a direct link", () => {
    stranger.go(messageUrl);
    stranger.assertNotFoundError();
  });


  // Other members have no access
  // ------------------------------------------------------

  it("Michael logs in", () => {
    assert(michael === stranger);
    michael.go(idAddress.origin);
    michael.complex.loginWithPasswordViaTopbar(michael);
  });


  it("Michael cannot access the topic, doesn't see the topic in the forum topic list", () => {
    michael.forumTopicList.waitUntilKnowsIsEmpty();
  });

  it("... not even after a refresh", () => {
    michael.refresh();
    michael.forumTopicList.waitUntilKnowsIsEmpty();
  });

  it("... and cannot access via direct link", () => {
    michael.go(messageUrl);
    michael.assertNotFoundError();
  });

  it("... and does not see it in his profile page, the messages section", () => {
    michael.userProfilePage.openNotfsFor(michael.username, idAddress.origin);
    michael.userProfilePage.notfs.waitUntilKnowsIsEmpty();
  });

  it("... and does not see it in the watchbar", () => {
    michael.watchbar.openIfNeeded();
    michael.watchbar.asserExactlyNumTopics(1); // the forum, but no priv-message
  });

  it("... and won't find it when searching", () => {
    michael.topbar.searchFor(mariasQuestion);
    michael.searchResultsPage.assertPhraseNotFound(mariasQuestion);
  });


  // The participants have access
  // ------------------------------------------------------

  it("Maria also doesn't see it listed in the forum", () => {
    maria.go(idAddress.origin);
    maria.forumTopicList.waitUntilKnowsIsEmpty();
  });

  it("... but she can access it via a direct link", () => {
    maria.go(messageUrl);
    maria.waitForMyDataAdded();
    maria.assertPageTitleMatches(messageTitle);
    maria.assertPageBodyMatches(messageText);
  });

  it("... and via her profile page, the messages section", () => {
    maria.userProfilePage.openNotfsFor(maria.username, idAddress.origin);
    maria.userProfilePage.notfs.waitUntilSeesNotfs();
    maria.userProfilePage.notfs.openPageNotfWithText(messageTitle);
    maria.waitForMyDataAdded();
    maria.assertPageTitleMatches(messageTitle);
    maria.assertPageBodyMatches(messageText);
  });

  it("... and via the watchbar", () => {
    maria.go(idAddress.origin);
    maria.watchbar.openIfNeeded();
    maria.watchbar.asserExactlyNumTopics(2); // forum + priv-message
    maria.watchbar.assertTopicVisible(messageTitle);
    maria.watchbar.goToTopic(messageTitle);
    maria.assertPageTitleMatches(messageTitle);
    maria.assertPageBodyMatches(messageText);
  });

  it("... and search for it and find it", () => {
    maria.topbar.searchFor(mariasQuestion);
    maria.searchResultsPage.waitForAssertNumPagesFound(mariasQuestion, 1);
  });


  // Cannot access after logged out
  // ------------------------------------------------------

  it("Maria logs out", () => {
    maria.topbar.clickLogout(
        // Needed because of a bug in Chrome? Chromedriver? Selenium? Webdriver.io? [E2EBUG]
        // Without, there's a stale-elem-exception for a totally unrelated elem:
        { waitForLoginButton: false });
  });

  it("... and can access the priv message no more, not in the topic list", () => {
    maria.go(idAddress.origin);
    maria.forumTopicList.waitUntilKnowsIsEmpty();
  });

  it("not in the watchbar", () => {
    maria.watchbar.openIfNeeded();
    // [E2EBUG] failed:
    // FAIL: AssertionError: Selector '.esWB_T_Title' matches 2 elems, but there should be exactly 1
    // ... but this didn't break the test suite :-/  could look into this later... seems harmless.
    maria.watchbar.asserExactlyNumTopics(1); // the forum, but no priv-message
  });

  it("not via direct link", () => {
    maria.go(messageUrl);
    maria.assertNotFoundError();
  });

  it("not via profile page", () => {
    maria.userProfilePage.openNotfsFor(maria.username, idAddress.origin);
    maria.userProfilePage.notfs.assertMayNotSeeNotfs();
  });

  it("not when searching", () => {
    maria.topbar.searchFor(mariasQuestion);
    maria.searchResultsPage.assertPhraseNotFound(mariasQuestion);
  });


  // Admins can access the topic
  // ------------------------------------------------------

  it("Alice logs in", () => {
    assert(alicesBrowser === maria); // same browser
    alicesBrowser.complex.loginWithPasswordViaTopbar(memberAlice);
  });

  it("... finds the topic when searching", () => {
    alicesBrowser.topbar.searchFor(mariasQuestion);
    alicesBrowser.searchResultsPage.waitForAssertNumPagesFound(mariasQuestion, 1);
  });

  it("... can click link and see it", () => {
    alicesBrowser.searchResultsPage.goToSearchResult();
    alicesBrowser.assertPageTitleMatches(messageTitle);
    alicesBrowser.assertPageBodyMatches(messageText);
  });

  it("... sees it in Maria's notf section", () => {
    alicesBrowser.userProfilePage.openNotfsFor(maria.username, idAddress.origin);
    alicesBrowser.userProfilePage.notfs.waitUntilSeesNotfs();
    alicesBrowser.userProfilePage.notfs.openPageNotfWithText(messageTitle);
    alicesBrowser.waitForMyDataAdded();
    alicesBrowser.assertPageTitleMatches(messageTitle);
    alicesBrowser.assertPageBodyMatches(messageText);
  });


  /*
  it("Owen also doesn't see it listed in the forum", () => {
    owen.debug();
    owen.go(idAddress.origin);
    owen.forumTopicList.waitUntilKnowsIsEmpty();
  });

  it("... but he too can access it via a direct link", () => {
  });

  it("... and via his profile page, the messages section", () => {
  });

  it("Michael leaves, a guest logs in", () => {
  });

  it("The guest doesn't see the topic in the topic list", () => {
  });

  it("... and cannot access it via a direct link", () => {
  });
  */

});

