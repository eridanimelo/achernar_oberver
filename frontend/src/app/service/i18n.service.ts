import { Injectable, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export type SupportedLang = 'pt' | 'en' | 'es' | 'fr' | 'it';

export const SUPPORTED_LANGS: Array<{ code: SupportedLang; label: string; intl: string }> = [
  { code: 'pt', label: 'Português', intl: 'pt-BR' },
  { code: 'en', label: 'English', intl: 'en-US' },
  { code: 'es', label: 'Español', intl: 'es-ES' },
  { code: 'fr', label: 'Français', intl: 'fr-FR' },
  { code: 'it', label: 'Italiano', intl: 'it-IT' },
];

const STORAGE_KEY = 'achernar.lang';
const FALLBACK: SupportedLang = 'pt';

type Dict = Record<string, unknown>;

function flatten(obj: Dict, prefix = '', out: Record<string, string> = {}): Record<string, string> {
  for (const [key, value] of Object.entries(obj)) {
    const full = prefix ? `${prefix}.${key}` : key;
    if (value != null && typeof value === 'object' && !Array.isArray(value)) {
      flatten(value as Dict, full, out);
    } else if (value != null) {
      out[full] = String(value);
    }
  }
  return out;
}

function detect(): SupportedLang {
  try {
    const saved = (localStorage.getItem(STORAGE_KEY) || '').toLowerCase();
    if (['pt', 'en', 'es', 'fr', 'it'].includes(saved)) return saved as SupportedLang;
  } catch { /* storage indisponível: ignora */ }
  const nav = (navigator.language || '').toLowerCase();
  if (nav.startsWith('pt')) return 'pt';
  if (nav.startsWith('es')) return 'es';
  if (nav.startsWith('fr')) return 'fr';
  if (nav.startsWith('it')) return 'it';
  if (nav.startsWith('en')) return 'en';
  return FALLBACK;
}

@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly langSignal = signal<SupportedLang>(detect());
  private readonly dictSignal = signal<Record<string, string>>({});
  private readonly cache = new Map<SupportedLang, Record<string, string>>();
  private readonly loaded = new Set<string>();
  private reqSeq = 0;

  readonly lang = this.langSignal.asReadonly();
  /** Locale BCP-47 para Intl (números/datas). */
  readonly locale = computed(() => SUPPORTED_LANGS.find(l => l.code === this.langSignal())?.intl ?? 'pt-BR');
  /** Incrementado a cada troca de dicionário: pipes impuros/instâncias reavaliam. */
  private readonly revSignal = signal(0);
  readonly rev = this.revSignal.asReadonly();

  constructor(private readonly http: HttpClient) {
    this.use(this.langSignal());
  }

  async use(lang: SupportedLang): Promise<void> {
    const seq = ++this.reqSeq;
    this.langSignal.set(lang);
    try { localStorage.setItem(STORAGE_KEY, lang); } catch { /* ignora */ }
    document.documentElement.lang = lang;
    // Idioma já em cache: só restaura o dicionário (antes o dict ficava
    // preso no idioma anterior e a troca parecia "não funcionar").
    const cached = this.cache.get(lang);
    if (cached) {
      this.dictSignal.set(cached);
      this.revSignal.update(v => v + 1);
      return;
    }
    try {
      const raw = await firstValueFrom(this.http.get<Dict>(`assets/i18n/${lang}.json`));
      if (seq !== this.reqSeq) return; // troca rápida pt->en->pt: descarta resposta obsoleta
      const flat = flatten(raw);
      let merged: Record<string, string>;
      if (lang === FALLBACK) {
        merged = flat;
      } else {
        // Merge com fallback: chaves ausentes caem para pt sem quebrar a tela.
        const fb = await this.ensureFallback();
        if (seq !== this.reqSeq) return;
        merged = { ...fb, ...flat };
      }
      this.cache.set(lang, merged);
      this.loaded.add(lang);
      this.dictSignal.set(merged);
    } catch {
      if (seq !== this.reqSeq) return;
      if (lang !== FALLBACK) {
        const fb = await this.ensureFallback();
        if (seq !== this.reqSeq) return;
        // Não cacheia o fallback como se fosse o idioma: próxima tentativa recarrega.
        this.dictSignal.set(fb);
      }
    } finally {
      if (seq === this.reqSeq) this.revSignal.update(v => v + 1);
    }
  }

  private async ensureFallback(): Promise<Record<string, string>> {
    const cached = this.cache.get(FALLBACK);
    if (cached) return cached;
    try {
      const raw = await firstValueFrom(this.http.get<Dict>(`assets/i18n/${FALLBACK}.json`));
      const flat = flatten(raw);
      this.cache.set(FALLBACK, flat);
      this.loaded.add(FALLBACK);
      return flat;
    } catch {
      return {};
    }
  }

  t(key: string, params: Record<string, string | number> = {}): string {
    // Lê rev para que bindings/métodos reexecutem após troca de idioma.
    this.revSignal();
    const dict = this.dictSignal();
    let text = dict[key] ?? key;
    for (const [k, v] of Object.entries(params)) {
      text = text.replaceAll(`{{${k}}}`, String(v));
    }
    return text;
  }
}
