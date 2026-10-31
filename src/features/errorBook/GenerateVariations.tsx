import { RefreshCw } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useBackHandler } from '../../app/navigation';
import { useWorkspace } from '../../app/workspace';
import { AiError } from '../../ai/dashscope';
import type { Question } from '../../data/schema';
import { plainQuestion } from '../../domain/underline';
import { errorText, Page } from '../../ui/components';
import { QuestionText } from './QuestionText';
import { newKey, type PracticeItem } from './types';

const PARALLEL = 3;

type Slot =
  | { status: 'waiting' | 'running' }
  | { status: 'done'; item: PracticeItem }
  | { status: 'failed'; error: string };

/**
 * 举一反三: one new question per original, three requests at a time. Failures are
 * listed with their reason and can be retried one by one or together; practice can start with the
 * questions that worked.
 */
export function GenerateVariations({ sources, onStart, onCancel }: { sources: Question[]; onStart(items: PracticeItem[]): void; onCancel(): void }) {
  const { ai } = useWorkspace();
  const [slots, setSlots] = useState<Slot[]>(() => sources.map(() => ({ status: 'waiting' })));
  const abort = useRef(new AbortController());
  const started = useRef(new Set<number>());

  // A fresh controller per mount (React StrictMode mounts twice in development); requests cancelled by an
  // unmount go back to "waiting" below and start again after the remount.
  useEffect(() => {
    const controller = new AbortController();
    abort.current = controller;
    return () => controller.abort();
  }, []);

  // Start waiting slots while fewer than PARALLEL are running. `started` guards against starting one twice.
  useEffect(() => {
    const running = slots.filter(slot => slot.status === 'running').length;
    const waiting = slots.map((slot, index) => (slot.status === 'waiting' && !started.current.has(index) ? index : -1)).filter(index => index >= 0);
    const batch = waiting.slice(0, Math.max(0, PARALLEL - running));
    if (!batch.length) return;
    batch.forEach(index => started.current.add(index));
    setSlots(current => current.map((slot, index) => (batch.includes(index) ? { status: 'running' } : slot)));
    const signal = abort.current.signal;
    for (const index of batch) {
      const source = sources[index];
      ai.generateQuestion({ text: source.text, answer: source.answer }, signal)
        .then(generated => ({ status: 'done', item: { key: newKey(), text: generated.text, answer: generated.answer, sourceQuestionId: source.id } }) as Slot)
        .catch(error => (signal.aborted || (error instanceof AiError && error.kind === 'cancelled') ? { status: 'waiting' } : { status: 'failed', error: errorText(error) }) as Slot)
        .then(result => {
          started.current.delete(index);
          setSlots(current => current.map((slot, i) => (i === index ? result : slot)));
        });
    }
  }, [slots, sources, ai]);

  const retry = (indexes: number[]) => setSlots(current => current.map((slot, index) => (indexes.includes(index) ? { status: 'waiting' } : slot)));
  const done = slots.flatMap(slot => (slot.status === 'done' ? [slot.item] : []));
  const failed = slots.map((slot, index) => (slot.status === 'failed' ? index : -1)).filter(index => index >= 0);
  const busy = slots.some(slot => slot.status === 'waiting' || slot.status === 'running');
  const cancel = () => { abort.current.abort(); onCancel(); };
  useBackHandler(true, cancel);

  return (
    <Page title="举一反三 · 出题"
      footer={<button className="button primary big grow" disabled={busy || !done.length} onClick={() => onStart(done)}>
        {busy ? `正在出题（${done.length + failed.length} / ${sources.length}）…` : `开始练习（${done.length} 题）`}
      </button>}>
      <p className="muted small">千问为每道原题出一道考同样知识点的新题。做完后只把做错的新题保存到错题本。</p>
      {failed.length > 0 && (
        <div className="notice error row">
          <span className="grow">{failed.length} 道题没有出成功。{!busy && done.length > 0 && '可以重试，也可以先做已经出好的题。'}</span>
          <button className="button" onClick={() => retry(failed)}><RefreshCw size={18} />全部重试</button>
        </div>
      )}
      <div className="list">
        {sources.map((source, index) => {
          const slot = slots[index];
          return (
            <div key={source.id} className="list-row question-row">
              <span className="index">{index + 1}</span>
              <div className="grow stack-tight">
                <span className="muted small ellipsis">原题：{plainQuestion(source.text)}</span>
                {slot.status === 'done' && <><QuestionText text={slot.item.text} /><div className="answer-line">答案：{slot.item.answer}</div></>}
                {(slot.status === 'waiting' || slot.status === 'running') && <span className="muted">{slot.status === 'running' ? '正在出题…' : '等待中'}</span>}
                {slot.status === 'failed' && <span className="bad small">出题失败：{slot.error}</span>}
              </div>
              {slot.status === 'failed' && <button className="button" onClick={() => retry([index])}><RefreshCw size={18} />重试</button>}
            </div>
          );
        })}
      </div>
    </Page>
  );
}
