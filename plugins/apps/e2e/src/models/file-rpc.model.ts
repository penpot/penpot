export interface FileRpc {
  '~:name': string;
  '~:revn': number;
  '~:id': string;
  '~:is-shared': boolean;
  '~:version': number;
  '~:project-id': string;
  '~:data': {
    '~:pages': string[];
    '~:objects': string[];
    '~:styles': string[];
    '~:components': string[];
    '~:styles-v2': string[];
    '~:components-v2': string[];
    '~:features': string[];
  };
}
