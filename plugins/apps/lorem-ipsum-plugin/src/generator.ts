const wordList = [
  'dolor',
  'sit',
  'amet',
  'consectetur',
  'adipiscing',
  'elit',
  'sed',
  'do',
  'eiusmod',
  'tempor',
  'incididunt',
  'labore',
  'et',
  'dolore',
  'magna',
  'aliqua',
  'enim',
  'ad',
  'minim',
  'veniam',
  'quis',
  'nostrud',
  'exercitation',
  'ullamco',
  'laboris',
  'nisi',
  'ut',
  'aliquip',
  'ex',
  'ea',
  'commodo',
  'consequat',
  'duis',
  'aute',
  'irure',
  'in',
  'reprehenderit',
  'voluptate',
  'velit',
  'esse',
  'cillum',
  'eu',
  'fugiat',
  'nulla',
  'pariatur',
  'excepteur',
  'sint',
  'occaecat',
  'cupidatat',
  'non',
  'proident',
  'sunt',
  'culpa',
  'qui',
  'officia',
  'deserunt',
  'mollit',
  'anim',
  'id',
  'est',
  'laborum',
];

const lorem = 'Lorem ipsum' as const;

function* randomWordGenerator() {
  let copyWordList: string[] = [];

  while (true) {
    if (!copyWordList.length) {
      copyWordList = [...wordList];
    }

    const newWordIndex = Math.floor(Math.random() * copyWordList.length);

    yield copyWordList[newWordIndex];

    copyWordList.splice(newWordIndex, 1);
  }
}

const getRandomWordGenerator = randomWordGenerator();

function getRandomWord() {
  return getRandomWordGenerator.next().value;
}

export function generateCharacters(count: number, startWithLorem = true) {
  let text = '';

  if (startWithLorem) {
    text = lorem + ' ';
  }

  while (text.length < count) {
    text += getRandomWord() + ' ';
  }

  return text.slice(0, count);
}

export function generateWords(count: number, startWithLorem = true) {
  const words = [];

  if (startWithLorem) {
    words.push(...lorem.split(' ').slice(0, count));
  }

  for (let i = words.length; i < count; i++) {
    words.push(getRandomWord());
  }

  return words.join(' ');
}

export function generateSentences(count: number, startWithLorem = true) {
  const sentences = [];
  for (let i = 0; i < count; i++) {
    const sentenceLength = Math.floor(Math.random() * 10) + 3; // between 3 and 12 words per sentence
    let sentence = generateWords(sentenceLength, false);

    if (startWithLorem && i === 0) {
      sentence =
        lorem + ' ' + sentence.charAt(0).toLowerCase() + sentence.slice(1);
    }

    sentences.push(sentence.charAt(0).toUpperCase() + sentence.slice(1) + '.');
  }
  return sentences.join(' ');
}

export function generateParagraphs(count: number, startWithLorem = true) {
  const paragraphs = [];
  for (let i = 0; i < count; i++) {
    const paragraphLength = Math.floor(Math.random() * 5) + 3; // between 3 and 7 sentences per paragraph
    paragraphs.push(
      generateSentences(paragraphLength, startWithLorem && i === 0),
    );
  }
  return paragraphs.join('\n\n');
}
