import { BaseWebSocketPage } from "./BaseWebSocketPage";

export class OnboardingPage extends BaseWebSocketPage {
  constructor(page) {
    super(page);
    this.submitButton = page.getByRole("Button", { name: "Next" });
  }

  async fillOnboardingInputsStep1() {
    await this.page.getByText("Personal").click();
    await this.page.getByText("Select option").click();
    await this.page.getByText("Product Management").click();

    await this.submitButton.click();
  }

  async fillOnboardingInputsStep2() {
    await this.page.getByText("Figma").click();

    await this.submitButton.click();
  }

  async fillOnboardingInputsStep3() {
    await this.page.getByText("Other").click();
    await this.page.getByPlaceholder("Other (specify)").fill("Another");
  }
}

export default OnboardingPage;
