import test from 'node:test'
import assert from 'node:assert/strict'
import { formatMoney, formatPrice, moneyColumn, priceColumn, decimalMultiply, decimalSum, decimalSubtract } from '../src/format/money.ts'

test('amounts group thousands, omit padding, and never round any decimal', () => {
  assert.equal(formatMoney(1234567.89), '1,234,567.89')
  assert.equal(formatMoney('2014293.5'), '2,014,293.5')
  assert.equal(formatMoney(1000), '1,000')
  assert.equal(formatMoney(-5000), '-5,000')
  assert.equal(formatMoney(-0.4), '-0.4')
  assert.equal(formatMoney(0), '0')
  assert.equal(formatMoney('1234.567'), '1,234.567')
  assert.equal(formatMoney('1,234.5'), '1,234.5')
})

test('missing data and labels preserve their caller-defined placeholders', () => {
  assert.equal(formatMoney(null), '0')
  assert.equal(formatMoney(undefined, '-'), '-')
  assert.equal(formatMoney('', ''), '')
  assert.equal(formatPrice(null), '—')
  assert.equal(formatPrice('多种单价'), '多种单价')
  assert.equal(formatPrice('-'), '-')
})

test('prices keep sub-cent precision while grouping thousands', () => {
  assert.equal(formatPrice('0.00345'), '0.00345')
  assert.equal(formatPrice('1234.56789'), '1,234.56789')
  assert.equal(formatPrice(1234), '1,234')
  assert.equal(formatPrice('0.30000'), '0.3')
  assert.equal(formatPrice('-1234.00001'), '-1,234.00001')
})

test('table formatters use the value argument without mutating rows', () => {
  const row = { amount: 123456.78, unitPrice: '0.01234' }
  assert.equal(moneyColumn(row, {}, row.amount), '123,456.78')
  assert.equal(priceColumn(row, {}, row.unitPrice), '0.01234')
  assert.deepEqual(row, { amount: 123456.78, unitPrice: '0.01234' })
})


test('exact decimal calculations preserve third and later places without binary floating artifacts', () => {
  assert.equal(formatMoney('100.000'), '100')
  assert.equal(formatMoney('1.2346'), '1.2346')
  assert.equal(formatMoney('9007199254740993.123456789'), '9,007,199,254,740,993.123456789')
  assert.equal(formatMoney('1e-7'), '0.0000001')
  assert.equal(decimalMultiply('0.123456', '3.25'), '0.401232')
  assert.equal(decimalSum(['0.1', '0.2']), '0.3')
  assert.equal(decimalSubtract('0.3', '0.1'), '0.2')
  assert.equal(decimalSum(['1000.12345678', '0.00000001']), '1000.12345679')
})
