import { Component, effect, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import type { Shape } from '@penpot/plugin-types';

@Component({
  selector: 'app-root',
  imports: [ReactiveFormsModule],
  template: `
    <div class="wrapper">
      <h1>Test area!</h1>

      <p>
        Current project name: <span>{{ projectName() }}</span>
      </p>
      <p>
        Counter: <span>{{ counter() }}</span>
      </p>

      <form [formGroup]="form" (ngSubmit)="updateName()">
        <div class="name-wrap">
          <label>Selected Shape: </label>
          <input class="input" type="text" formControlName="name" />
          <button type="submit" data-appearance="primary">Update</button>
        </div>
      </form>

      <div class="actions-wrap">
        <button
          type="button"
          data-appearance="secondary"
          (click)="createRect()"
        >
          +Rect
        </button>
        <button type="button" data-appearance="secondary" (click)="moveX()">
          Move X
        </button>
        <button type="button" data-appearance="secondary" (click)="moveY()">
          Move Y
        </button>
        <button type="button" data-appearance="secondary" (click)="resizeW()">
          Resize W
        </button>
        <button type="button" data-appearance="secondary" (click)="resizeH()">
          Resize H
        </button>
        <button
          type="button"
          data-appearance="secondary"
          (click)="loremIpsum()"
        >
          Lorem Ipsum
        </button>
        <button type="button" data-appearance="secondary" (click)="addIcon()">
          + Icon
        </button>
        <button
          type="button"
          data-appearance="secondary"
          (click)="createGrid()"
        >
          + Grid
        </button>
        <button
          type="button"
          data-appearance="secondary"
          (click)="createPalette()"
        >
          Create color palette board
        </button>
        <button
          type="button"
          data-appearance="secondary"
          (click)="increaseCounter()"
        >
          +COUNTER
        </button>
        <button
          type="button"
          data-appearance="secondary"
          (click)="stylizeWords()"
        >
          WORDS STYLES
        </button>
        <button
          type="button"
          data-appearance="secondary"
          (click)="rotateShapes()"
        >
          Rotate
        </button>
        <button
          type="button"
          data-appearance="secondary"
          (click)="createMargins()"
        >
          Add Margins
        </button>

        <button
          type="button"
          data-appearance="secondary"
          (click)="addComment()"
        >
          Add comment
        </button>
        <button
          type="button"
          data-appearance="secondary"
          (click)="exportFile()"
        >
          Export File
        </button>
        <button
          type="button"
          data-appearance="secondary"
          (click)="exportSelected()"
        >
          Export Selected
        </button>
        <button
          type="button"
          data-appearance="secondary"
          (click)="resizeModal()"
        >
          Resize
        </button>
        <button
          type="button"
          data-appearance="secondary"
          (click)="testLocalStorage()"
        >
          Test local storage
        </button>

        <input type="file" class="file-upload" (change)="uploadImage($event)" />
      </div>
      <hr />
      <div>
        <h1>Variants</h1>
        <div class="actions-wrap">
          <button
            type="button"
            data-appearance="secondary"
            (click)="transformInVariant()"
          >
            Transform in variant
          </button>

          <button
            type="button"
            data-appearance="secondary"
            (click)="combineSelectedAsVariants()"
          >
            Combine selected as variants
          </button>

          <button
            type="button"
            data-appearance="secondary"
            (click)="addVariant()"
          >
            Add Variant
          </button>

          <button
            type="button"
            data-appearance="secondary"
            (click)="addProperty()"
          >
            Add Property
          </button>

          <button
            type="button"
            data-appearance="secondary"
            (click)="removeProperty()"
          >
            Remove Property
          </button>

          <button
            type="button"
            data-appearance="secondary"
            (click)="renameProperty()"
          >
            Rename Property
          </button>

          <button
            type="button"
            data-appearance="secondary"
            (click)="setVariantProperty()"
          >
            Set variant property
          </button>
        </div>

        <button
          type="button"
          data-appearance="secondary"
          (click)="switchVariant()"
        >
          Switch
        </button>
      </div>

      <p>
        <button
          (click)="close()"
          type="button"
          data-appearance="primary"
          data-variant="destructive"
          class="act-close-plugin"
        >
          Close plugin
        </button>
      </p>
    </div>
  `,
  styleUrl: './app.component.css',
})
export class AppComponent {
  #pageId: null | string = null;
  #fileId = null;
  #revn = 0;
  #selection = signal<Shape[]>([]);

  form = new FormGroup({
    name: new FormControl(''),
  });
  theme = signal('');
  projectName = signal('Unknown');
  counter = signal(0);

  constructor() {
    window.addEventListener('message', (event) => {
      if (event.data.type === 'file') {
        this.#fileId = event.data.content.id;
        this.#revn = event.data.content.revn;
      } else if (event.data.type === 'page') {
        this.#refreshPage(
          event.data.content.page.id,
          event.data.content.page.name,
        );
      } else if (event.data.type === 'selection') {
        this.#refreshSelection(event.data.content.selection);
        this.counter.set(event.data.content.counter);
      } else if (event.data.type === 'init') {
        this.#fileId = event.data.content.fileId;
        this.#revn = event.data.content.revn;
        this.#refreshPage(event.data.content.pageId, event.data.content.name);
        this.#refreshSelection(event.data.content.selection);
        this.theme.set(event.data.content.theme);
        this.counter.set(event.data.content.counter);
      } else if (event.data.type === 'theme') {
        this.theme.set(event.data.content);
      } else if (event.data.type === 'update-counter') {
        this.counter.set(event.data.content.counter);
      } else if (event.data.type === 'start-download') {
        this.#startDownload(event.data.name, event.data.content);
      }
    });

    this.#sendMessage({ content: 'ready' });

    effect(() => {
      document.body.setAttribute('data-theme', this.theme());
    });
  }

  close() {
    this.#sendMessage({ content: 'close' });
  }

  updateName() {
    const id = this.#selection()[0].id;
    const name = this.form.get('name')?.value;
    this.#sendMessage({ content: 'change-name', data: { id, name } });
  }

  createRect() {
    this.#sendMessage({ content: 'create-rect' });
  }

  moveX() {
    const id = this.#selection()[0].id;
    this.#sendMessage({ content: 'move-x', data: { id } });
  }

  moveY() {
    const id = this.#selection()[0].id;
    this.#sendMessage({ content: 'move-y', data: { id } });
  }

  resizeW() {
    const id = this.#selection()[0].id;
    this.#sendMessage({ content: 'resize-w', data: { id } });
  }

  resizeH() {
    const id = this.#selection()[0].id;
    this.#sendMessage({ content: 'resize-h', data: { id } });
  }

  loremIpsum() {
    this.#sendMessage({ content: 'lorem-ipsum' });
  }

  addIcon() {
    this.#sendMessage({ content: 'add-icon' });
  }

  createGrid() {
    this.#sendMessage({ content: 'create-grid' });
  }

  createPalette() {
    this.#sendMessage({ content: 'create-colors' });
  }

  increaseCounter() {
    this.#sendMessage({ content: 'increase-counter' });
  }

  stylizeWords() {
    this.#sendMessage({ content: 'word-styles' });
  }

  rotateShapes() {
    this.#sendMessage({ content: 'rotate-selection' });
  }

  createMargins() {
    this.#sendMessage({ content: 'create-margins' });
  }

  addComment() {
    this.#sendMessage({ content: 'add-comment' });
  }

  exportFile() {
    this.#sendMessage({ content: 'export-file' });
  }

  exportSelected() {
    this.#sendMessage({ content: 'export-selected' });
  }

  async uploadImage(event: Event) {
    const input = event.target as HTMLInputElement;
    if (input?.files?.length) {
      const file = input?.files[0];

      if (file) {
        const buff = await file.arrayBuffer();
        const data = new Uint8Array(buff);
        const mimeType = file.type;
        this.#sendMessage({
          content: 'create-image-data',
          data: { data, mimeType },
        });
        input.value = '';
      }
    }
  }

  resizeModal() {
    this.#sendMessage({ content: 'resize-modal' });
  }

  testLocalStorage() {
    this.#sendMessage({ content: 'save-localstorage' });
  }

  #sendMessage(message: unknown) {
    parent.postMessage(message, '*');
  }

  #refreshPage(pageId: string, name: string) {
    this.#pageId = pageId;
    this.projectName.set(name || 'Unknown');
  }

  #refreshSelection(selection: Shape[]) {
    this.#selection.set(selection);
    if (selection && selection.length > 0) {
      this.form.get('name')?.setValue(this.#selection()[0].name);
    } else {
      this.form.get('name')?.setValue('');
    }
  }

  #startDownload(name: string, data: Uint8Array) {
    const blob = new Blob([data], { type: 'application/octet-stream' });

    // We need to start a download with this URL
    const downloadURL = URL.createObjectURL(blob);

    // Download
    var a = document.createElement('a');
    document.body.appendChild(a);
    a.href = downloadURL;
    a.download = name;
    a.click();

    // Remove temporary
    URL.revokeObjectURL(a.href);
    a.remove();
  }

  transformInVariant() {
    this.#sendMessage({ content: 'transform-in-variant' });
  }

  combineSelectedAsVariants() {
    this.#sendMessage({ content: 'combine-selected-as-variants' });
  }

  addVariant() {
    this.#sendMessage({ content: 'add-variant' });
  }

  addProperty() {
    this.#sendMessage({ content: 'add-property' });
  }

  removeProperty() {
    let input = prompt('Property position?');
    if (input !== null) {
      const pos = parseInt(input, 10);
      if (!isNaN(pos)) {
        this.#sendMessage({ content: 'remove-property', data: pos });
      }
    }
  }

  renameProperty() {
    let input = prompt('Property position?');
    if (input !== null) {
      const pos = parseInt(input, 10);
      if (!isNaN(pos)) {
        let name = prompt('New name?');
        if (name !== null) {
          this.#sendMessage({
            content: 'rename-property',
            data: { pos, name },
          });
        }
      }
    }
  }

  setVariantProperty() {
    let input = prompt('Property position?');
    if (input !== null) {
      const pos = parseInt(input, 10);
      if (!isNaN(pos)) {
        let value = prompt('New value?');
        if (value !== null) {
          this.#sendMessage({
            content: 'set-variant-property',
            data: { pos, value },
          });
        }
      }
    }
  }

  switchVariant() {
    let input = prompt('Property position?');
    if (input !== null) {
      const pos = parseInt(input, 10);
      if (!isNaN(pos)) {
        let value = prompt('New value?');
        if (value !== null) {
          this.#sendMessage({
            content: 'switch-variant',
            data: { pos, value },
          });
        }
      }
    }
  }
}
