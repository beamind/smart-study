import { Capacitor } from '@capacitor/core';
import { TextToSpeech } from '@capacitor-community/text-to-speech';

/**
 * Speech output. On Android the plugin uses the system TTS engine;
 * in a PC browser it uses the Web Speech API, whose voices depend on the browser and OS.
 */
export type SpeechLanguage = 'zh-CN' | 'en-US';

export interface SpeechEngine {
  /** Anything slow (loading the voice list) happens here, so speak() starts without delay. */
  prepare(language: SpeechLanguage): Promise<void>;
  /** Resolves when the text has been spoken; rejects when interrupted or failed. */
  speak(text: string, language: SpeechLanguage, rate: number, pitch: number): Promise<void>;
  stop(): Promise<void>;
  /** Null when fine, otherwise a message to show (e.g. no voice for this language). */
  check(language: SpeechLanguage): Promise<string | null>;
}

let voiceList: SpeechSynthesisVoice[] | null = null;
const browserVoices = async (): Promise<SpeechSynthesisVoice[]> => {
  if (voiceList?.length) return voiceList;
  voiceList = await new Promise(resolve => {
    if (!('speechSynthesis' in window)) { resolve([]); return; }
    const now = window.speechSynthesis.getVoices();
    if (now.length) { resolve(now); return; }
    // Chrome and Edge load the voice list asynchronously.
    const timer = window.setTimeout(() => resolve(window.speechSynthesis.getVoices()), 1500);
    window.speechSynthesis.addEventListener('voiceschanged', () => { window.clearTimeout(timer); resolve(window.speechSynthesis.getVoices()); }, { once: true });
  });
  return voiceList!;
};

const matches = (voice: { lang: string }, language: SpeechLanguage) =>
  voice.lang.replace('_', '-').toLowerCase().startsWith(language.slice(0, 2));

/** The web plugin takes a voice index; prefer a local voice for the language over the browser default. */
function voiceIndex(language: SpeechLanguage): number | undefined {
  const voices = voiceList ?? [];
  const local = voices.findIndex(item => matches(item, language) && item.localService);
  const any = voices.findIndex(item => matches(item, language));
  return local >= 0 ? local : any >= 0 ? any : undefined;
}

/** Turns SpeechSynthesisErrorEvent and plugin errors into readable messages. */
function speechError(error: unknown): Error {
  const code = (error as { error?: string })?.error;
  if (code) return Object.assign(new Error(`语音合成出错（${code}）`), { interrupted: code === 'interrupted' || code === 'canceled' });
  return error instanceof Error ? error : new Error(String(error));
}

/**
 * Browser: Web Speech API directly. The plugin's web version cancels before every utterance,
 * which in Chrome/Edge can interrupt the utterance that was just queued.
 */
let currentUtterance: SpeechSynthesisUtterance | null = null; // keep a reference: Chrome may drop events of collected utterances
const webSpeech = {
  speak(text: string, language: SpeechLanguage, rate: number, pitch: number) {
    return new Promise<void>((resolve, reject) => {
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = language;
      utterance.rate = rate;
      utterance.pitch = pitch;
      const index = voiceIndex(language);
      if (index !== undefined) utterance.voice = voiceList![index];
      utterance.onend = () => { if (currentUtterance === utterance) currentUtterance = null; resolve(); };
      utterance.onerror = event => { if (currentUtterance === utterance) currentUtterance = null; reject(speechError(event)); };
      currentUtterance = utterance;
      window.speechSynthesis.speak(utterance);
    });
  },
  async stop() {
    if (!window.speechSynthesis.speaking && !window.speechSynthesis.pending) return;
    window.speechSynthesis.cancel();
    // Give the engine a moment; speaking right after cancel() is unreliable in Chrome/Edge.
    await new Promise(resolve => setTimeout(resolve, 50));
  },
};

export const deviceSpeech: SpeechEngine = {
  async prepare() {
    if (!Capacitor.isNativePlatform()) await browserVoices();
  },
  async speak(text, language, rate, pitch) {
    if (!Capacitor.isNativePlatform()) {
      if (!('speechSynthesis' in window)) throw new Error('这个浏览器不支持朗读');
      return webSpeech.speak(text, language, rate, pitch);
    }
    try {
      await TextToSpeech.speak({ text, lang: language, rate, pitch, volume: 1 });
    } catch (error) {
      throw speechError(error);
    }
  },
  async stop() {
    if (!Capacitor.isNativePlatform()) { if ('speechSynthesis' in window) await webSpeech.stop(); return; }
    await TextToSpeech.stop().catch(() => undefined);
  },
  async check(language) {
    const name = language === 'zh-CN' ? '中文' : '英文';
    if (Capacitor.isNativePlatform()) {
      const { supported } = await TextToSpeech.isLanguageSupported({ lang: language }).catch(() => ({ supported: false }));
      return supported ? null : `系统朗读引擎不支持${name}，请在系统设置的"文字转语音"里安装${name}语音包`;
    }
    if (!('speechSynthesis' in window)) return '这个浏览器不支持朗读，请使用 Edge 或 Chrome';
    const voices = await browserVoices();
    return voices.some(voice => matches(voice, language)) ? null
      : `这个浏览器里没有${name}语音，朗读会用默认语音或没有声音。Windows 上建议用 Edge，或在"设置 → 时间和语言 → 语音"里添加${name}语音`;
  },
};

/**
 * Speaks a word a number of times with a gap. A newer call or cancel() stops the previous one.
 * Every await is followed by a check, so an older call can never start speaking after a newer one.
 */
export class WordReader {
  private generation = 0;

  constructor(private readonly engine: SpeechEngine, private readonly wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))) {}

  /** Resolves true when all readings finished, false when superseded or cancelled. Real failures are rethrown. */
  async read(text: string, language: SpeechLanguage, options: { rate: number; pitch: number; gapMs: number; times?: number }): Promise<boolean> {
    const generation = ++this.generation;
    const current = () => generation === this.generation;
    let retried = false;
    await this.engine.stop();
    await this.engine.prepare(language);
    for (let time = 0; time < (options.times ?? 2); time++) {
      if (time > 0) await this.wait(options.gapMs);
      if (!current()) return false;
      try {
        await this.engine.speak(text, language, options.rate, options.pitch);
      } catch (error) {
        if (!current()) return false;
        // Interrupted by something other than us (e.g. another app or tab took the speech engine): try once more.
        if ((error as { interrupted?: boolean }).interrupted && !retried) { retried = true; time--; continue; }
        throw error;
      }
    }
    return current();
  }

  async cancel() {
    this.generation++;
    await this.engine.stop();
  }
}

/** Silent engine for automated tests: `?speech=fake` "speaks" for 300 ms and counts what it was asked to say. */
export const fakeSpeech: SpeechEngine = {
  prepare: async () => undefined,
  speak: () => {
    window.__fakeSpeechCount = (window.__fakeSpeechCount ?? 0) + 1;
    return new Promise(resolve => setTimeout(resolve, 300));
  },
  stop: async () => undefined,
  check: async () => null,
};

declare global {
  interface Window {
    /** How many times the fake engine started speaking (read by the Playwright tests). */
    __fakeSpeechCount?: number;
  }
}

export const speechEngine = (): SpeechEngine =>
  new URLSearchParams(window.location.search).get('speech') === 'fake' ? fakeSpeech : deviceSpeech;
