import { FileRpc } from '../models/file-rpc.model';

const apiUrl = 'https://localhost:3449';

export async function PenpotApi() {
  if (!process.env['E2E_LOGIN_EMAIL']) {
    throw new Error('E2E_LOGIN_EMAIL not set');
  }

  const body = JSON.stringify({
    'email': process.env['E2E_LOGIN_EMAIL'],
    'password': process.env['E2E_LOGIN_PASSWORD'],
  });

  const resultLoginRequest = await fetch(
    `${apiUrl}/api/main/methods/login-with-password`,
    {
      credentials: 'include',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: body
    },
  );

  console.log("AAAAAAAAAAAA", 1, apiUrl)
  // console.log("AAAAAAAAAAAA", 2, resultLoginRequest);

  console.dir(resultLoginRequest.headers, {depth:20});
  console.log('Document Cookies:', window.document.cookie);

  const loginData = await resultLoginRequest.json();


  const authToken = resultLoginRequest.headers
    .get('set-cookie')
    ?.split(';')
    .at(0);

  if (!authToken) {
    throw new Error('Login failed');
  }

  return {
    getAuth: () => authToken,
    createFile: async () => {
      const createFileRequest = await fetch(
        `${apiUrl}/api/main/methods/create-file`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/transit+json',
            cookie: authToken,
            credentials: 'include',
          },
          body: JSON.stringify({
            '~:name': `test file ${new Date().toISOString()}`,
            '~:project-id': loginData['~:default-project-id'],
            '~:features': {
              '~#set': [
                'fdata/objects-map',
                'fdata/pointer-map',
                'fdata/shape-data-type',
                'components/v2',
                'styles/v2',
                'layout/grid',
                'plugins/runtime',
              ],
            },
          }),
        },
      );

      return (await createFileRequest.json()) as FileRpc;
    },
    deleteFile: async (fileId: string) => {
      const deleteFileRequest = await fetch(
        `${apiUrl}/api/main/methods/delete-file`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/transit+json',
            cookie: authToken,
            credentials: 'include',
          },
          body: JSON.stringify({
            '~:id': fileId,
          }),
        },
      );

      return deleteFileRequest;
    },
  };
}
