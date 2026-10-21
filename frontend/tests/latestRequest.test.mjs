import test from 'node:test'
import assert from 'node:assert/strict'
import { createLatestRequest } from '../src/api/latestRequest.ts'

const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('rapid filter changes abort obsolete requests and only accept newest data', async () => {
  const latest = createLatestRequest()
  const first = deferred(), second = deferred()
  let firstSignal
  const oldRead = latest.run(signal => { firstSignal = signal; return first.promise })
  const newRead = latest.run(() => second.promise)
  assert.equal(firstSignal.aborted, true)
  second.resolve({ page: 1, keyword: 'new', items: [2] })
  assert.deepEqual(await newRead, { page: 1, keyword: 'new', items: [2] })
  // Also cover a transport that completes despite abort.
  first.resolve({ page: 3, keyword: 'old', items: [1] })
  assert.equal(await oldRead, undefined)
})

test('leaving a page cancels its request and ignores a late response', async () => {
  const latest = createLatestRequest(), pending = deferred()
  let signal
  const read = latest.run(value => { signal = value; return pending.promise })
  latest.cancel()
  assert.equal(signal.aborted, true)
  pending.resolve(['old data'])
  assert.equal(await read, undefined)
})

test('expected abort failures are silent but current network failures still propagate', async () => {
  const latest = createLatestRequest(), pending = deferred()
  const oldRead = latest.run(() => pending.promise)
  latest.cancel()
  pending.reject(new Error('request aborted'))
  assert.equal(await oldRead, undefined)
  await assert.rejects(latest.run(async () => { throw new Error('network down') }), /network down/)
  assert.deepEqual(await latest.run(async () => [100]), [100])
})
