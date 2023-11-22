export function registerPlaygroundShortcuts(
  runCommand: (command: string) => unknown
) {
  window.addEventListener('keydown', handleWindowKeyDown);

  function run(e: KeyboardEvent, command: string) {
    e.preventDefault();
    runCommand(command);
  }

  function handleWindowKeyDown(e: KeyboardEvent) {
    if (isEditing()) {
      return;
    }

    const keyChar = String.fromCharCode(e.keyCode);
    const metaKey = e.metaKey || e.ctrlKey;

    if (keyChar === 'P' && metaKey) {
      run(e, 'searchFixtures');
    } else if (keyChar === 'L' && metaKey && e.shiftKey) {
      run(e, 'toggleFixtureList');
    } else if (keyChar === 'K' && metaKey && e.shiftKey) {
      run(e, 'toggleControlPanel');
    } else if (keyChar === 'F' && metaKey && e.shiftKey) {
      run(e, 'goFullScreen');
    } else if (keyChar === 'E' && metaKey && e.shiftKey) {
      run(e, 'editFixture');
    }
  }

  return () => {
    window.removeEventListener('keydown', handleWindowKeyDown);
  };
}

function isEditing() {
  const activeElement = document.activeElement;
  return activeElement && isInputTag(activeElement.tagName);
}

function isInputTag(tagName: string) {
  const inputTags = ['input', 'textarea', 'select'];
  return inputTags.includes(tagName.toLowerCase());
}
