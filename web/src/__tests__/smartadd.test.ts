// Taskly Web — Smart Add Parser Tests
import { describe, it, expect } from 'vitest';
import { parseSmartAdd } from '../features/smartadd/parser';

describe('Smart Add Parser', () => {
  it('parses simple task name without tokens', () => {
    const result = parseSmartAdd('Buy milk');
    expect(result.name).toBe('Buy milk');
    expect(result.tokens).toHaveLength(0);
  });

  it('parses due date token (^)', () => {
    const result = parseSmartAdd('Buy milk ^tomorrow');
    expect(result.name).toBe('Buy milk');
    expect(result.dueDate).toBeDefined();
    expect(result.tokens).toHaveLength(1);
    expect(result.tokens[0].type).toBe('dueDate');
  });

  it('parses due date token (^) with Russian', () => {
    const result = parseSmartAdd('Купить молоко ^завтра');
    expect(result.name).toBe('Купить молоко');
    expect(result.dueDate).toBeDefined();
    expect(result.tokens).toHaveLength(1);
    expect(result.tokens[0].type).toBe('dueDate');
  });

  it('parses priority token (!)', () => {
    const result = parseSmartAdd('Important task !1');
    expect(result.name).toBe('Important task');
    expect(result.priority).toBe(1);
    expect(result.tokens).toHaveLength(1);
    expect(result.tokens[0].type).toBe('priority');
  });

  it('parses priority 2 and 3', () => {
    const r2 = parseSmartAdd('Medium !2');
    expect(r2.priority).toBe(2);

    const r3 = parseSmartAdd('Low !3');
    expect(r3.priority).toBe(3);
  });

  it('parses list token (#)', () => {
    const result = parseSmartAdd('Task #Work');
    expect(result.name).toBe('Task');
    expect(result.listName).toBe('Work');
    expect(result.tokens).toHaveLength(1);
  });

  it('parses repeat token (*)', () => {
    const result = parseSmartAdd('Weekly task *every week');
    expect(result.name).toBe('Weekly task');
    expect(result.repeatRule).toBeDefined();
    expect(result.repeatMode).toBe('every');
  });

  it('parses estimate token (=)', () => {
    const result = parseSmartAdd('Code review =30 mins');
    expect(result.name).toBe('Code review');
    expect(result.estimateMin).toBe(30);
  });

  it('parses note token (//)', () => {
    const result = parseSmartAdd('Task // remember to check');
    expect(result.name).toBe('Task');
    expect(result.note).toBe('remember to check');
  });

  it('parses start date token (~)', () => {
    const result = parseSmartAdd('Plan ~monday');
    expect(result.name).toBe('Plan');
    expect(result.startDate).toBeDefined();
  });

  it('parses location token (@)', () => {
    const result = parseSmartAdd('Meet @office');
    expect(result.location).toBe('office');
  });

  it('parses multiple tokens combined', () => {
    const result = parseSmartAdd('Submit report ^пт #Работа !1');
    expect(result.name).toBe('Submit report');
    expect(result.dueDate).toBeDefined();
    expect(result.listName).toBe('Работа');
    expect(result.priority).toBe(1);
    expect(result.tokens).toHaveLength(3);
  });

  it('handles "через N дней" date syntax', () => {
    const result = parseSmartAdd('Task ^через 3 дня');
    expect(result.name).toBe('Task');
    expect(result.dueDate).toBeDefined();
  });

  it('handles "in N days" date syntax', () => {
    const result = parseSmartAdd('Task ^in 5 days');
    expect(result.name).toBe('Task');
    expect(result.dueDate).toBeDefined();
  });

  it('parses after repeat mode (*after)', () => {
    const result = parseSmartAdd('Task *after 2 weeks');
    expect(result.name).toBe('Task');
    expect(result.repeatRule).toBeDefined();
    expect(result.repeatMode).toBe('after');
  });

  it('handles empty input', () => {
    const result = parseSmartAdd('');
    expect(result.name).toBe('');
    expect(result.tokens).toHaveLength(0);
  });

  it('handles input with only tokens', () => {
    const result = parseSmartAdd('^завтра !1 #Work');
    expect(result.name).toBe('');
    expect(result.dueDate).toBeDefined();
    expect(result.priority).toBe(1);
    expect(result.listName).toBe('Work');
  });

  it('preserves token positions', () => {
    const result = parseSmartAdd('Buy milk ^tomorrow !2');
    expect(result.tokens[0].start).toBe(9); // ^tomorrow starts at index 9
    expect(result.tokens[0].end).toBe(18);  // ends at index 18
  });
});