import { Pipe, PipeTransform } from '@angular/core';
import { I18nService } from './i18n.service';

/** Pipe impuro de propósito: reavalia a cada change detection após troca de idioma. */
@Pipe({ name: 'translate', standalone: true, pure: false })
export class TranslatePipe implements PipeTransform {
  constructor(private readonly i18n: I18nService) {}

  transform(key: string, params: Record<string, string | number> = {}): string {
    if (!key) return '';
    return this.i18n.t(key, params);
  }
}
