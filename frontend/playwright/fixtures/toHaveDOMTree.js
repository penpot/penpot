import { expect as baseExpect } from '@playwright/test';
export { test } from '@playwright/test';

export const expect = baseExpect.extend({
  async toHaveDOMTree(locator, expected, options) {
    const assertionName = 'toHaveDOMTree';

    let pass;
    let matcherResult;

    try {
      await locator.evaluate((expected) => {
        
      }, [expected]);
      pass = true;
    } catch (error) {
      matcherResult = error.matcherResult;
      pass = false;
    }

    const message = pass
      ? () => this.utils.matcherHint(assertionName, undefined, undefined, { isNot: this.isNot }) +
          '\n\n' +
          `Locator: ${locator}\n` +
          `Expected: ${this.isNot ? 'not' : ''}${this.utils.printExpected(expected)}\n` +
          (matcherResult ? `Received: ${this.utils.printReceived(matcherResult.actual)}` : '')
      : () =>  this.utils.matcherHint(assertionName, undefined, undefined, { isNot: this.isNot }) +
          '\n\n' +
          `Locator: ${locator}\n` +
          `Expected: ${this.utils.printExpected(expected)}\n` +
          (matcherResult ? `Received: ${this.utils.printReceived(matcherResult.actual)}` : '');

    return {
      message,
      pass,
      name: assertionName,
      expected,
      actual: matcherResult?.actual,
    };
  },
});
