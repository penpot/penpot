import { Component, output, signal } from '@angular/core';
import { icons } from 'feather-icons';

@Component({
  selector: 'app-icon-search',
  imports: [],
  styleUrl: './icon-search.component.css',
  template: `
    <input
      class="search-icon"
      type="search"
      placeholder="Search an icon"
      (input)="onSearchIcons($event)"
    />
  `,
})
export class IconSearchComponent {
  public searchIcons = output<string>();
  public iconsCount = signal(Object.keys(icons).length);

  public onSearchIcons(event: Event): void {
    const target = event.target as HTMLInputElement;
    this.searchIcons.emit(target?.value.toLowerCase() || '');
  }
}
