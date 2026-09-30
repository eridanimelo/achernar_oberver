import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, timeout } from 'rxjs';
import { Span, SpanPage } from '../model/span.model';

const API_TIMEOUT_MS = 15000;

@Injectable({ providedIn: 'root' })
export class ObserverService {
  constructor(private readonly http: HttpClient) {}

  getSummary(params: HttpParams): Observable<Record<string, number>> {
    return this.http.get<Record<string, number>>('/api/summary', { params }).pipe(timeout(API_TIMEOUT_MS));
  }

  getProjects(params: HttpParams = new HttpParams()): Observable<any[]> {
    return this.http.get<any[]>('/api/projects', { params }).pipe(timeout(API_TIMEOUT_MS));
  }

  getTracePage(params: HttpParams): Observable<SpanPage> {
    return this.http.get<SpanPage>('/api/traces/page', { params }).pipe(timeout(API_TIMEOUT_MS));
  }

  getSpan(id: string): Observable<Span> {
    return this.http.get<Span>(`/api/spans/${encodeURIComponent(id)}`).pipe(timeout(API_TIMEOUT_MS));
  }

  getSessionTraces(sessionId: string): Observable<Span[]> {
    return this.http.get<Span[]>(`/api/sessions/${encodeURIComponent(sessionId)}/traces`).pipe(timeout(API_TIMEOUT_MS));
  }

  clearTraces(): Observable<void> { return this.http.delete<void>('/api/traces'); }

  stream(onEvent: () => void): EventSource {
    const source = new EventSource('/api/stream');
    source.addEventListener('trace', () => onEvent());
    return source;
  }

  getTimeseries(params: HttpParams): Observable<any[]> {
    return this.http.get<any[]>('/api/timeseries', { params }).pipe(timeout(API_TIMEOUT_MS));
  }

  getVersion(): Observable<{ version: string; name: string }> {
    return this.http.get<{ version: string; name: string }>('/api/version').pipe(timeout(API_TIMEOUT_MS));
  }
}
