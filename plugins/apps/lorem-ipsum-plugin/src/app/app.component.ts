import { Component } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import type {
  GenerationTypes,
  PluginMessageEvent,
  PluginUIEvent,
} from '../model';
import { filter, fromEvent, map, merge, of } from 'rxjs';

@Component({
  imports: [ReactiveFormsModule],
  selector: 'app-root',
  template: `
    <form [formGroup]="form" class="sections-wrapper" (ngSubmit)="generate()">
      <section class="regular-generate">
        <p class="body-s">
          Select a text field to replace it with a placeholder text.
        </p>

        <div class="generation-options">
          <input
            formControlName="num"
            type="number"
            class="input generation-size"
            min="1"
          />

          <select formControlName="type" class="select generation-type">
            <option value="paragraphs">Paragraphs</option>
            <option value="sentences">Sentences</option>
            <option value="words">Words</option>
            <option value="characters">Characters</option>
          </select>
        </div>
        <button type="submit" data-appearance="primary">Generate</button>
      </section>
      <section class="extra-options">
        <div class="checkbox-container">
          <input
            formControlName="startWith"
            class="checkbox-input"
            type="checkbox"
            id="startWith"
            value="checkbox_second"
          />
          <label for="startWith" class="body-s">Start with 'Lorem Ipsum'</label>
        </div>

        <div class="checkbox-container">
          <input
            formControlName="autoClose"
            class="checkbox-input"
            type="checkbox"
            id="autoClose"
            value="checkbox_second"
          />
          <label for="autoClose" class="body-s">Auto close</label>
        </div>
      </section>
    </form>
  `,
  styleUrl: './app.component.css',
  host: {
    '[attr.data-theme]': 'theme()',
  },
})
export class AppComponent {
  messages$ = fromEvent<MessageEvent<PluginMessageEvent>>(window, 'message');

  initialTheme$ = of(
    new URLSearchParams(window.location.search).get('theme'),
  ).pipe(filter((theme) => !!theme));

  theme = toSignal(
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

  form = new FormGroup({
    num: new FormControl<number>(1, { nonNullable: true }),
    type: new FormControl<GenerationTypes>('paragraphs', { nonNullable: true }),
    startWith: new FormControl(true, { nonNullable: true }),
    autoClose: new FormControl(false, { nonNullable: true }),
  });

  constructor() {
    this.#sendMessage({ type: 'ready' });
  }

  generate() {
    const formValue = this.form.getRawValue();

    this.#sendMessage({
      type: 'text',
      generationType: formValue.type,
      startWithLorem: formValue.startWith,
      size: formValue.num,
      autoClose: formValue.autoClose,
    });
  }

  #sendMessage(message: PluginUIEvent) {
    parent.postMessage(message, '*');
  }
}
