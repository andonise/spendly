import { SseParser } from './sse-fetch';

describe('SseParser', () => {
  it('parses events split across chunks, ignoring keep-alive comments', () => {
    const parser = new SseParser();
    const out = [
      ...parser.push(': keep-alive\n\nevent: sta'),
      ...parser.push('tus\nid: 7\ndata: {"status":"PARSED"}\n'),
      ...parser.push('\r\ndata: first\ndata: second\n\n'),
    ];
    expect(out).toEqual([
      { event: 'status', id: '7', data: '{"status":"PARSED"}' },
      { event: 'message', id: '7', data: 'first\nsecond' },
    ]);
  });

  it('flushes a final event without a trailing blank line', () => {
    const parser = new SseParser();
    expect(parser.push('data: token')).toEqual([]);
    expect(parser.flush()).toEqual([{ event: 'message', id: undefined, data: 'token' }]);
  });
});
