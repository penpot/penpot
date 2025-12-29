import { Component, output, input } from '@angular/core';
import { SafeHtmlPipe } from '../../pipes/safe-html.pipe';
import { FeatherIcon } from 'feather-icons';

@Component({
  selector: 'app-icon-button',
  imports: [SafeHtmlPipe],
  styleUrl: './icon-button.component.css',
  template: `<button
    class="icon-button"
    [attr.aria-label]="'Insert icon: ' + icon().name"
    [title]="icon().name"
    (click)="onInsertIcon()"
    type="button"
  >
    <svg
      aria-hidden="true"
      width="24"
      height="24"
      view-box="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2.5"
      stroke-linecap="round"
      stroke-linejoin="round"
      [innerHtml]="icon().contents | safeHtml"
    ></svg>
  </button>`,
})
export class IconButtonComponent {
  public icon = input.required<FeatherIcon>();
  public insertIcon = output<void>();

  public onInsertIcon(): void {
    this.insertIcon.emit();
  }
}
