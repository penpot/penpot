import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FeatherIconNames, icons } from 'feather-icons';
import { SafeHtmlPipe } from './pipes/safe-html.pipe';
import { IconButtonComponent } from './components/icon-button/icon-button.component';
import { IconSearchComponent } from './components/icon-search/icon-search.component';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, fromEvent, map, merge, take } from 'rxjs';
import { PluginMessageEvent } from '../model';

@Component({
  selector: 'app-root',
  imports: [
    RouterModule,
    SafeHtmlPipe,
    IconButtonComponent,
    IconSearchComponent,
  ],
  styleUrl: './app.component.css',
  template: `<div class="icons-plugin">
    <div class="icons-search">
      <app-icon-search
        (searchIcons)="this.searchIcons($event)"
      ></app-icon-search>
    </div>
    @if (iconKeys().length === 0) {
      <div class="no-icons-found">No icons found</div>
    } @else {
      <div class="icons-list">
        @for (key of iconKeys(); track key) {
          <app-icon-button
            [class]="theme()"
            [icon]="icons()[key]"
            (insertIcon)="this.insertIcon(key)"
          ></app-icon-button>
        }
      </div>
    }
  </div>`,
  host: {
    '[attr.data-theme]': 'theme()',
  },
})
export class AppComponent {
  public route = inject(ActivatedRoute);
  public icons = signal(icons);
  public iconKeys = signal(Object.keys(icons) as FeatherIconNames[]);
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

  public insertIcon(key: FeatherIconNames): void {
    if (
      key &&
      this.icons()[key] &&
      this.icons()[key].toSvg({
        'stroke-width': '3',
      })
    ) {
      this.sendMessage({
        type: 'insert-icon',
        content: {
          svg: this.icons()[key].toSvg(),
          name: this.icons()[key].name || key,
        },
      });
    }
  }

  public searchIcons(search: string): void {
    const allKeys = Object.keys(icons) as FeatherIconNames[];

    if (search === '') {
      this.iconKeys.set(allKeys);
      return;
    }

    const filtered = allKeys.filter(
      (key) =>
        this.icons()[key].tags.some((t) => t.match(search)) ||
        this.icons()[key].name.match(search),
    ) as FeatherIconNames[];

    this.iconKeys.set(filtered);
  }

  private sendMessage(message: unknown): void {
    parent.postMessage(message, '*');
  }
}
