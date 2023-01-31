/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

"use strict";
import {
  checkOnboardingSlide,
  goToSlideByNumber,
} from "../../support/utils.js";

describe("onboarding slides", () => {
  beforeEach(() => {
    cy.demoLogin();
  });

  it("go through all the onboarding slides", () => {
    cy.getBySel("onboarding-welcome").should("exist");
    cy.getBySel("onboarding-next-btn").should("exist");
    cy.getBySel("onboarding-next-btn").click();

    cy.getBySel("opsource-next-btn").should("exist");
    cy.getBySel("skip-btn").should("not.exist");
    cy.getBySel("opsource-next-btn").click();

    var genArr = Array.from(Array(3).keys());
    cy.wrap(genArr).each((index) => {
      checkOnboardingSlide(index, true);
    });
    checkOnboardingSlide("3", false);

    cy.getBySel("onboarding-welcome-title").should("exist");
  });

  it("go to specific onboarding slides", () => {
    cy.getBySel("onboarding-next-btn").click();
    cy.getBySel(`opsource-next-btn`).click();

    var genArr = Array.from(Array(4).keys());
    cy.wrap(genArr).each((index) => {
      goToSlideByNumber(4 - index);
    });
  });

  it("skip onboarding slides", () => {
    cy.getBySel("onboarding-next-btn").click();
    cy.getBySel("opsource-next-btn").click();
    cy.getBySel("skip-btn").click();
    cy.getBySel("fly-solo-op").click();
    cy.getBySel("onboarding-welcome-title").should("exist");
  });
});
