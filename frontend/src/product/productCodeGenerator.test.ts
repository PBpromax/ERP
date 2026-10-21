import { generateProductIdentity, isProductCodeDuplicated } from './productCodeGenerator'

interface ProductGeneratorCase {
  name: string
  categoryCode: string
  tagValues: Record<string, string | boolean>
  expectedCode: string
}

export const productCodeGeneratorCases: ProductGeneratorCase[] = [
  { name: 'A 类编码生成', categoryCode: 'A', tagValues: { color_code: '001', length_code: '0300' }, expectedCode: 'RA-001-0300' },
  { name: 'B 类编码生成', categoryCode: 'B', tagValues: { color_code: '001', length_code: '0550', combine_code: '01', tube_spec: '10*7.5*8' }, expectedCode: 'RB-001-0550-01-10*7.5*8' },
  { name: 'C 类普通编码', categoryCode: 'C', tagValues: { color_code: '001', length_code: '0285', width_code: '22', head_code: '01' }, expectedCode: 'RC-001-0285-22-01' },
  { name: 'C 类带提手把', categoryCode: 'C', tagValues: { color_code: '002', length_code: '0300', width_code: '25', head_code: '01', has_handle: true }, expectedCode: 'RC-002-0300-25-01-T' },
  { name: 'C 类印花款', categoryCode: 'C', tagValues: { color_code: '007', length_code: '0400', width_code: '25', head_code: '01', is_print: true, print_label: 'PATTERN_A' }, expectedCode: 'RC-007-0400-25-01-Y-PATTERN_A' },
  { name: 'E 类带色号', categoryCode: 'E', tagValues: { color_code: '004', length_code: '0440', diameter: '5.5', handle_code: '05', color_no: '87911' }, expectedCode: 'RE-004-0440-5.5-05-87911' },
  { name: 'F 类编码', categoryCode: 'F', tagValues: { color_code: '001', size_code: '0370', lock_spec: '5.0' }, expectedCode: 'RF-001-0370-5.0' },
  { name: 'Q 类新增默认编码', categoryCode: 'Q', tagValues: { color_code: '001', model: 'ABC' }, expectedCode: 'RQ-001-010-ABC' },
  { name: 'Q 类历史兼容编码', categoryCode: 'Q', tagValues: { color_code: '001', model: 'ABC', legacy_code_mode: true }, expectedCode: 'RP-001-010-ABC' }
]

export function runProductCodeGeneratorTests() {
  productCodeGeneratorCases.forEach(item => {
    const result = generateProductIdentity(item.categoryCode, item.tagValues)
    if (!result.ready || result.productCode !== item.expectedCode) {
      throw new Error(`${item.name}: expected ${item.expectedCode}, got ${result.productCode}`)
    }
  })

  if (!isProductCodeDuplicated('RA-001-0300', [{ id: 1, code: 'RA-001-0300' }])) {
    throw new Error('重复产品编码不允许保存: expected duplicate to be true')
  }
  if (isProductCodeDuplicated('RA-001-0300', [{ id: 1, code: 'RA-001-0300' }], 1)) {
    throw new Error('重复产品编码不允许保存: current editing row should be ignored')
  }
}
