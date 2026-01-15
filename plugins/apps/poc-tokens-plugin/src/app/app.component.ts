import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { fromEvent, map, filter, take, merge } from 'rxjs';
import { PluginMessageEvent, PluginUIEvent } from '../model';

type TokenTheme = {
  id: string;
  name: string;
  group: string;
  description: string;
  active: boolean;
};

type TokenSet = {
  id: string;
  name: string;
  description: string;
  active: boolean;
};

type Token = {
  id: string;
  name: string;
  description: string;
};

type TokensGroup = [string, Token[]];

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
  host: {
    '[attr.data-theme]': 'theme()',
  },
})
export class AppComponent {
  public route = inject(ActivatedRoute);

  public messages$ = fromEvent<MessageEvent<PluginMessageEvent>>(
    window,
    'message',
  );

  public initialTheme$ = this.route.queryParamMap.pipe(
    map((params) => params.get('theme')),
    filter((theme) => !!theme),
    take(1),
  );

  public theme = toSignal(
    merge(
      this.initialTheme$,
      this.messages$.pipe(
        filter((event) => event.data.type === 'theme'),
        map((event) => {
          return event.data.content;
        }),
      ),
    ),
  );

  public themes: TokenTheme[] = [];
  public sets: TokenSet[] = [];
  public tokenGroups: TokensGroup[] = [];
  public currentSetId: string | undefined = undefined;

  constructor() {
    window.addEventListener('message', (event) => {
      if (event.data.type === 'set-themes') {
        this.#setThemes(event.data.themesData);
      } else if (event.data.type === 'set-sets') {
        this.#setSets(event.data.setsData);
      } else if (event.data.type === 'set-tokens') {
        this.#setTokens(event.data.tokenGroupsData);
      }
    });
  }

  loadLibrary() {
    this.#sendMessage({ type: 'load-library' });
  }

  loadTokens(setId: string) {
    this.currentSetId = setId;
    this.#sendMessage({ type: 'load-tokens', setId });
  }

  addTheme() {
    this.#sendMessage({
      type: 'add-theme',
      themeGroup: this.#randomString(),
      themeName: this.#randomString(),
    });
  }

  addSet() {
    this.#sendMessage({ type: 'add-set', setName: this.#randomString() });
  }

  addToken(tokenType: string) {
    let tokenValue;
    switch (tokenType) {
      case 'borderRadius':
        tokenValue = '25';
        break;
      case 'shadow':
        tokenValue = [
          {
            color: '#123456',
            inset: 'false',
            offsetX: '6',
            offsetY: '6',
            spread: '0',
            blur: '4',
          },
        ];
        break;
      case 'color':
        tokenValue = '#fabada';
        break;
      case 'dimension':
        tokenValue = '100';
        break;
      case 'fontFamilies':
        tokenValue = ['Source Sans Pro', 'Sans serif'];
        break;
      case 'fontSizes':
        tokenValue = '24';
        break;
      case 'fontWeights':
        tokenValue = 'bold';
        break;
      case 'letterSpacing':
        tokenValue = '0.5';
        break;
      case 'number':
        tokenValue = '33';
        break;
      case 'opacity':
        tokenValue = '0.6';
        break;
      case 'rotation':
        tokenValue = '45';
        break;
      case 'sizing':
        tokenValue = '200';
        break;
      case 'spacing':
        tokenValue = '16';
        break;
      case 'borderWidth':
        tokenValue = '3';
        break;
      case 'textCase':
        tokenValue = 'lowercase';
        break;
      case 'textDecoration':
        tokenValue = 'underline';
        break;
      case 'typography':
        tokenValue = {
          fontFamilies: ['Acme', 'Arial', 'Sans Serif'],
          fontSizes: '36',
          letterSpacing: '0.8',
          textCase: 'uppercase',
          textDecoration: 'none',
          fontWeights: '600',
          lineHeight: '1.5',
        };
        break;
    }

    if (this.currentSetId && tokenValue) {
      this.#sendMessage({
        type: 'add-token',
        setId: this.currentSetId,
        tokenType,
        tokenName: this.#randomString(),
        tokenValue,
      });
    } else {
      console.log('Invalid token type');
    }
  }

  renameTheme(themeId: string, themeName: string) {
    const newName = prompt('Rename theme', themeName);
    if (newName && newName !== '') {
      this.#sendMessage({ type: 'rename-theme', themeId, newName });
    }
  }

  renameSet(setId: string, setName: string) {
    const newName = prompt('Rename set', setName);
    if (newName && newName !== '') {
      this.#sendMessage({ type: 'rename-set', setId, newName });
    }
  }

  renameToken(tokenId: string, tokenName: string) {
    const newName = prompt('Rename token', tokenName);
    if (this.currentSetId && newName && newName !== '') {
      this.#sendMessage({
        type: 'rename-token',
        setId: this.currentSetId,
        tokenId,
        newName,
      });
    }
  }

  deleteTheme(themeId: string) {
    this.#sendMessage({ type: 'delete-theme', themeId });
  }

  deleteSet(setId: string) {
    this.#sendMessage({ type: 'delete-set', setId });
  }

  deleteToken(tokenId: string) {
    if (this.currentSetId) {
      this.#sendMessage({
        type: 'delete-token',
        setId: this.currentSetId,
        tokenId,
      });
    }
  }

  isThemeActive(themeId: string) {
    for (const theme of this.themes) {
      if (theme.id === themeId) {
        return theme.active;
      }
    }
    return false;
  }

  toggleTheme(themeId: string) {
    this.#sendMessage({ type: 'toggle-theme', themeId });
  }

  isSetActive(setId: string) {
    for (const set of this.sets) {
      if (set.id === setId) {
        return set.active;
      }
    }
    return false;
  }

  toggleSet(setId: string) {
    this.#sendMessage({ type: 'toggle-set', setId });
  }

  applyToken(tokenId: string) {
    if (this.currentSetId) {
      this.#sendMessage({
        type: 'apply-token',
        setId: this.currentSetId,
        tokenId,
        // attributes: ['stroke-color']   // Uncomment to choose attribute to apply
      }); // (incompatible attributes will have no effect)
    }
  }

  #sendMessage(message: PluginUIEvent) {
    parent.postMessage(message, '*');
  }

  #setThemes(themes: TokenTheme[]) {
    this.themes = themes;
  }

  #setSets(sets: TokenSet[]) {
    this.sets = sets;
  }

  #setTokens(tokenGroups: TokensGroup[]) {
    this.tokenGroups = tokenGroups;
  }

  #randomString() {
    // Generate a big random number and convert it to string using base 36
    // (the number of letters in the ascii alphabet)
    return Math.floor(Math.random() * Date.now()).toString(36);
  }
}
