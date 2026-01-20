import { Component, effect, inject, linkedSignal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import type {
  PluginMessageEvent,
  PluginUIEvent,
  ThemePluginEvent,
  SetColorsPluginEvent,
  TokenFileExtraData,
} from '../model';
import { filter, fromEvent, map, merge, take } from 'rxjs';
import { transformToToken } from './utils/transform-to-token';
import { SvgComponent } from './components/svg.component';

@Component({
  selector: 'app-root',
  imports: [SvgComponent],
  template: `
    <h1 class="title title-m">Convert your colors assets to Design Tokens</h1>
    <p class="description body-m">
      A Penpot plugin to generate a JSON file with your color styles in a
      <a target="_blank" href="https://tr.designtokens.org/format/"
        >Design Token Standard format</a
      >.
    </p>
    @if (result()) {
      <div class="success body-s">
        <app-svg name="tick" />
        Colors convertered to tokens successfully!
      </div>
    }
    <div class="actions">
      @if (result()) {
        <button
          type="button"
          data-appearance="secondary"
          class="restart-btn"
          (click)="restart()"
        >
          <app-svg name="reload" />
          Restart
        </button>
      } @else {
        <button type="button" (click)="convert()" data-appearance="primary">
          Convert colors
        </button>
      }

      <button
        (click)="handleDownload()"
        class="download-btn"
        type="button"
        data-appearance="primary"
        [attr.disabled]="result() ? null : true"
      >
        <app-svg name="download" />
        Download
      </button>
    </div>

    <!-- @if (result()) {
      <p class="body-m download-note">
        Now you can modify and import it (link to help center)
      </p>
    } -->
  `,
  styleUrl: './app.component.css',
  host: {
    '[attr.data-theme]': 'theme()',
  },
})
export class AppComponent {
  route = inject(ActivatedRoute);
  messages$ = fromEvent<MessageEvent<PluginMessageEvent>>(window, 'message');

  initialTheme$ = this.route.queryParamMap.pipe(
    map((params) => params.get('theme')),
    filter((theme) => !!theme),
    take(1),
  );

  theme = toSignal(
    merge(
      this.initialTheme$,
      this.messages$.pipe(
        filter(
          (event): event is MessageEvent<ThemePluginEvent> =>
            event.data.type === 'theme',
        ),
        map((event) => {
          return event.data.content;
        }),
      ),
    ),
  );

  #result = toSignal(
    this.messages$.pipe(
      filter(
        (event): event is MessageEvent<SetColorsPluginEvent> =>
          event.data.type === 'set-colors',
      ),
      map((event) => {
        if (event.data.colors) {
          try {
            const tokens = transformToToken(event.data.colors);

            return {
              tokens,
              name: event.data.fileName,
            };
          } catch (error) {
            console.error(error);
          }
        }

        return null;
      }),
    ),
    {
      initialValue: null,
    },
  );

  result = linkedSignal(() => this.#result());

  constructor() {
    effect(() => {
      if (this.result()) {
        this.#sendMessage({
          type: 'resize',
          width: 410,
          height: 340,
        });
      } else {
        this.#sendMessage({ type: 'reset' });
      }
    });
  }

  #sendMessage(message: PluginUIEvent): void {
    parent.postMessage(message, '*');
  }

  convert(): void {
    this.#sendMessage({ type: 'get-colors' });
  }

  restart(): void {
    this.result.set(null);
  }

  handleDownload() {
    const fileTokens = this.#result();
    if (!fileTokens) return;

    const extraData: TokenFileExtraData = {
      $themes: [],
      $metadata: {
        activeThemes: [],
        tokenSetOrder: [],
        activeSets: [],
      },
    };

    const tokensStructure = {
      ...fileTokens.tokens,
      ...extraData,
    };

    const blob = new Blob([JSON.stringify(tokensStructure)], {
      type: 'text/json',
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileTokens.name + '-tokens.json';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }
}
