import { CommonModule } from '@angular/common';
import { HttpParams } from '@angular/common/http';
import { ChangeDetectorRef, Component, HostListener, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription, finalize, forkJoin, timer } from 'rxjs';
import { Span } from './model/span.model';
import { APP_VERSION } from './version';
import { I18nService, SUPPORTED_LANGS, SupportedLang } from './service/i18n.service';
import { ObserverService } from './service/observer.service';
import { TranslatePipe } from './service/translate.pipe';

type DetailTab = 'overview' | 'flow' | 'conversation' | 'tools' | 'cache' | 'metadata';
type DataView = 'friendly' | 'json';

interface ConversationItem {
  role: string;
  content: string;
  toolCalls?: ToolCall[];
  toolCallId?: string;
}

interface FlowNode { id: string; type: 'system' | 'context' | 'file' | 'agent' | 'subagent' | 'skill' | 'rule' | 'mcp' | 'tool' | 'response'; title: string; detail?: string; status?: 'ok' | 'error' | 'info'; level: number; parentId?: string | null; tokens: number; argsPretty?: string; resultPreview?: string; stepIndex: number; fileName?: string; metaName?: string; metaDescription?: string; metaType?: string; }

interface ToolCall {
  id?: string;
  name: string;
  arguments?: unknown;
  result?: string;
  category: 'mcp' | 'tool' | 'subagent' | 'skill' | 'rule' | 'agent';
  provider?: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslatePipe],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent implements OnInit, OnDestroy {
  private liveSource?: EventSource;
  private liveReload?: Subscription;
  private liveReconnect?: Subscription;
  private searchTimer?: Subscription;
  private loadSub?: Subscription;
  private loadSeq = 0;
  loading = false;
  lastUpdated?: Date;
  autoRefresh = true;
  showTechnicalSpans = false;
  search = '';
  detailTab: DetailTab = 'overview';
  dataView: DataView = 'friendly';
  summary: Record<string, number> = {};
  projects: any[] = [];
  allProjects: any[] = [];
  spans: Span[] = [];
  series: any[] = [];
  selected?: Span;
  project = '';
  period = 'today';
  page = 0;
  pageSize = 50;
  totalElements = 0;
  totalPages = 0;
  detailLoading = false;
  expandedFlow = new Set<string>();
  flowFilter = 'all';
  flowView: 'list' | 'graph' = 'list';
  flowZoom = 1;
  flowPanX = 0;
  flowPanY = 0;
  graphFullscreen = false;
  graphCustomPos = new Map<string, { x: number; y: number }>();
  draggingNodeId?: string;  private nodeDrag?: { id: string; startClientX: number; startClientY: number; origX: number; origY: number; moved: boolean };
  private suppressNodeClick = false;
  private graphDrag?: { startX: number; startY: number; panX: number; panY: number };
  showRealPrompt = false;
  showFullSession = false;
  sessionSpans: Span[] = [];
  sessionLoading = false;

  constructor(
    private readonly observerService: ObserverService,
    private readonly cdr: ChangeDetectorRef,
    readonly i18n: I18nService,
  ) {}

  readonly langs = SUPPORTED_LANGS;
  readonly appVersion = APP_VERSION;
  backendVersion?: string;
  showSettings = false;

  toggleSettings(): void {
    this.showSettings = !this.showSettings;
    this.refreshView();
  }

  closeSettings(): void {
    if (!this.showSettings) return;
    this.showSettings = false;
    this.refreshView();
  }

  setLang(code: string): void {
    const lang = (SUPPORTED_LANGS.some(l => l.code === code) ? code : 'pt') as SupportedLang;
    void this.i18n.use(lang).then(() => this.refreshView());
  }

  ngOnInit(): void {
    // Padrão: dia atual + todos os projetos. Carrega uma única vez no init.
    this.project = '';
    this.period = 'today';
    this.page = 0;
    this.load();
    this.connectLive();
    this.observerService.getVersion().subscribe({
      next: v => { this.backendVersion = v?.version; this.refreshView(); },
      error: () => undefined
    });
  }

  ngOnDestroy(): void {
    this.liveReload?.unsubscribe();
    this.liveReconnect?.unsubscribe();
    this.searchTimer?.unsubscribe();
    this.loadSub?.unsubscribe();
    this.liveSource?.close();
  }

  toggleRefresh(): void {
    this.autoRefresh = !this.autoRefresh;
    if (this.autoRefresh) this.connectLive();
    else {
      this.liveReload?.unsubscribe();
      this.liveReconnect?.unsubscribe();
      this.liveSource?.close();
      this.liveSource = undefined;
    }
  }

  clearAll(): void {
    if (!confirm(this.i18n.t('dialogs.confirmClear'))) return;
    this.observerService.clearTraces().subscribe({
      next: () => {
        this.selected = undefined;
        this.spans = []; this.series = []; this.summary = {}; this.projects = [];
        this.load();
      }
    });
  }

  private connectLive(): void {
    this.liveSource?.close();
    this.liveReconnect?.unsubscribe();
    if (!this.autoRefresh) return;
    const source = this.observerService.stream(() => {
      this.liveReload?.unsubscribe();
      this.liveReload = timer(1200).subscribe(() => this.load());
    });
    source.onerror = () => {
      if (!this.autoRefresh) return;
      // O proxy dev (ng serve) pode derrubar o socket SSE em silêncio:
      // sem isso o painel "trava" até religar o Tempo real manualmente.
      this.liveReload?.unsubscribe();
      this.liveReload = timer(1200).subscribe(() => this.load());
      this.liveReconnect?.unsubscribe();
      this.liveReconnect = timer(5000).subscribe(() => {
        if (this.autoRefresh) this.connectLive();
      });
    };
    this.liveSource = source;
  }

  selectSpan(span: Span): void {
    // Não reseta detailTab: trocar de execução mantém Fluxo/Conversa/etc.
    // Isso elimina a sensação de "preciso clicar duas vezes".
    if (this.selected?.id === span.id && !this.detailLoading) return;
    this.detailLoading = true;
    this.selected = span; // feedback imediato (highlight + hero), detalhe chega em seguida
    this.expandedFlow.clear();
    this.flowFilter = 'all';
    this.graphCustomPos.clear();
    this.resetGraphView();
    this.showRealPrompt = false;
    this.showFullSession = false;
    this.sessionSpans = [];
    this.sessionLoading = false;
    this.observerService.getSpan(span.id).subscribe({
      next: detail => {
        this.selected = detail;
        this.detailLoading = false;
        this.refreshView();
        if (this.showFullSession) this.loadSessionSpans();
      },
      error: () => { this.detailLoading = false; this.refreshView(); }
    });
  }

  onToggleFullSession(): void {
    if (this.showFullSession && this.selected?.sessionId) this.loadSessionSpans();
    this.expandedFlow.clear();
    this.refreshView();
  }

  private loadSessionSpans(): void {
    const sid = this.selected?.sessionId;
    if (!sid) return;
    this.sessionLoading = true;
    this.observerService.getSessionTraces(sid).subscribe({
      next: rows => {
        this.sessionSpans = [...(rows || [])].sort((a, b) =>
          String(a.startedAt).localeCompare(String(b.startedAt)));
        this.sessionLoading = false;
        this.refreshView();
      },
      error: () => { this.sessionSpans = []; this.sessionLoading = false; this.refreshView(); }
    });
  }

  get sessionStepCount(): number {
    return this.sessionSpans.filter(s => s.kind === 'llm').length || this.sessionSpans.length;
  }

  get effectiveInputTokens(): number {
    if (this.showFullSession && this.sessionSpans.length)
      return this.sessionSpans.reduce((a, s) => a + Number(s.inputTokens || 0), 0);
    return Number(this.selected?.inputTokens || 0);
  }

  get effectiveOutputTokens(): number {
    if (this.showFullSession && this.sessionSpans.length)
      return this.sessionSpans.reduce((a, s) => a + Number(s.outputTokens || 0), 0);
    return Number(this.selected?.outputTokens || 0);
  }

  get effectiveCacheReadTokens(): number {
    if (this.showFullSession && this.sessionSpans.length)
      return this.sessionSpans.reduce((a, s) => a + Number(s.cacheReadTokens || 0), 0);
    return Number(this.selected?.cacheReadTokens || 0);
  }

  get effectiveCacheWriteTokens(): number {
    if (this.showFullSession && this.sessionSpans.length)
      return this.sessionSpans.reduce((a, s) => a + Number(s.cacheWriteTokens || 0), 0);
    return Number(this.selected?.cacheWriteTokens || 0);
  }

  get effectiveTotalTokens(): number {
    return this.effectiveInputTokens + this.effectiveOutputTokens;
  }

  onSearchChange(): void {
    this.searchTimer?.unsubscribe();
    this.searchTimer = timer(400).subscribe(() => this.applyFilters());
  }

  clearSearch(): void {
    this.search = '';
    this.applyFilters();
  }

  matches(span: Span): boolean {
    const q = this.search.trim().toLowerCase();
    return !q || [span.name, span.model, span.project, span.agent, span.traceId]
      .some(value => (value || '').toLowerCase().includes(q));
  }

  get visibleSpans(): Span[] {
    return this.spans
      .filter(span => (this.showTechnicalSpans || span.kind === 'llm') && this.matches(span))
;
  }

  get cacheRate(): number {
    const input = Number(this.summary['inputTokens'] || 0);
    return input ? Math.min(100, Number((Number(this.summary['cacheReadTokens'] || 0) / input * 100).toFixed(1))) : 0;
  }

  get selectedCacheRate(): number {
    const input = this.effectiveInputTokens;
    return input ? Math.min(100, Number((this.effectiveCacheReadTokens / input * 100).toFixed(1))) : 0;
  }

  get selectedRealInput(): number {
    return Math.max(0, this.effectiveInputTokens - this.effectiveCacheReadTokens);
  }

  /** Estimativa do prompt REAL (não-cacheado).
   *  Prompt cache (Anthropic/OpenAI) é sempre prefixo: o que é novo é o
   *  SUFIXO da conversa. Como o provider só informa tokens (não quais
   *  mensagens vieram do cache), estimamos o sufixo proporcionalmente:
   *  freshChars = realInputTokens * (totalChars / inputTokens).
   *  Caminhamos das últimas mensagens para trás até preencher o orçamento.
   *  A mensagem de fronteira vem parcial, marcada com isPartial. */
  get realPromptSegments(): Array<{ role: string; content: string; isPartial: boolean; fullLength: number; toolCalls?: ToolCall[] }> {
    const messages = this.conversation;
    const input = this.effectiveInputTokens;
    const real = this.selectedRealInput;
    if (!messages.length || !input || !real) return [];
    const effLen = (m: ConversationItem) =>
      (m.content || '').length + ((m.toolCalls || []).length ? JSON.stringify(m.toolCalls).length : 0);
    const totalChars = messages.reduce((acc, m) => acc + effLen(m), 0);
    if (!totalChars) return [];
    const avgCharsPerToken = totalChars / input;
    let budget = Math.max(1, Math.round(real * avgCharsPerToken));
    const picked: Array<{ role: string; content: string; isPartial: boolean; fullLength: number; toolCalls?: ToolCall[] }> = [];
    for (let i = messages.length - 1; i >= 0 && budget > 0; i--) {
      const msg = messages[i];
      const len = effLen(msg);
      if (!len) continue;
      if (len <= budget) {
        picked.unshift({ role: msg.role, content: msg.content, isPartial: false, fullLength: len, toolCalls: msg.toolCalls });
        budget -= len;
      } else {
        // Fronteira do cache no meio desta mensagem: só o final é novo.
        const contentLen = (msg.content || '').length;
        const freshContent = contentLen >= budget
          ? (msg.content || '').slice(contentLen - budget)
          : msg.content;
        picked.unshift({
          role: msg.role,
          content: freshContent,
          isPartial: true,
          fullLength: len,
          toolCalls: contentLen >= budget ? undefined : msg.toolCalls
        });
        budget = 0;
      }
    }
    return picked;
  }

  get realPromptChars(): number {
    return this.realPromptSegments.reduce((acc, s) => acc + s.content.length, 0);
  }

  /** Heurística do prefixo provavelmente cacheado (aba Conversa).
   *  O provider só informa contadores (cache_read), nunca quais mensagens
   *  vieram do cache. Como prompt cache é sempre PREFIXO, estimamos
   *  caminhando das primeiras mensagens até esgotar o orçamento:
   *  cachedChars = cacheReadTokens * (totalChars / inputTokens).
   *  Retorna uma marca por mensagem, alinhada com `conversation`.
   *  A mensagem de fronteira vem como 'partial'. É estimativa, não fato. */
  get cachePrefixMarks(): Array<{ state: 'cached' | 'partial' | 'fresh' }> {
    const messages = this.conversation;
    const input = this.effectiveInputTokens;
    const cached = this.effectiveCacheReadTokens;
    if (!messages.length) return [];
    if (!input || !cached) return messages.map(() => ({ state: 'fresh' as const }));
    const effLen = (m: ConversationItem) =>
      (m.content || '').length + ((m.toolCalls || []).length ? JSON.stringify(m.toolCalls).length : 0);
    const totalChars = messages.reduce((acc, m) => acc + effLen(m), 0);
    if (!totalChars) return messages.map(() => ({ state: 'fresh' as const }));
    let budget = Math.round(cached * (totalChars / input));
    return messages.map(m => {
      const len = effLen(m);
      if (!len || budget <= 0) return { state: 'fresh' as const };
      if (len <= budget) {
        budget -= len;
        return { state: 'cached' as const };
      }
      budget = 0;
      return { state: 'partial' as const };
    });
  }

  get cachedPrefixCount(): number {
    return this.cachePrefixMarks.filter(m => m.state !== 'fresh').length;
  }

  toggleRealPrompt(): void {
    this.showRealPrompt = !this.showRealPrompt;
    this.refreshView();
  }

  goToConversation(): void {
    this.detailTab = 'conversation';
    this.refreshView();
  }

  get maxSeries(): number { return Math.max(1, ...this.series.map(item => item.tokens || 0)); }

  get conversation(): ConversationItem[] {
    if (this.showFullSession && this.sessionSpans.length) return this.sessionConversation;
    return this.singleConversation;
  }

  private linkToolResults(messages: ConversationItem[]): ConversationItem[] {
    const results = new Map<string, string>();
    for (const message of messages) {
      if (message.role === 'tool' && message.toolCallId) results.set(message.toolCallId, message.content);
    }
    for (const message of messages) {
      for (const call of message.toolCalls || []) {
        if (call.id && results.has(call.id)) call.result = results.get(call.id);
      }
    }
    return messages;
  }

  get singleConversation(): ConversationItem[] {
    return this.linkToolResults(this.parseMessages(this.selected?.requestBody));
  }

  get sessionConversation(): ConversationItem[] {
    const all: ConversationItem[] = [];
    for (const span of this.sessionSpans) {
      for (const m of this.parseMessages(span.requestBody)) all.push(m);
      const resp = span.responseBody;
      if (typeof resp === 'string' && resp.trim()) {
        // Resposta final do passo entra como mensagem assistente quando
        // ainda não está no corpo agregado (evita conversa vazia).
        const probe = resp.slice(0, 120);
        if (!all.some(m => m.role === 'assistant' && (m.content || '').includes(probe))) {
          all.push({ role: 'assistant', content: resp.length > 8000 ? resp.slice(0, 8000) : resp });
        }
      }
    }
    return this.linkToolResults(all);
  }

  get usedTools(): ToolCall[] {
    const calls: ToolCall[] = [];
    for (const message of this.conversation) calls.push(...(message.toolCalls || []));
    return calls;
  }

  get flowNodes(): FlowNode[] {
    const nodes: FlowNode[] = [];
    const estimateTokens = (text: string | null | undefined) => Math.ceil((text || '').length / 3.5);

    const messages = this.conversation;
    
    const system = messages.find(m => m.role === 'system');
    if (system) {
      nodes.push({
        id: 'flow-system', type: 'system', title: this.i18n.t('flow.nodeSystem'), detail: this.i18n.t('flow.nodeSystemDetail'),
        status: 'ok', level: 0, tokens: estimateTokens(system.content),
        argsPretty: this.shortText(system.content, 400), resultPreview: '', stepIndex: 0
      });
    }

    let activeAgent = false;
    let activeSkill = false;
    const seenPaths = new Set<string>();

    const normalizePath = (path: string): string => {
      let p = path.startsWith('/') ? path : '/' + path;
      return p.replace(/\/\./g, '/').toLowerCase();
    };

    for (const msg of messages) {
      if (msg.toolCalls) {
        for (const tool of msg.toolCalls) {
          const result = tool.result || '';
          const isError = /error|not found|failed|cannot/i.test(result);
          
          let argDetail = '';
          if (tool.arguments != null && typeof tool.arguments === 'object') {
            const obj = tool.arguments as Record<string, unknown>;
            argDetail = String(obj['filePath'] ?? obj['path'] ?? obj['url'] ?? obj['command'] ?? obj['target'] ?? JSON.stringify(tool.arguments));
          } else if (typeof tool.arguments === 'string') {
            argDetail = tool.arguments;
          }

          const path = (tool.arguments as any)?.filePath || (tool.arguments as any)?.path;
          const toolTokens = estimateTokens(JSON.stringify(tool.arguments)) + estimateTokens(result);

          // A categoria já vem reclassificada (subagent/skill/rule/agent/mcp);
          // o path só refina file vs doc e o nível hierárquico.
          let type: FlowNode['type'] = (tool.category as FlowNode['type']) || 'tool';
          let title = tool.name;
          let level = type === 'subagent' || type === 'agent' ? 1
            : type === 'skill' || type === 'rule' ? 2
            : type === 'mcp' ? 0 : 0;

          if (path) {
            const normPath = normalizePath(path);
            if (seenPaths.has(normPath)) continue;
            seenPaths.add(normPath);

            const filename = path.split('/').pop() || path;
            const lower = path.toLowerCase();

            if (type === 'tool' || type === 'file') {
              if (lower.endsWith('agents.md')) { type = 'agent'; title = filename; level = 0; activeAgent = true; activeSkill = false; }
              else if (lower.includes('/agents/') || lower.includes('agent_') || lower.includes('agent-')) { type = 'agent'; title = filename; level = 1; activeAgent = true; activeSkill = false; }
              else if (lower.includes('subagent')) { type = 'subagent'; title = filename; level = 1; }
              else if (lower.includes('/skills/') || lower.includes('skill')) { type = 'skill'; title = filename; level = 2; activeSkill = true; }
              else if (lower.includes('/rules/') || lower.includes('rule') || lower.endsWith('.mdc') || lower.endsWith('rules.md')) { type = 'rule'; title = filename; level = 2; }
              else { type = 'file'; title = filename; level = activeSkill ? 3 : (activeAgent ? 2 : 1); }
            } else {
              // Tipo semântico já conhecido (ex.: task → subagent): só ajusta
              // título para o arquivo quando houver path, mantendo nível.
              title = this.isGenericDocName(tool.name) || tool.name === 'tool' ? filename : tool.name;
              if (type === 'agent' && level < 1) { level = 1; activeAgent = true; activeSkill = false; }
              if (type === 'skill' || type === 'rule') { level = 2; if (type === 'skill') activeSkill = true; }
              if (type === 'subagent' && level < 1) level = 1;
            }
          } else {
            if (type === 'tool' || type === 'mcp' || type === 'file') {
              level = activeSkill ? 3 : (activeAgent ? 2 : 0);
              if (!path && type === 'file') type = 'tool';
            }
            // subagent/skill/rule/agent sem path mantêm o nível semântico.
          }

          // Header com metadata (skills/agents/rules): name + description + tipo.
          // Se o conteúdo lido tem frontmatter, o título vira o name e o detalhe
          // mostra a descrição. Sem name, a pasta vira o nome (SKILL.md -> minha-skill).
          let fileName: string | undefined;
          let metaName: string | undefined;
          let metaDescription: string | undefined;
          let metaType: string | undefined;
          if (path) {
            fileName = path.split('/').pop() || path;
            if (type === 'agent' || type === 'subagent' || type === 'skill' || type === 'rule' || type === 'file') {
              const meta = this.parseDocHeader(result);
              metaName = meta.name;
              metaDescription = meta.description;
              metaType = meta.docType;
              if (meta.docType && (type === 'file' || type === 'skill' || type === 'agent' || type === 'rule')) {
                const hint = meta.docType.toLowerCase();
                if (hint.includes('skill')) { type = 'skill'; level = 2; activeSkill = true; }
                else if (hint.includes('subagent')) { type = 'subagent'; level = 1; }
                else if (hint.includes('agent')) { type = 'agent'; if (level < 1) level = 1; activeAgent = true; activeSkill = false; }
                else if (hint.includes('rule')) { type = 'rule'; level = 2; }
              }
              if (metaName) title = metaName;
              else if (fileName && this.isGenericDocName(fileName)) title = this.parentFolderName(path) || fileName;
            }
          }

          nodes.push({
            id: `flow-${nodes.length}`,
            type,
            title,
            detail: path ? path : (tool.provider ? `${tool.provider} · ${this.shortText(argDetail, 90)}` : this.shortText(argDetail, 90)),
            status: isError ? 'error' : 'ok',
            level,
            tokens: toolTokens,
            argsPretty: this.prettyJson(tool.arguments),
            resultPreview: this.shortText(result, 500),
            stepIndex: nodes.length,
            fileName,
            metaName,
            metaDescription,
            metaType
          });
        }
      }
    }

    if (this.showFullSession && this.sessionSpans.length) {
      for (const span of this.sessionSpans) {
        const resp = span.responseBody;
        if (resp == null || (typeof resp === 'string' && !resp.trim())) continue;
        const text = String(resp);
        nodes.push({
          id: `flow-${nodes.length}-response`, type: 'response', title: this.i18n.t('flow.nodeResponse'), detail: String(span.model || this.responseModel),
          status: 'ok', level: 0, tokens: estimateTokens(text),
          argsPretty: '', resultPreview: this.shortText(text, 600),
          stepIndex: nodes.length
        });
      }
    } else if (this.selected?.responseBody) {
      nodes.push({
        id: `flow-${nodes.length}-response`, type: 'response', title: this.i18n.t('flow.nodeResponse'), detail: this.responseModel,
        status: 'ok', level: 0, tokens: estimateTokens(String(this.selected.responseBody)),
        argsPretty: '', resultPreview: this.shortText(String(this.selected.responseBody), 600),
        stepIndex: nodes.length
      });
    }

    // NOTA: cache_read.input_tokens é métrica da chamada LLM como um todo
    // (o provider não diz quais nós vieram do cache). Por isso NÃO
    // distribuímos cache por nó aqui. Valores reais ficam no header
    // (selected.inputTokens/cacheReadTokens/outputTokens) e na aba Cache.
    // Hierarquia pai→filho (orquestrador → agent → skill/rule → file/tool):
    // a lista continua sequencial (stepIndex), mas cada nó ganha parentId =
    // o último nó anterior com level menor. O grafo usa parentId nas arestas.
    const stack: FlowNode[] = [];
    for (const node of nodes) {
      while (stack.length && stack[stack.length - 1].level >= node.level) stack.pop();
      node.parentId = stack.length ? stack[stack.length - 1].id : null;
      stack.push(node);
    }
    return nodes;
  }

  get filteredFlowNodes(): FlowNode[] {
    if (this.flowFilter === 'all') return this.flowNodes;
    return this.flowNodes.filter(n => n.type === this.flowFilter);
  }

  get flowStats(): Record<string, number> {
    const stats: Record<string, number> = {};
    for (const n of this.flowNodes) stats[n.type] = (stats[n.type] || 0) + 1;
    return stats;
  }

  isFlowExpanded(id: string): boolean { return this.expandedFlow.has(id); }

  toggleFlowNode(id: string): void {
    if (this.expandedFlow.has(id)) this.expandedFlow.delete(id);
    else this.expandedFlow.add(id);
    this.refreshView();
  }

  // Sem trackBy, os wrappers recriados pelo getter fariam o Angular
  // destruir e recriar os nós a cada change detection — o clique caía
  // num elemento já descartado e o expandir nunca abria.
  trackGraphNode(_index: number, g: { node: FlowNode }): string { return g.node.id; }
  trackGraphEdge(_index: number, e: { key: string }): string { return e.key; }

  expandAllFlow(): void { this.flowNodes.forEach(n => this.expandedFlow.add(n.id)); }
  collapseAllFlow(): void { this.expandedFlow.clear(); }

  setFlowView(view: 'list' | 'graph'): void {
    this.flowView = view;
    this.resetGraphView();
    this.refreshView();
  }

  flowZoomIn(): void {
    this.flowZoom = Math.min(2.5, Math.round((this.flowZoom + 0.2) * 100) / 100);
    this.refreshView();
  }

  flowZoomOut(): void {
    this.flowZoom = Math.max(0.3, Math.round((this.flowZoom - 0.2) * 100) / 100);
    this.refreshView();
  }

  resetGraphView(): void {
    this.flowZoom = 1;
    this.flowPanX = 0;
    this.flowPanY = 0;
    this.graphDrag = undefined;
    this.nodeDrag = undefined;
    this.draggingNodeId = undefined;
  }

  clearGraphLayout(): void {
    this.graphCustomPos.clear();
    this.refreshView();
  }

  private defaultGraphPos(index: number, level: number): { x: number; y: number } {
    // Coluna = profundidade hierárquica; linha = ordem sequencial com
    // respiro por nível para pais não colidirem com os filhos.
    return { x: level * 280 + 20, y: index * 128 + 20 };
  }

  private nodePos(id: string, index: number, level: number): { x: number; y: number } {
    return this.graphCustomPos.get(id) ?? this.defaultGraphPos(index, level);
  }

  onGraphWheel(event: WheelEvent): void {
    event.preventDefault();
    const delta = event.deltaY > 0 ? -0.1 : 0.1;
    this.flowZoom = Math.min(2.5, Math.max(0.3, Math.round((this.flowZoom + delta) * 100) / 100));
    this.refreshView();
  }

  onGraphPanStart(event: MouseEvent): void {
    this.graphDrag = { startX: event.clientX, startY: event.clientY, panX: this.flowPanX, panY: this.flowPanY };
  }

  onGraphPanMove(event: MouseEvent): void {
    if (this.nodeDrag) { this.moveNodeDrag(event); return; }
    if (!this.graphDrag) return;
    this.flowPanX = this.graphDrag.panX + (event.clientX - this.graphDrag.startX);
    this.flowPanY = this.graphDrag.panY + (event.clientY - this.graphDrag.startY);
  }

  onGraphPanEnd(): void { this.graphDrag = undefined; this.endNodeDrag(); }

  onGraphNodeDragStart(event: MouseEvent, id: string, index: number, level: number): void {
    event.stopPropagation();
    // Sem preventDefault: ele cancelava o click do card em alguns navegadores.
    this.graphDrag = undefined; // arrastar nó, não o canvas
    const orig = this.nodePos(id, index, level);
    this.nodeDrag = { id, startClientX: event.clientX, startClientY: event.clientY, origX: orig.x, origY: orig.y, moved: false };
    this.draggingNodeId = id;
  }

  private moveNodeDrag(event: MouseEvent): void {
    const drag = this.nodeDrag;
    if (!drag) return;
    const zoom = Math.max(0.3, this.flowZoom);
    const dx = (event.clientX - drag.startClientX) / zoom;
    const dy = (event.clientY - drag.startClientY) / zoom;
    // Tolerância maior: tremor da mão num clique simples não pode virar "arrasto".
    if (Math.hypot(event.clientX - drag.startClientX, event.clientY - drag.startClientY) > 6) drag.moved = true;
    this.graphCustomPos.set(drag.id, {
      x: Math.max(0, Math.round(drag.origX + dx)),
      y: Math.max(0, Math.round(drag.origY + dy))
    });
  }

  private endNodeDrag(): void {
    if (this.nodeDrag?.moved) this.suppressNodeClick = true;
    this.nodeDrag = undefined;
    this.draggingNodeId = undefined;
  }

  @HostListener('window:mousemove', ['$event'])
  onWindowMouseMove(event: MouseEvent): void {
    if (this.nodeDrag) this.moveNodeDrag(event);
    else if (this.graphDrag) {
      this.flowPanX = this.graphDrag.panX + (event.clientX - this.graphDrag.startX);
      this.flowPanY = this.graphDrag.panY + (event.clientY - this.graphDrag.startY);
    }
  }

  @HostListener('window:mouseup')
  onWindowMouseUp(): void {
    this.graphDrag = undefined;
    this.endNodeDrag();
  }

  toggleFlowNodeGuarded(id: string): void {
    if (this.suppressNodeClick) { this.suppressNodeClick = false; return; }
    this.toggleFlowNode(id);
  }

  toggleGraphFullscreen(): void {
    this.graphFullscreen = !this.graphFullscreen;
    if (!this.graphFullscreen) this.resetGraphView();
    this.refreshView();
  }

  @HostListener('document:keydown.escape')
  onEscapeFullscreen(): void {
    if (this.showSettings) {
      this.showSettings = false;
      this.refreshView();
      return;
    }
    if (this.graphFullscreen) {
      this.graphFullscreen = false;
      this.refreshView();
    }
  }

  downloadGraphImage(): void {
    const nodes = this.graphNodes;
    if (!nodes.length) return;
    const W = this.graphWidth;
    const H = this.graphHeight;
    const esc = (v: string) =>
      (v || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    const short = (v: string, max = 28) => {
      const clean = (v || '').replace(/\s+/g, ' ').trim();
      return clean.length > max ? clean.slice(0, max - 1) + '…' : clean;
    };
    const levelColor = (level: number) =>
      level === 0 ? '#8b95ab' : level === 1 ? '#9d85ff' : level === 2 ? '#ffaca9' : '#4ade80';
    const edges = this.graphEdges
      .map(e => `<path d="${e.d}" fill="none" stroke="${e.isBack ? '#6f5fe0' : '#4a5480'}" stroke-width="2.5" ${e.isBack ? 'stroke-dasharray="7 5"' : ''}/>`).join('');
    const boxes = nodes.map(g => {
      const color = levelColor(g.node.level);
      const title = esc(short(this.flowDisplayTitle(g.node), 26));
      const type = esc(this.flowDisplayType(g.node));
      const sub = esc(short(this.flowDisplayDetail(g.node) || this.flowDepthLabel(g.node.level), 34));
      const tokens = esc(this.i18n.t('flow.tokensStep', { n: g.node.tokens, step: g.index + 1 }));
      return `<g>
        <rect x="${g.x}" y="${g.y}" width="240" height="76" rx="12" fill="#121722" stroke="${color}" stroke-width="1.5"/>
        <rect x="${g.x}" y="${g.y}" width="4" height="76" rx="2" fill="${color}"/>
        <circle cx="${g.x - 2}" cy="${g.y - 2}" r="11" fill="#171c28" stroke="#3a4560"/>
        <text x="${g.x - 2}" y="${g.y + 2}" font-size="9" text-anchor="middle" fill="#9aa5bd" font-family="monospace">${g.index + 1}</text>
        <text x="${g.x + 16}" y="${g.y + 20}" font-size="8" font-weight="bold" letter-spacing="1" fill="${color}" font-family="sans-serif">${type}</text>
        <text x="${g.x + 16}" y="${g.y + 38}" font-size="12" font-weight="bold" fill="#e8ebf5" font-family="sans-serif">${title}</text>
        <text x="${g.x + 16}" y="${g.y + 53}" font-size="9" fill="#78849a" font-family="sans-serif">${sub}</text>
        <text x="${g.x + 16}" y="${g.y + 67}" font-size="8" fill="#5f6b83" font-family="monospace">${tokens}</text>
      </g>`;
    }).join('');
    const svg =
      `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">` +
      `<rect width="${W}" height="${H}" fill="#0b0f18"/>` + edges + boxes + `</svg>`;
    const blob = new Blob([svg], { type: 'image/svg+xml;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
      try {
        const scale = 2;
        const canvas = document.createElement('canvas');
        canvas.width = W * scale;
        canvas.height = H * scale;
        const ctx = canvas.getContext('2d');
        if (!ctx) throw new Error('canvas indisponível');
        ctx.fillStyle = '#0b0f18';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.scale(scale, scale);
        ctx.drawImage(img, 0, 0, W, H);
        URL.revokeObjectURL(url);
        canvas.toBlob(b => {
          if (!b) return;
          const pngUrl = URL.createObjectURL(b);
          const a = document.createElement('a');
          a.href = pngUrl;
          a.download = `fluxo-grafo-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.png`;
          a.click();
          setTimeout(() => URL.revokeObjectURL(pngUrl), 4000);
        }, 'image/png');
      } catch {
        URL.revokeObjectURL(url);
      }
    };
    img.onerror = () => URL.revokeObjectURL(url);
    img.src = url;
  }

  graphTransform(): string {
    return `translate(${this.flowPanX}px, ${this.flowPanY}px) scale(${this.flowZoom})`;
  }

  get graphNodes(): Array<{ node: FlowNode; x: number; y: number; index: number }> {
    const list = this.filteredFlowNodes;
    return list.map((node, i) => {
      const pos = this.nodePos(node.id, i, node.level);
      return { node, x: pos.x, y: pos.y, index: i };
    });
  }

  get graphLevels(): number[] {
    const max = this.filteredFlowNodes.reduce((m, n) => Math.max(m, n.level), 0);
    return Array.from({ length: max + 1 }, (_, i) => i);
  }

  get graphWidth(): number {
    const nodes = this.graphNodes;
    if (!nodes.length) return 600;
    return Math.max(...nodes.map(n => n.x)) + 260;
  }

  get graphHeight(): number {
    const nodes = this.graphNodes;
    if (!nodes.length) return 300;
    return Math.max(...nodes.map(n => n.y)) + 160;
  }

  get graphEdges(): Array<{ d: string; isBack: boolean; key: string }> {
    const pos = this.graphNodes;
    const byId = new Map(pos.map(p => [p.node.id, p]));
    const edges: Array<{ d: string; isBack: boolean; key: string }> = [];
    const edge = (fromId: string, toId: string) => {
      const from = byId.get(fromId);
      const to = byId.get(toId);
      if (!from || !to || fromId === toId) return;
      const x1 = from.x + 240;
      const y1 = from.y + 40;
      const x2 = to.x;
      const y2 = to.y + 40;
      const mx = (x1 + x2) / 2;
      const d = `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`;
      edges.push({ d, isBack: x2 < x1, key: `${fromId}->${toId}` });
    };
    let usedParent = false;
    for (const p of pos) {
      if (p.node.parentId && byId.has(p.node.parentId)) {
        edge(p.node.parentId, p.node.id);
        usedParent = true;
      }
    }
    // Sem hierarquia inferida (tudo no mesmo nível): cai para o sequencial.
    if (!usedParent) {
      for (let i = 1; i < pos.length; i++) edge(pos[i - 1].node.id, pos[i].node.id);
    }
    return edges;
  }

  flowTypeLabel(type: string): string {
    const labels: Record<string, string> = {
      system: this.i18n.t('flow.typeSystem'), agent: this.i18n.t('flow.typeAgent'),
      subagent: this.i18n.t('flow.typeSubagent'), skill: this.i18n.t('flow.typeSkill'),
      rule: this.i18n.t('flow.typeRule'), file: this.i18n.t('flow.typeFile'),
      context: this.i18n.t('flow.typeContext'), tool: this.i18n.t('flow.typeTool'),
      mcp: this.i18n.t('flow.typeMcp'), response: this.i18n.t('flow.typeResponse')
    };
    return labels[type] || type.toUpperCase();
  }

  flowDepthLabel(level: number): string {
    if (level === 0) return this.i18n.t('flow.depthL0');
    if (level === 1) return this.i18n.t('flow.depthL1');
    if (level === 2) return this.i18n.t('flow.depthL2');
    return this.i18n.t('flow.depthLn', { n: level });
  }

  /** Nome de exibição do nó: o name mapeado no header (frontmatter).
   *  Sem prefixo de tipo — o tipo já tem o próprio badge. */
  flowDisplayTitle(node: FlowNode): string {
    return node.title;
  }

  /** Tipo de exibição do nó: o type mapeado no header quando existe
   *  (sempre há, pois o mapeamento tem name/type/description);
   *  senão o rótulo do tipo inferido. Usado na lista e no grafo. */
  flowDisplayType(node: FlowNode): string {
    if (node.metaType) return node.metaType.toUpperCase();
    return this.flowTypeLabel(node.type);
  }

  /** Exibe paths de forma relativa: tenta cortar a partir do nome do
   *  projeto ou de marcadores conhecidos (frontend/, src/, agents/…);
   *  senão mostra os últimos 3 segmentos. O path completo continua
   *  disponível no tooltip (title) e no detalhe expandido. */
  relativeFlowPath(path: string): string {
    if (!path || !path.includes('/')) return path;
    const project = (this.selected?.project || '').trim().toLowerCase();
    if (project) {
      const idx = path.toLowerCase().lastIndexOf(project + '/');
      if (idx >= 0) return './' + path.slice(idx + project.length + 1);
    }
    const markers = ['/frontend/', '/backend/', '/src/', '/.opencode/', '/agents/', '/skills/', '/rules/', '/opt/work/', '/workspace/'];
    const lower = path.toLowerCase();
    for (const marker of markers) {
      const idx = lower.lastIndexOf(marker);
      if (idx >= 0) return '.' + path.slice(idx);
    }
    const parts = path.split('/').filter(Boolean);
    if (parts.length > 3) return '…/' + parts.slice(-3).join('/');
    return path;
  }

  private isAbsPath(value: string): boolean {
    const p = (value || '').trim();
    if (p.startsWith('/')) return true;
    return p.length > 2 && p.charAt(1) === ':' && (p.charAt(2) === '/' || p.charAt(2) === String.fromCharCode(92));
  }

  flowDisplayDetail(node: FlowNode): string {
    const detail = node.detail || '';
    const sep = detail.indexOf('·');
    const head = (sep >= 0 ? detail.slice(0, sep) : detail).trim();
    if (this.isAbsPath(head)) {
      const tail = sep >= 0 ? ' ' + detail.slice(sep).trim() : '';
      return this.shortText(this.relativeFlowPath(head), 90) + tail;
    }
    if (detail.includes('/') && detail.length > 60) return this.shortText(detail, 90);
    return detail;
  }

  /** Extrai name/description/type do header (frontmatter YAML) do conteúdo lido.
   *  Cobre SKILL.md / agents / rules nos formatos:
   *    ---
   *    name: minha-skill
   *    description: o que ela faz
   *    type: skill
   *    ---
   *  Tolera prefixo de nº de linha ("12: name: ..."), aspas e caixas variadas
   *  (Name/NAME, Type/kind/categoria). Retorna vazio quando não há header. */
  parseDocHeader(result: string | null | undefined): { name?: string; description?: string; docType?: string } {
    if (!result) return {};
    const rawLines = String(result).replace(/\\n/g, '\n').split('\n');
    const lines = rawLines.map(l => l.replace(/^\s*\d+\s*[:|]\s?/, ''));
    let start = lines.findIndex(l => l.trim() === '---');
    if (start < 0) {
      // Header sem cercas: primeiras linhas com "name:" valem como header.
      const head = lines.slice(0, 12).join('\n');
      if (!/^\s*(name|nome)\s*:/im.test(head)) return {};
      return this.parseHeaderFields(head);
    }
    const end = lines.findIndex((l, i) => i > start && l.trim() === '---');
    if (end < 0 || end - start > 40) return {};
    return this.parseHeaderFields(lines.slice(start + 1, end).join('\n'));
  }

  private parseHeaderFields(block: string): { name?: string; description?: string; docType?: string } {
    const field = (keys: string[]): string | undefined => {
      for (const key of keys) {
        const re = new RegExp('^\\s*' + key + '\\s*:\\s*(.+?)\\s*$', 'im');
        const m = block.match(re);
        if (m) return m[1].replace(/^['"]|['"]$/g, '').trim() || undefined;
      }
      return undefined;
    };
    const out: { name?: string; description?: string; docType?: string } = {};
    const name = field(['name', 'nome', 'title', 'id']);
    if (name) out.name = name.slice(0, 120);
    const description = field(['description', 'descricao', 'descri[cç][aã]o', 'desc', 'summary']);
    if (description) out.description = description.slice(0, 280);
    const docType = field(['type', 'tipo', 'kind', 'categoria', 'category']);
    if (docType) out.docType = docType.slice(0, 40);
    return out;
  }

  /** Pasta do arquivo: skills/minha-skill/SKILL.md -> minha-skill. */
  parentFolderName(path: string): string | undefined {
    const parts = (path || '').split('/').filter(Boolean);
    if (parts.length < 2) return undefined;
    return parts[parts.length - 2];
  }

  /** SKILL.md, AGENT.md, RULE.md… não identificam nada: o nome real é a pasta. */
  isGenericDocName(filename: string): boolean {
    const base = (filename || '').toLowerCase().replace(/\.[a-z0-9]+$/, '');
    return ['skill', 'skills', 'agent', 'agents', 'subagent', 'subagents', 'rule', 'rules', 'prompt', 'system', 'index', 'readme', 'instruction', 'instructions'].includes(base);
  }

  private shortText(value: string, max = 100): string {
    const clean = (value || '').replace(/\s+/g, ' ').trim();
    return clean.length > max ? clean.slice(0, max) + '…' : clean;
  }

  private toolArgumentSummary(value: unknown): string {
    if (value == null) return '';
    if (typeof value === 'object') {
      const obj = value as Record<string, unknown>;
      const preferred = obj['filePath'] ?? obj['path'] ?? obj['url'] ?? obj['command'] ?? obj['target'];
      if (preferred != null) return this.shortText(String(preferred), 90);
    }
    return this.shortText(typeof value === 'string' ? value : this.prettyJson(value), 90);
  }

  get declaredTools(): Array<{ name: string; description?: string }> {
    const attrs = this.attributes();
    const indexes = new Set<number>();
    Object.keys(attrs).forEach(key => {
      const match = key.match(/^(?:gen_ai\.tool|llm\.request\.functions)\.(\d+)\.name$/);
      if (match) indexes.add(Number(match[1]));
    });
    return [...indexes].sort((a, b) => a - b).map(index => ({
      name: String(attrs[`gen_ai.tool.${index}.name`] ?? attrs[`llm.request.functions.${index}.name`] ?? `tool-${index}`),
      description: this.asOptionalString(attrs[`gen_ai.tool.${index}.description`] ?? attrs[`llm.request.functions.${index}.description`])
    }));
  }

  get toolsDeclaredCount(): number {
    return Number(this.attr('litellm.request.tools.declared') ?? this.declaredTools.length ?? 0);
  }

  get provider(): string {
    const meta = this.metadata() as Record<string, unknown>;
    return this.asOptionalString(this.attr('gen_ai.provider.name') ?? this.attr('gen_ai.system')
      ?? meta['provider'] ?? meta['origin'])
      || this.i18n.t('overview.unknown');
  }

  get responseModel(): string {
    return this.asOptionalString(this.attr('gen_ai.response.model')) || this.selected?.model || this.i18n.t('overview.unknown');
  }

  get operation(): string {
    return this.asOptionalString(this.attr('gen_ai.operation.name')) || this.selected?.kind || this.i18n.t('overview.unknown');
  }

  get streaming(): string {
    const value = this.attr('litellm.request.streaming') ?? this.attr('llm.is_streaming');
    if (value === true || value === 'true') return this.i18n.t('overview.yes');
    if (value === false || value === 'false') return this.i18n.t('overview.no');
    return this.i18n.t('overview.unknown');
  }

  get serviceName(): string { return this.asOptionalString(this.resource()['service.name']) || this.i18n.t('overview.unknown'); }
  get environment(): string { return this.asOptionalString(this.resource()['deployment.environment']) || this.i18n.t('overview.unknown'); }
  get sdk(): string {
    const r = this.resource();
    const lang = this.asOptionalString(r['telemetry.sdk.language']);
    const version = this.asOptionalString(r['telemetry.sdk.version']);
    return [lang, version].filter(Boolean).join(' · ') || this.i18n.t('overview.unknown');
  }

  get sessionLabel(): string {
    return this.selected?.sessionId ? this.selected.sessionId : this.i18n.t('overview.noSession');
  }

  get contextLabel(): string {
    return this.conversation.length > 1
      ? this.i18n.t('overview.contextWithHistory', { n: this.conversation.length })
      : this.i18n.t('overview.contextUnknown');
  }

  load(): void {
    const seq = ++this.loadSeq;
    // Cancela o voo anterior antes de começar o novo. Sem isso, loads
    // sobrepostos (clique em Atualizar + refresh automático do SSE) deixam
    // respostas obsoletas sendo descartadas pelo loadSeq enquanto o `loading`
    // fica preso em `true` — botão travado em "Atualizando…" para sempre.
    this.loadSub?.unsubscribe();
    this.loading = true;
    const params = this.buildParams();
    const range = this.range();
    let timeseriesParams = new HttpParams();
    if (range.from) timeseriesParams = timeseriesParams.set('from', range.from).set('to', range.to).set('bucket', this.bucket());
    if (this.project) timeseriesParams = timeseriesParams.set('project', this.project);

    let pageParams = params.set('page', this.page).set('size', this.pageSize).set('technical', this.showTechnicalSpans);
    if (this.search.trim()) pageParams = pageParams.set('search', this.search.trim());

    this.loadSub = forkJoin({
      summary: this.observerService.getSummary(params),
      projects: this.observerService.getProjects(this.buildParams(false)),
      traces: this.observerService.getTracePage(pageParams)
    }).pipe(
      // Garante `loading = false` em QUALQUER término (sucesso, erro, exceção
      // no handler ou cancelamento): o botão nunca trava em "Atualizando…".
      // O detectChanges é proposital: neste setup os callbacks HTTP rodam fora
      // da zona do Angular (sem change detection automática), então o estado
      // atualizava mas a tela congelava em "Atualizando…".
      finalize(() => { if (seq === this.loadSeq) this.loading = false; this.refreshView(); })
    ).subscribe({
      next: result => {
        if (seq !== this.loadSeq) return; // descarta resposta obsoleta (filtro digitado rápido)
        this.summary = result.summary;
        this.projects = result.projects;
        this.allProjects = result.projects;
        this.spans = result.traces.content;
        this.totalElements = result.traces.totalElements;
        this.totalPages = result.traces.totalPages;
        this.lastUpdated = new Date();
        // Mostra algo de cara: se nada selecionado, abre a 1ª execução (padrão dia atual + todos).
        if (!this.selected && this.spans.length) this.selectSpan(this.spans[0]);
        else if (this.selected && !this.spans.some(s => s.id === this.selected!.id)) {
          this.selected = undefined; // filtro mudou e o selecionado sumiu: limpa em vez de mostrar dado velho
        }
        if (range.from) this.observerService.getTimeseries(timeseriesParams).subscribe({
          next: series => { this.series = series; this.refreshView(); },
          error: () => { this.series = []; this.refreshView(); }
        }); else this.series = [];
        this.refreshView();
      },
      // O `finalize` acima desliga o loading; aqui só evitamos erro unhandled.
      error: () => undefined
    });
  }

  /** Renderização explícita: cobre os casos em que o callback assíncrono roda
   *  fora da zona do Angular e o change detection automático não dispara. */
  private refreshView(): void {
    try { this.cdr.detectChanges(); } catch { /* view ainda não pronta: ignora */ }
  }

  selectProject(project: string): void { this.project = project; this.page = 0; this.load(); }
  applyFilters(): void { this.page = 0; this.load(); }
  previousPage(): void { if (this.page > 0) { this.page--; this.load(); } }
  nextPage(): void { if (this.page + 1 < this.totalPages) { this.page++; this.load(); } }

  prettyJson(value: unknown): string {
    if (value == null) return this.i18n.t('overview.notProvided');
    if (typeof value === 'string') {
      try { return JSON.stringify(JSON.parse(value), null, 2); }
      catch { return value; }
    }
    return JSON.stringify(value, null, 2);
  }

  formatNumber(value: unknown): string {
    const number = Number(value || 0);
    if (number >= 1_000_000) return `${this.trimZeros((number / 1_000_000).toFixed(3))}M`;
    if (number >= 1_000) return `${this.trimZeros((number / 1_000).toFixed(3))}K`;
    return this.intNumber(0).format(Math.round(number));
  }

  formatInteger(value: unknown): string { return this.intNumber(0).format(Math.round(Number(value || 0))); }

  /** Números com 1 casa (ex.: cache hit, latência): respeita o locale ativo. */
  formatDecimal1(value: unknown): string { return this.intNumber(1).format(Number(value || 0)); }

  private intNumber(fractionDigits: number): Intl.NumberFormat {
    return new Intl.NumberFormat(this.i18n.locale(), {
      minimumFractionDigits: fractionDigits,
      maximumFractionDigits: fractionDigits === 0 ? 0 : 2,
    });
  }

  /** Datas/horas via Intl com o locale ativo (troca junto com o idioma). */
  fmtDateTime(value: Date | string | null | undefined): string {
    if (!value) return this.i18n.t('heading.waiting');
    const date = value instanceof Date ? value : new Date(value);
    return new Intl.DateTimeFormat(this.i18n.locale(), {
      day: '2-digit', month: '2-digit', year: 'numeric',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    }).format(date);
  }

  fmtTime(value: Date | string | null | undefined): string {
    if (!value) return '';
    const date = value instanceof Date ? value : new Date(value);
    return new Intl.DateTimeFormat(this.i18n.locale(), { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(date);
  }
  barHeight(value: number): number { return Math.max(2, (value || 0) / this.maxSeries * 100); }

  timeLabel(value: string): string {
    const date = new Date(value);
    const locale = this.i18n.locale();
    return this.period === 'hour'
      ? new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit' }).format(date)
      : new Intl.DateTimeFormat(locale, { day: '2-digit', month: '2-digit' }).format(date);
  }

  roleLabel(role: string): string {
    const labels: Record<string, string> = {
      system: this.i18n.t('conversation.roleSystem'), user: this.i18n.t('conversation.roleUser'),
      assistant: this.i18n.t('conversation.roleAssistant'), tool: this.i18n.t('conversation.roleTool')
    };
    return labels[role] || role;
  }

  roleIcon(role: string): string {
    const icons: Record<string, string> = { system: 'S', user: 'U', assistant: 'A', tool: 'T' };
    return icons[role] || '?';
  }

  toolIcon(tool: ToolCall): string {
    if (tool.category === 'mcp') return 'M';
    if (tool.category === 'subagent') return 'S';
    if (tool.category === 'skill') return 'K';
    if (tool.category === 'rule') return 'R';
    if (tool.category === 'agent') return 'A';
    return 'T';
  }

  toolCategoryLabel(tool: ToolCall): string {
    if (tool.category === 'mcp') return 'MCP';
    if (tool.category === 'subagent') return 'SUBAGENT';
    if (tool.category === 'skill') return 'SKILL';
    if (tool.category === 'rule') return 'RULE';
    if (tool.category === 'agent') return 'AGENT';
    return 'TOOL';
  }

  /** Título do pai hierárquico (orquestrador → filho). Lista continua
   *  sequencial; o grafo desenha pai→filho. */
  flowParentTitle(node: FlowNode): string | undefined {
    if (!node.parentId) return undefined;
    const parent = this.flowNodes.find(n => n.id === node.parentId);
    return parent ? this.flowDisplayTitle(parent) : undefined;
  }

  private parseMessages(value: unknown): ConversationItem[] {
    const parsed = this.parseUnknown(value);
    if (!Array.isArray(parsed)) return [];
    return parsed.map((raw: any) => {
      const content = this.contentText(raw?.content);
      const toolCalls = Array.isArray(raw?.tool_calls) ? raw.tool_calls.map((call: any) => this.toToolCall(call)) : [];
      return {
        role: String(raw?.role || 'unknown'),
        content,
        toolCalls,
        toolCallId: raw?.tool_call_id ? String(raw.tool_call_id) : undefined
      };
    });
  }

  private toToolCall(call: any): ToolCall {
    const name = String(call?.function?.name || call?.name || 'tool');
    const lower = name.toLowerCase();
    const args = this.parseUnknown(call?.function?.arguments ?? call?.arguments);
    const mcp = lower.startsWith('playwright_') || lower.includes('mcp_') || lower.startsWith('mcp.');
    // Reclassifica tool genérica → semântica (vale p/ opencode, copilot, claude).
    // Espelha o backend refineToolKind para curar linhas antigas salvas como "tool".
    let category: ToolCall['category'] = mcp ? 'mcp' : 'tool';
    if (!mcp) {
      if (lower.includes('subagent') || lower === 'task' || lower.startsWith('task_')
        || lower.includes('delegate') || lower.includes('dispatch') || lower.includes('spawn')
        || lower.startsWith('agent')) category = 'subagent';
      else if (lower.includes('skill')) category = 'skill';
      else if (lower.includes('rule')) category = 'rule';
      else {
        const path = this.toolPathArg(args);
        if (path) {
          const p = path.toLowerCase();
          if (p.includes('subagent')) category = 'subagent';
          else if (p.includes('/skills/') || p.includes('skill')) category = 'skill';
          else if (p.includes('/agents/') || p.endsWith('agents.md')) category = 'agent';
          else if (p.includes('/rules/') || p.includes('rule') || p.endsWith('.mdc')) category = 'rule';
        }
        if (category === 'tool' && args && typeof args === 'object') {
          const o = args as Record<string, unknown>;
          if (o['skill'] != null || o['skillName'] != null) category = 'skill';
          else if (o['subagent'] != null || o['subagentType'] != null || o['agent'] != null) category = 'subagent';
        }
      }
    }
    return {
      id: call?.id ? String(call.id) : undefined,
      name,
      arguments: args,
      category,
      provider: lower.startsWith('playwright_') ? 'Playwright' : mcp ? 'MCP' : undefined
    };
  }

  private toolPathArg(args: unknown): string | undefined {
    if (typeof args === 'string') return args.includes('/') || args.includes('.md') ? args : undefined;
    if (args && typeof args === 'object') {
      const o = args as Record<string, unknown>;
      for (const k of ['filePath', 'path', 'file', 'target', 'url']) {
        if (typeof o[k] === 'string' && (o[k] as string).trim()) return o[k] as string;
      }
    }
    return undefined;
  }

  private contentText(content: any): string {
    if (typeof content === 'string') return content;
    if (Array.isArray(content)) return content.map(item => item?.text ?? item?.content ?? this.prettyJson(item)).join('\n');
    if (content == null) return '';
    return this.prettyJson(content);
  }

  private parseUnknown(value: unknown): any {
    if (typeof value !== 'string') return value;
    try { return JSON.parse(value); } catch { return value; }
  }

  private metadata(): Record<string, any> { return (this.selected?.metadata || {}) as Record<string, any>; }
  private attributes(): Record<string, any> { return (this.metadata()['attributes'] || {}) as Record<string, any>; }
  private resource(): Record<string, any> { return (this.metadata()['resource'] || {}) as Record<string, any>; }
  private attr(key: string): any { return this.attributes()[key]; }
  private asOptionalString(value: unknown): string | undefined { return value == null || value === '' ? undefined : String(value); }
  private trimZeros(value: string): string { return value.replace(/\.0+$/, '').replace(/(\.\d*?)0+$/, '$1'); }

  private range(): { from?: string; to: string } {
    const now = new Date();
    const from = new Date(now);
    switch (this.period) {
      case 'hour': from.setHours(from.getHours() - 1); break;
      case 'today': from.setHours(0, 0, 0, 0); break;
      case '7d': from.setDate(from.getDate() - 7); break;
      case '30d': from.setDate(from.getDate() - 30); break;
      case 'month': from.setDate(1); from.setHours(0, 0, 0, 0); break;
      default: return { to: now.toISOString() };
    }
    return { from: from.toISOString(), to: now.toISOString() };
  }

  private buildParams(includeProject = true): HttpParams {
    const range = this.range();
    let params = new HttpParams();
    if (range.from) params = params.set('from', range.from).set('to', range.to);
    if (includeProject && this.project) params = params.set('project', this.project);
    return params;
  }

  private bucket(): string {
    if (this.period === 'hour') return 'minute';
    if (this.period === '30d' || this.period === 'month') return 'day';
    return 'hour';
  }
}
