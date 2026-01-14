import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import type {
  PluginMessageEvent,
  PluginUIEvent,
  ThemePluginEvent,
} from '../model';
import { filter, fromEvent, map, merge, take } from 'rxjs';
import { CommonModule } from '@angular/common';
import { Shape } from '@penpot/plugin-types';

@Component({
  imports: [CommonModule],
  selector: 'app-root',
  template: `
    <div class="wrapper body-s">
      @if (selection().length === 0) {
        <p class="empty-preview">
          Select two filled shapes to calculate the color contrast between them.
        </p>
      } @else if (selection().length === 1) {
        <p class="empty-preview">
          Select <span class="bold">one more</span> filled shape to calculate
          the color contrast between the selected colors.
        </p>
      } @else if (selection().length >= 2) {
        <div class="contrast-preview">
          <p>Selected colors:</p>
          <div class="color-box"></div>
          <ul class="select-colors">
            <li>
              {{ color1() }}
            </li>
            <li>{{ color2() }}</li>
          </ul>
        </div>
        <p class="contrast-ratio">
          Contrast ratio: <span>{{ result() }} : 1</span>
        </p>
        <div class="contrast-results">
          <div class="contrast-result">
            <p class="title">Normal text:</p>
            <ul class="list">
              <li
                class="tag"
                [ngClass]="
                  result() >= contrastStandards.AA.normal ? 'good' : 'fail'
                "
              >
                AA
              </li>
              <li
                class="tag"
                [ngClass]="
                  result() >= contrastStandards.AAA.normal ? 'good' : 'fail'
                "
              >
                AAA
              </li>
            </ul>
          </div>
          <div class="contrast-result">
            <p class="title">
              Large text
              <span class="body-xs">(starting from 19px bold or 24px):</span>
            </p>
            <ul class="list">
              <li
                class="tag"
                [ngClass]="
                  result() >= contrastStandards.AA.large ? 'good' : 'fail'
                "
              >
                AA
              </li>
              <li
                class="tag"
                [ngClass]="
                  result() >= contrastStandards.AAA.large ? 'good' : 'fail'
                "
              >
                AAA
              </li>
            </ul>
          </div>
          <div class="contrast-result">
            <p class="title">
              Graphics
              <span class="body-xs">(such as form input borders):</span>
            </p>
            <ul class="list">
              <li
                class="tag"
                [ngClass]="
                  result() >= contrastStandards.graphics ? 'good' : 'fail'
                "
              >
                AA
              </li>
            </ul>
          </div>
        </div>
      }
    </div>
  `,
  styleUrl: './app.component.css',
  host: {
    '[attr.data-theme]': 'theme()',
    '[style.--color1]': 'color1()',
    '[style.--color2]': 'color2()',
  },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  #route = inject(ActivatedRoute);
  #messages$ = fromEvent<MessageEvent<PluginMessageEvent>>(window, 'message');

  #initialTheme$ = this.#route.queryParamMap.pipe(
    map((params) => params.get('theme')),
    filter((theme) => !!theme),
    take(1),
  );

  selection = toSignal(
    this.#messages$.pipe(
      filter(
        (event) =>
          event.data.type === 'init' || event.data.type === 'selection',
      ),
      map((event) => {
        if (event.data.type === 'init') {
          return event.data.content.selection;
        } else if (event.data.type === 'selection') {
          return event.data.content;
        }

        return [];
      }),
      map((shapes) => {
        return shapes
          .map((shape) => this.#getShapeColor(shape))
          .filter((color): color is string => !!color);
      }),
    ),
    {
      initialValue: [],
    },
  );

  theme = toSignal(
    merge(
      this.#initialTheme$,
      this.#messages$.pipe(
        map((event) => event.data),
        filter((data): data is ThemePluginEvent => data.type === 'theme'),
        map((data) => {
          return data.content;
        }),
      ),
    ),
  );

  color1 = computed(() => {
    return this.selection().at(-2);
  });

  color2 = computed(() => {
    return this.selection().at(-1);
  });

  result = computed<number>(() => {
    const color1 = this.color1();
    const color2 = this.color2();

    if (!color1 || !color2) {
      return 0;
    }

    const lum1 = this.#getLuminosity(color1) + 0.05;
    const lum2 = this.#getLuminosity(color2) + 0.05;

    const result = lum1 > lum2 ? lum1 / lum2 : lum2 / lum1;

    return Number(result.toFixed(2));
  });

  contrastStandards = {
    AA: {
      normal: 4.5,
      large: 3,
    },
    AAA: {
      normal: 7,
      large: 4.5,
    },
    graphics: 3,
  } as const;

  constructor() {
    this.#sendMessage({ type: 'ready' });
  }

  #getLuminosity(color: string) {
    const rgb = this.#hexToRgb(color);
    const a = rgb.map((v) => {
      v /= 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * a[0] + 0.7152 * a[1] + 0.0722 * a[2];
  }

  #hexToRgb(hex: string) {
    const r = parseInt(hex.slice(1, 3), 16);
    const g = parseInt(hex.slice(3, 5), 16);
    const b = parseInt(hex.slice(5, 7), 16);
    return [r, g, b];
  }

  #getShapeColor(shape?: Shape): string | undefined {
    const fills = shape?.fills;
    if (fills && fills !== 'mixed') {
      return fills?.[0]?.fillColor ?? shape?.strokes?.[0]?.strokeColor;
    }
    return undefined;
  }

  #sendMessage(message: PluginUIEvent) {
    parent.postMessage(message, '*');
  }
}
