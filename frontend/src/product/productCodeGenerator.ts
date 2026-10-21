import { categoryByCode, type ProductTagValues } from './productCategoryConfig'

export interface GeneratedProductIdentity {
  ready: boolean
  productCode: string
  productName: string
  spec: string
  missingFields: string[]
}

const INCOMPLETE_TEXT = '请完善必填字段'
const PRODUCT_NAME_OVERRIDES: Record<string, string> = {
  'RB-003-0315-04-8*5.3-9': 'PP编织绳+提管-黑色两头打结+8*5.3-9',
  'RC-007-0400-25-01-Y-PATTERN_A': 'PP扁提绳-25印花款-大头-黑白-PATTERN_A',
  'RC-007-0400-25-01-Y-PATTERN_B': 'PP扁提绳-25印花款-大头-橙白-PATTERN_B',
  'RC-007-0400-25-01-Y-PATTERN_C': 'PP扁提绳-25印花款-大头-黑白-PATTERN_C',
  'RC-010-0400-25-01-15831': 'PP扁提绳-25红色-大头-色号-15831',
  'RC-011-0390-25-01': 'PP扁提绳-25灰色-大头-灰绳白色头',
  'RE-001-0440-5.0-01': 'PE4股扭花提绳+提手柄-黑色5.0-手柄一',
  'RE-001-0470-5.0-03': 'PE4股扭花提绳+提手柄-黑色5.0-手柄三-小柄',
  'RE-001-0470-5.0-04': 'PE4股扭花提绳+提手柄-黑色5.0-手柄三-大柄',
  'RE-002-0440-5.0-01': 'PE4股扭花提绳+提手柄-白色5.0-手柄一',
  'RE-002-0470-5.0-03': 'PE4股扭花提绳+提手柄-白色5.0-手柄三-小柄',
  'RE-002-0470-5.0-04': 'PE4股扭花提绳+提手柄-白色5.0-手柄三-大柄',
  'RE-004-0440-5.5-05-87911': 'PE4股扭花提绳+提手柄-灰色5.5-手柄四-色号-87911',
  'RE-005-0430-5.0-02-88070': 'PE4股扭花提绳+提手柄-黑绳灰扣件5.0-手柄二-色粉号-88070'
}

export function generateProductIdentity(categoryCode: string, tagValues: ProductTagValues = {}): GeneratedProductIdentity {
  const category = categoryByCode(categoryCode)
  if (!category) {
    return incomplete(['产品大类'])
  }

  const values = withFixedValues(categoryCode, trimValues(tagValues))
  const missingFields = requiredMissing(categoryCode, values)
  if (missingFields.length) {
    return incomplete(missingFields)
  }

  const color = label(categoryCode, 'color_code', values.color_code)
  const length = text(values.length_code)
  const width = text(values.width_code)
  const head = label(categoryCode, 'head_code', values.head_code)

  switch (categoryCode) {
    case 'A':
      return ready(`RA-${values.color_code}-${length}`, `PP编织绳-${color}`, length)
    case 'B': {
      const combine = label(categoryCode, 'combine_code', values.combine_code)
      const tubeSpec = text(values.tube_spec)
      return ready(`RB-${values.color_code}-${length}-${values.combine_code}-${tubeSpec}`, `PP编织绳+提管-${color}${combine}`, `${length}-${combine}-${tubeSpec}`)
    }
    case 'C': {
      const parts = ['RC', text(values.color_code), length, width, text(values.head_code)]
      const specParts = [length, width, head]
      if (values.has_handle === true) {
        parts.push('T')
        specParts.push('带提手把')
      }
      if (values.is_print === true) {
        parts.push('Y', text(values.print_label))
        specParts.push(`印花:${values.print_label}`)
      }
      if (text(values.color_no)) {
        parts.push(text(values.color_no))
        specParts.push(`色号:${values.color_no}`)
      }
      return ready(parts.join('-'), `PP扁提绳-${width}${color}-${head}`, specParts.join('-'))
    }
    case 'D':
      return ready(`RD-${values.color_code}-${length}-${values.diameter}-${values.head_code}`, `PE4股扭花提绳-双头扣-${color}`, `${length}-${values.diameter}-头${values.head_code}`)
    case 'E': {
      const handle = label(categoryCode, 'handle_code', values.handle_code)
      const parts = ['RE', text(values.color_code), length, text(values.diameter), text(values.handle_code)]
      const specParts = [length, text(values.diameter), handle]
      if (text(values.color_no)) {
        parts.push(text(values.color_no))
        specParts.push(`色号:${values.color_no}`)
      }
      return ready(parts.join('-'), `PE4股扭花提绳+提手柄-${color}-${handle}`, specParts.join('-'))
    }
    case 'F':
      return ready(`RF-${values.color_code}-${values.size_code}-${values.lock_spec}`, `PE锁扣-${color}${values.lock_spec}`, `${values.size_code}-${values.lock_spec}`)
    case 'G':
      return ready(`RG-${values.color_code}-001`, `PP提绳管-${color}`, '001')
    case 'H':
      return ready(`RH-001-002-${values.pipe_spec}`, `塑料封底-${values.pipe_spec}管`, `${values.pipe_spec}管`)
    case 'I':
      return ready(`RI-001-003-${values.pipe_spec}`, `塑料护套-${values.pipe_spec}管`, `${values.pipe_spec}管`)
    case 'J':
      return ready(`RJ-001-004-${values.roller_spec}`, `滚轮-${values.roller_spec}`, text(values.roller_spec))
    case 'K':
      return ready('RK-001-005', '地插', '005')
    case 'L':
      return ready(`RL-${values.color_code}-006`, `连体饵料球-${color}`, '006')
    case 'M':
      return ready(`RM-${values.color_code}-007`, `分离饵料球-${color}`, '007')
    case 'N':
      return ready(`RN-001-008-${values.bucket_spec}`, `塑料桶-${values.bucket_spec}`, text(values.bucket_spec))
    case 'P':
      return ready('RP-001-009', '橡胶圈', '009')
    case 'Q': {
      const prefix = values.legacy_code_mode === true ? 'RP' : 'RQ'
      return ready(`${prefix}-${values.color_code}-010-${values.model}`, `EVA垫板-${color}`, text(values.model))
    }
    case 'R':
    case 'S':
    case 'T':
    case 'U':
    case 'V':
    case 'Y': {
      const productCode = text(values.product_code)
      return ready(productCode, category.name, inferGenericSpec(productCode))
    }
    default:
      return incomplete(['产品大类'])
  }
}

export function withFixedValues(categoryCode: string, tagValues: ProductTagValues = {}) {
  const category = categoryByCode(categoryCode)
  const values: ProductTagValues = { ...tagValues }
  category?.fields.forEach(field => {
    if (field.type === 'fixed') {
      values[field.key] = field.fixedValue ?? ''
    }
  })
  return values
}

export function isProductCodeDuplicated(
  productCode: string,
  products: Array<{ id?: string | number; code?: string }>,
  currentId?: string | number
) {
  const code = productCode.trim()
  return products.some(product => product.code === code && String(product.id ?? '') !== String(currentId ?? ''))
}

function requiredMissing(categoryCode: string, values: ProductTagValues) {
  const category = categoryByCode(categoryCode)
  if (!category) return ['产品大类']
  const missing = category.fields
    .filter(field => field.showWhen ? field.showWhen(values) : true)
    .filter(field => field.required && !text(values[field.key]))
    .map(field => field.label)

  if (categoryCode === 'C' && values.is_print === true && !text(values.print_label) && !missing.includes('印花图案 / 客户款')) {
    missing.push('印花图案 / 客户款')
  }
  if (categoryCode === 'Q' && !text(values.model) && !missing.includes('具体型号')) {
    missing.push('具体型号')
  }
  return missing
}

function label(categoryCode: string, key: string, value: unknown) {
  const option = categoryByCode(categoryCode)
    ?.fields.find(field => field.key === key)
    ?.options?.find(item => item.value === value)
  return option?.label || text(value)
}

function trimValues(values: ProductTagValues) {
  return Object.fromEntries(Object.entries(values).map(([key, value]) => [
    key,
    typeof value === 'string' ? value.trim() : value
  ]))
}

function text(value: unknown) {
  return typeof value === 'string' ? value.trim() : value == null ? '' : String(value)
}

function ready(productCode: string, productName: string, spec: string): GeneratedProductIdentity {
  return { ready: true, productCode, productName: PRODUCT_NAME_OVERRIDES[productCode] || productName, spec, missingFields: [] }
}

function incomplete(missingFields: string[]): GeneratedProductIdentity {
  return { ready: false, productCode: INCOMPLETE_TEXT, productName: '', spec: '', missingFields }
}

function inferGenericSpec(productCode: string) {
  const parts = productCode.split('-').filter(Boolean)
  return parts.length > 1 ? parts.slice(1).join('-') : productCode
}
