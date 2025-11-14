import type { PluginMessageEvent, PluginUIEvent } from './model.js';
import { TokenType, TokenProperty } from '@penpot/plugin-types';

penpot.ui.open('Design Tokens test', `?theme=${penpot.theme}`, {
  width: 1000,
  height: 800,
});

penpot.on('themechange', (theme) => {
  sendMessage({ type: 'theme', content: theme });
});

penpot.ui.onMessage<PluginUIEvent>(async (message) => {
  if (message.type === 'load-library') {
    loadLibrary();
  } else if (message.type === 'load-tokens') {
    loadTokens(message.setId);
  } else if (message.type === 'add-theme') {
    addTheme(message.themeGroup, message.themeName);
  } else if (message.type === 'add-set') {
    addSet(message.setName);
  } else if (message.type === 'add-token') {
    addToken(
      message.setId,
      message.tokenType,
      message.tokenName,
      message.tokenValue,
    );
  } else if (message.type === 'rename-theme') {
    renameTheme(message.themeId, message.newName);
  } else if (message.type === 'rename-set') {
    renameSet(message.setId, message.newName);
  } else if (message.type === 'rename-token') {
    renameToken(message.setId, message.tokenId, message.newName);
  } else if (message.type === 'delete-theme') {
    deleteTheme(message.themeId);
  } else if (message.type === 'delete-set') {
    deleteSet(message.setId);
  } else if (message.type === 'delete-token') {
    deleteToken(message.setId, message.tokenId);
  } else if (message.type === 'toggle-theme') {
    toggleTheme(message.themeId);
  } else if (message.type === 'toggle-set') {
    toggleSet(message.setId);
  } else if (message.type === 'apply-token') {
    applyToken(message.setId, message.tokenId, message.attributes);
  }
});

function sendMessage(message: PluginMessageEvent) {
  penpot.ui.sendMessage(message);
}

function loadLibrary() {
  const tokensCatalog = penpot.library.local.tokens;

  const themes = tokensCatalog.themes;

  const themesData = themes.map((theme) => {
    return {
      id: theme.id,
      group: theme.group,
      name: theme.name,
      active: theme.active,
    };
  });

  penpot.ui.sendMessage({
    source: 'penpot',
    type: 'set-themes',
    themesData,
  });

  const sets = tokensCatalog.sets;

  const setsData = sets.map((set) => {
    return {
      id: set.id,
      name: set.name,
      active: set.active,
    };
  });

  penpot.ui.sendMessage({
    source: 'penpot',
    type: 'set-sets',
    setsData,
  });
}

function loadTokens(setId: string) {
  const tokensCatalog = penpot.library.local.tokens;
  const set = tokensCatalog?.getSetById(setId);
  const tokensByType = set?.tokensByType;

  const tokenGroupsData = [];
  if (tokensByType) {
    for (const group of tokensByType) {
      const type = group[0];
      const tokens = group[1];
      tokenGroupsData.push([
        type,
        tokens.map((token) => {
          return {
            id: token.id,
            name: token.name,
            description: token.description,
          };
        }),
      ]);
    }

    penpot.ui.sendMessage({
      source: 'penpot',
      type: 'set-tokens',
      tokenGroupsData,
    });
  }
}

function addTheme(themeGroup: string, themeName: string) {
  const tokensCatalog = penpot.library.local.tokens;
  const theme = tokensCatalog?.addTheme({group: themeGroup,
                                         name: themeName });
  if (theme) {
    loadLibrary();
  }
}

function addSet(setName: string) {
  const tokensCatalog = penpot.library.local.tokens;
  const set = tokensCatalog?.addSet({name: setName});
  if (set) {
    loadLibrary();
  }
}

function addToken(
  setId: string,
  tokenType: string,
  tokenName: string,
  tokenValue: unknown,
) {
  const tokensCatalog = penpot.library.local.tokens;
  const set = tokensCatalog?.getSetById(setId);
  const token = set?.addToken({type: tokenType as TokenType,
                               name: tokenName,
                               value: tokenValue});
  if (token) {
    loadTokens(setId);
  }
}

function renameTheme(themeId: string, newName: string) {
  const tokensCatalog = penpot.library.local.tokens;
  const theme = tokensCatalog?.getThemeById(themeId);
  if (theme) {
    theme.name = newName;
    loadLibrary();
  }
}

function renameSet(setId: string, newName: string) {
  const tokensCatalog = penpot.library.local.tokens;
  const set = tokensCatalog?.getSetById(setId);
  if (set) {
    set.name = newName;
    loadLibrary();
  }
}

function renameToken(setId: string, tokenId: string, newName: string) {
  const tokensCatalog = penpot.library.local.tokens;
  const set = tokensCatalog?.getSetById(setId);
  const token = set?.getTokenById(tokenId);
  if (token) {
    token.name = newName;
    loadTokens(setId);
  }
}

function deleteTheme(themeId: string) {
  const tokensCatalog = penpot.library.local.tokens;
  const theme = tokensCatalog?.getThemeById(themeId);
  if (theme) {
    theme.remove();
    loadLibrary();
  }
}

function deleteSet(setId: string) {
  const tokensCatalog = penpot.library.local.tokens;
  const set = tokensCatalog?.getSetById(setId);
  if (set) {
    set.remove();
    loadLibrary();
  }
}

function deleteToken(setId: string, tokenId: string) {
  const tokensCatalog = penpot.library.local.tokens;
  const set = tokensCatalog?.getSetById(setId);
  const token = set?.getTokenById(tokenId);
  if (token) {
    token.remove();
    loadTokens(setId);
  }
}

function toggleTheme(themeId: string) {
  const tokensCatalog = penpot.library.local.tokens;
  const theme = tokensCatalog?.getThemeById(themeId);
  if (theme) {
    theme.toggleActive();
    loadLibrary();
  }
}

function toggleSet(setId: string) {
  const tokensCatalog = penpot.library.local.tokens;
  const set = tokensCatalog?.getSetById(setId);
  if (set) {
    set.toggleActive();
    loadLibrary();
  }
}

function applyToken(
  setId: string,
  tokenId: string,
  attributes: TokenProperty[] | undefined,
) {
  const tokensCatalog = penpot.library.local.tokens;
  const set = tokensCatalog?.getSetById(setId);
  const token = set?.getTokenById(tokenId);

  if (token) {
    token.applyToSelected(attributes);
  }

  // Alternatve way
  //
  // const selection = penpot.selection;
  // if (token && selection) {
  //   for (const shape of selection) {
  //     shape.applyToken(token, attributes);
  //   }
  // }
}
