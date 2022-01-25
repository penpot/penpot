export const checkOnboardingSlide = (number, checkSkip) => {
    cy.getBySel(`slide-${number}-title`).should("exist");
    if(checkSkip){cy.getBySel("skip-btn").should("exist");}
    cy.get(".onboarding .step-dots").should("exist");
    cy.getBySel(`slide-${number}-btn`).should("exist");
    cy.getBySel(`slide-${number}-btn`).click();
};

export const goToSlideByNumber = (number) => {
    cy.get(`.step-dots li:nth-child(${number})`).click();
    cy.getBySel(`slide-${number -1}-btn`).should("exist");
};