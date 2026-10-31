import { Fragment } from 'react';
import { splitUnderlines } from '../../domain/underline';

/** Renders __x__ marks as underlined letters and keeps line breaks. */
export function QuestionText({ text, className = '' }: { text: string; className?: string }) {
  return (
    <div className={`question-text ${className}`}>
      {text.split('\n').map((line, lineIndex) => (
        <Fragment key={lineIndex}>
          {lineIndex > 0 && <br />}
          {splitUnderlines(line).map((part, index) => part.underline ? <u key={index}>{part.text}</u> : <Fragment key={index}>{part.text}</Fragment>)}
        </Fragment>
      ))}
    </div>
  );
}
