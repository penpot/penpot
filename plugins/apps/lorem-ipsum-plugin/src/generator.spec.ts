import { describe, it, expect } from 'vitest';
import {
  generateCharacters,
  generateWords,
  generateSentences,
  generateParagraphs,
} from './generator';

describe('generateCharacters', () => {
  it('should generate the correct number of characters starting with "Lorem ipsum"', () => {
    const result = generateCharacters(20);
    expect(result.length).toBe(20);
    expect(result.startsWith('Lorem ipsum')).toBe(true);
  });

  it('should generate the correct number of characters without starting with "Lorem ipsum"', () => {
    const result = generateCharacters(40, false);
    expect(result.length).toBe(40);
    expect(result.startsWith('Lorem ipsum')).toBe(false);
  });
});

describe('generateWords', () => {
  it('should generate the correct number of words starting with "Lorem ipsum"', () => {
    const result = generateWords(5);
    const words = result.split(' ');
    expect(words.length).toBe(5);
    expect(result.startsWith('Lorem ipsum')).toBe(true);
  });

  it('should generate the correct number of words without starting with "Lorem ipsum"', () => {
    const result = generateWords(10, false);
    const words = result.split(' ');
    expect(words.length).toBe(10);
    expect(result.startsWith('Lorem ipsum')).toBe(false);
  });
});

describe('generateSentences', () => {
  it('should generate the correct number of sentences starting with "Lorem ipsum"', () => {
    const result = generateSentences(3);
    const sentences = result.split('. ');
    expect(sentences.length).toBe(3);
    expect(result.startsWith('Lorem ipsum')).toBe(true);
  });

  it('should generate the correct number of sentences without starting with "Lorem ipsum"', () => {
    const result = generateSentences(6, false);
    const sentences = result.split('. ');
    expect(sentences.length).toBe(6);
    expect(result.startsWith('Lorem ipsum')).toBe(false);
  });
});

describe('generateParagraphs', () => {
  it('should generate the correct number of paragraphs starting with "Lorem ipsum"', () => {
    const result = generateParagraphs(2);
    const paragraphs = result.split('\n\n');
    expect(paragraphs.length).toBe(2);
    expect(result.startsWith('Lorem ipsum')).toBe(true);
  });

  it('should generate the correct number of paragraphs without starting with "Lorem ipsum"', () => {
    const result = generateParagraphs(4, false);
    const paragraphs = result.split('\n\n');
    expect(paragraphs.length).toBe(4);
    expect(result.startsWith('Lorem ipsum')).toBe(false);
  });
});
