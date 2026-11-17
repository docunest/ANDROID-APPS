// Small polyfills for older Samsung Tizen web engines (Tizen 5.5/6 ≈ Chromium 69–76).
/* eslint-disable no-extend-native */
const AP = Array.prototype as any;
if (!AP.at) {
  AP.at = function (this: unknown[], i: number) {
    i = Math.trunc(i) || 0;
    if (i < 0) i += this.length;
    return this[i];
  };
}
if (!AP.flat) {
  AP.flat = function (this: unknown[], d = 1): unknown[] {
    return d > 0 ? this.reduce<unknown[]>((a, v) => a.concat(Array.isArray(v) ? (v as any).flat(d - 1) : v), []) : this.slice();
  };
}
if (!AP.flatMap) {
  AP.flatMap = function (this: unknown[], fn: (v: unknown, i: number, a: unknown[]) => unknown, t?: unknown) {
    return this.map(fn, t).flat();
  };
}
if (!(String.prototype as any).replaceAll) {
  (String.prototype as any).replaceAll = function (this: string, s: string | RegExp, r: string) {
    return typeof s === 'string' ? this.split(s).join(r) : this.replace(s, r);
  };
}
if (!(Object as any).hasOwn) {
  (Object as any).hasOwn = (o: object, k: PropertyKey) => Object.prototype.hasOwnProperty.call(o, k);
}
if (!(Object as any).fromEntries) {
  (Object as any).fromEntries = (it: Iterable<[PropertyKey, unknown]>) => {
    const o: Record<PropertyKey, unknown> = {};
    for (const [k, v] of it) o[k as string] = v;
    return o;
  };
}
if (typeof (globalThis as any).queueMicrotask !== 'function') {
  (globalThis as any).queueMicrotask = (cb: () => void) => Promise.resolve().then(cb);
}
if (typeof (Promise as any).allSettled !== 'function') {
  (Promise as any).allSettled = (ps: Promise<unknown>[]) =>
    Promise.all(ps.map((p) => Promise.resolve(p).then((value) => ({ status: 'fulfilled', value }), (reason) => ({ status: 'rejected', reason }))));
}
export {};
