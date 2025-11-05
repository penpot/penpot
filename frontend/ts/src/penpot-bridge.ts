export let translate = (key: string) => {
  return key;
};

export function setTranslation(translations: (key: string) => string) {
  translate = translations;
}
