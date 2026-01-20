import html from './app.element.html?raw';
import 'plugins-styles/lib/styles.css';
import './app.element.css';

export class AppElement extends HTMLElement {
  public static observedAttributes = [];

  connectedCallback() {
    this.innerHTML = html;

    Array.from(this.querySelectorAll('template')).forEach((el: HTMLElement) => {
      const pre = document.createElement('pre');
      const code = document.createElement('code');
      code.classList.add('language-html');
      const removeLineIndentation = el.innerHTML.replaceAll(
        this.getIndentationSize(el.innerHTML),
        '',
      );

      code.textContent = removeLineIndentation;

      pre.appendChild(code);

      el.parentNode?.appendChild(pre);
      el.remove();
    });

    (window as any).hljs.highlightAll();
  }

  getIndentationSize(str: string) {
    const size = str.length - str.trimStart().length;
    return ' '.repeat(size - 1);
  }
}
customElements.define('app-root', AppElement);
