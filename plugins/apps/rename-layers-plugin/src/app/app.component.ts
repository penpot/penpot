import { Component, ElementRef, ViewChild, inject } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import type {
  PluginMessageEvent,
  ReplaceText,
  ThemePluginEvent,
} from '../app/model';
import { filter, fromEvent, map, merge, take } from 'rxjs';
import { FormsModule } from '@angular/forms';
import { Shape } from '@penpot/plugin-types';

@Component({
  imports: [RouterModule, CommonModule, FormsModule],
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
  host: {
    '[attr.data-theme]': 'theme()',
  },
})
export class AppComponent {
  @ViewChild('searchElement') public searchElement!: ElementRef;
  @ViewChild('addElement') public addElement!: ElementRef;

  route = inject(ActivatedRoute);
  messages$ = fromEvent<MessageEvent<PluginMessageEvent>>(window, 'message');
  public textToReplace: ReplaceText = {
    search: '',
    replace: '',
  };
  public addText = '[Original layer name]';
  public tab: 'add' | 'replace' = 'add';
  public btnFeedback = false;

  constructor() {
    this.sendMessage({ type: 'ready' });
  }

  initialTheme$ = this.route.queryParamMap.pipe(
    map((params) => params.get('theme')),
    filter((theme) => !!theme),
    take(1),
  );

  theme = toSignal(
    merge(
      this.initialTheme$,
      this.messages$.pipe(
        filter((event) => event.data.type === 'theme'),
        map((event) => {
          return (event.data as ThemePluginEvent).content;
        }),
      ),
    ),
  );

  previewList = toSignal(
    this.messages$.pipe(
      filter(
        (event) =>
          event.data.type === 'init' || event.data.type === 'selection',
      ),
      map((event) => {
        if (event.data.type === 'init') {
          return event.data.content.selection;
        } else if (event.data.type === 'selection') {
          return event.data.content.selection;
        }

        return [];
      }),
    ),
    {
      initialValue: [],
    },
  );

  public updateText() {
    if (this.tab === 'replace') {
      this.sendMessage({ type: 'replace-text', content: this.textToReplace });
      this.handleBtnFeedback();
      this.searchElement.nativeElement.focus();
      this.resetForm();
    } else {
      const elementsToUpdate = this.previewList().map((item) => {
        return {
          current: item.name,
          new: this.resultAddText(item),
        };
      });
      this.sendMessage({ type: 'add-text', content: elementsToUpdate });
      this.handleBtnFeedback();
      this.addElement.nativeElement.focus();
      this.resetForm();
    }
  }

  public previewReplace() {
    this.sendMessage({
      type: 'preview-replace-text',
      content: this.textToReplace,
    });
  }

  public resultReplaceText(text: string) {
    return text.replace(this.textToReplace.search, this.textToReplace.replace);
  }

  public highlightMatch(text: string) {
    if (this.textToReplace.search) {
      return text.replace(
        this.textToReplace.search,
        `<span class="highlight">${this.textToReplace.search}</span>`,
      );
    } else {
      return text;
    }
  }

  public selectTab(tab: 'add' | 'replace') {
    this.tab = tab;
    this.resetForm();
  }

  public resetForm() {
    this.textToReplace.search = '';
    this.textToReplace.replace = '';
    this.addText = '[Original layer name]';
    this.sendMessage({ type: 'ready' });
  }

  public handleBtnFeedback() {
    this.btnFeedback = true;
    setTimeout(() => {
      this.btnFeedback = false;
    }, 750);
  }

  public resultAddText(shape: Shape) {
    return this.addText.replace('[Original layer name]', shape.name);
  }

  private sendMessage(message: PluginMessageEvent): void {
    parent.postMessage(message, '*');
  }
}
