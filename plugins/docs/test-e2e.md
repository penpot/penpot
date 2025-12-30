## End-to-End (E2E) Testing Guide

### Setting Up

1. **Configure Environment Variables**

   Create and populate the `.env` file with a valid user mail & password:

   ```env
   E2E_LOGIN_EMAIL="test@penpot.app"
   E2E_LOGIN_PASSWORD="123123123"
   E2E_SCREENSHOTS= "true"
   ```

2. **Run E2E Tests**

   Use the following command to execute the E2E tests:

   ```bash
   npm run test:e2e
   ```

### Writing Tests

1. **Adding Tests**

   Place your test files in the `/apps/e2e/src/**/*.spec.ts` directory. Below is an example of a test file:

   ```ts
   import testingPlugin from './plugins/create-board-text-rect';
   import { Agent } from './utils/agent';

   describe('Plugins', () => {
     it('create board - text - rectangle', async () => {
       const agent = await Agent();
       const result = await agent.runCode(testingPlugin.toString());

       expect(result).toMatchSnapshot();
     });
   });
   ```

   **Explanation**:
   - `Agent` opens a browser, logs into Penpot, and creates a file.
   - `runCode` executes the plugin code and returns the file state after execution.

2. **Using `runCode` Method**

   The `runCode` method takes the plugin code as a string:

   ```ts
   const result = await agent.runCode(testingPlugin.toString());
   ```

   It can also accept an options object:

   ```ts
   const result = await agent.runCode(testingPlugin.toString(), {
     autoFinish: false, // default: true
     screenshot: 'test-name', // default: ''
   });

   // Finish will close the browser & delete the file
   agent.finish();
   ```

3. **Snapshot Testing**

   The `toMatchSnapshot` method stores the result and throws an error if the content does not match the previous result:

   ```ts
   expect(result).toMatchSnapshot();
   ```

   Snapshots are stored in the `apps/e2e/src/__snapshots__/*.spec.ts.snap` directory.

   If you need to refresh all the snapshopts run the test with the update option:

   ```bash
   npm run test:e2e -- --update
   ```
