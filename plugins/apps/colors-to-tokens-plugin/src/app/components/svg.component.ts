import { Component, input } from '@angular/core';

@Component({
  selector: 'app-svg',
  template: `
    @switch (name()) {
      @case ('tick') {
        <svg
          xmlns="http://www.w3.org/2000/svg"
          fill="none"
          viewBox="1154.667 712.01 14.666 11.333"
        >
          <path d="m1167.333 714.01-7.333 7.333-3.333-3.333" />
          <path
            stroke-linecap="round"
            d="m1167.333 714.01-7.333 7.333-3.333-3.333"
          />
        </svg>
      }
      @case ('download') {
        <svg
          xmlns="http://www.w3.org/2000/svg"
          fill="none"
          viewBox="859 710.01 16 16"
        >
          <path
            stroke-linecap="round"
            d="M873 720.01v2.667a1.335 1.335 0 0 1-1.333 1.333h-9.334a1.335 1.335 0 0 1-1.333-1.333v-2.667m2.667-3.333L867 720.01m0 0 3.333-3.333M867 720.01v-8"
          />
        </svg>
      }
      @case ('reload') {
        <svg
          viewBox="0 0 16 16"
          stroke-linecap="round"
          stroke-linejoin="round"
          xmlns="http://www.w3.org/2000/svg"
        >
          <path d="M2.4 8a6 6 0 1 1 1.758 4.242M2.4 8l2.1-2zm0 0L1 5.5z"></path>
        </svg>
      }
    }
  `,
  styleUrl: './svg.component.css',
})
export class SvgComponent {
  name = input.required<'tick' | 'download' | 'reload'>();
}
