import { describe, expect, it } from 'vitest';
import { splitWords } from './types';

describe('splitWords', () => {
  it('splits Chinese words on spaces too, but keeps English phrases whole', () => {
    expect(splitWords('春天 温暖　柳树\n燕子，小草、花朵', 'chinese')).toEqual(['春天', '温暖', '柳树', '燕子', '小草', '花朵']);
    expect(splitWords('take care\napple, banana；look after ', 'english')).toEqual(['take care', 'apple', 'banana', 'look after']);
  });
});
