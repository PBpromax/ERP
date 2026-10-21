export type ProductStatus = 'enabled' | 'disabled'
export type ProductTagValue = string | boolean
export type ProductTagValues = Record<string, ProductTagValue>

export interface ProductTagOption {
  value: string
  label: string
}

export interface ProductTagField {
  key: string
  label: string
  type: 'select' | 'input' | 'switch' | 'fixed'
  required?: boolean
  options?: ProductTagOption[]
  suggestions?: string[]
  fixedValue?: string | boolean
  showWhen?: (values: ProductTagValues) => boolean
}

export interface ProductCategory {
  code: string
  name: string
  fields: ProductTagField[]
}

export interface Product {
  id?: number | string
  code: string
  name: string
  categoryCode: string
  categoryName: string
  spec: string
  unit: string
  defaultPrice: number
  costPrice: number
  safetyStock: number
  enabled?: boolean
  status: ProductStatus
  remark?: string
  aliases?: string[]
  aliasesText?: string
  tagValues?: string | ProductTagValues
  createdAt?: string
  updatedAt?: string
}

export const unitOptions = ['条', '根', '个', '套', '米', '件', '桶'].map(value => ({ value, label: value }))

export const productCategories: ProductCategory[] = [
  { code: 'A', name: 'PP编织绳', fields: [
    select('color_code', '颜色', [['001', '蓝色'], ['002', '白色'], ['003', '黑色']]),
    input('length_code', '绳长 / 规格代码', true, ['0300', '0500', '0550', '0560', '0600', '0640', '0385', '0900', '01000', '0350'])
  ] },
  { code: 'B', name: 'PP编织绳+提管', fields: [
    select('color_code', '颜色', [['001', '蓝色'], ['002', '白色'], ['003', '黑色']]),
    input('length_code', '绳长 / 规格代码', true, ['0300', '0550', '0600', '0640', '0700', '0320', '0370', '0420', '0385', '0500', '0900', '1000', '0350', '0315']),
    select('combine_code', '组合方式', [['01', '单管单绳'], ['02', '单管双绳'], ['03', '单环形'], ['04', '两头打结']]),
    input('tube_spec', '提管规格', true, ['10*7.5*8', '10*7.5*10', '8*5.3-9'])
  ] },
  { code: 'C', name: 'PP扁提绳', fields: [
    select('color_code', '颜色 / 款式', [['001', '白色'], ['002', '黑色'], ['003', '灰蓝色'], ['004', '灰绿色'], ['005', '绿色'], ['006', '黑黄色'], ['007', '印花款'], ['008', '丈青色'], ['009', '浅蓝色'], ['010', '红色'], ['011', '灰色']]),
    input('length_code', '长度 / 规格代码', true, ['0240', '0285', '0295', '0300', '0315', '0320', '0340', '0350', '0370', '0380', '0390', '0400', '0405', '0420', '0440', '0460', '0540', '1260']),
    select('width_code', '规格代码', [['22', '22规格'], ['25', '25规格']]),
    select('head_code', '头部规格', [['01', '大头'], ['02', '中头'], ['03', '小头']]),
    sw('has_handle', '是否带提手把'),
    sw('is_print', '是否印花款'),
    select('print_label', '印花图案 / 客户款', [['PATTERN_A', 'PATTERN_A'], ['PATTERN_B', 'PATTERN_B'], ['PATTERN_C', 'PATTERN_C']], values => values.is_print === true),
    input('color_no', '色号 / 色粉号', false, ['310821', '310860', '38770', '15831'])
  ] },
  { code: 'D', name: 'PE4股扭花提绳-双头扣', fields: [
    select('color_code', '颜色', [['001', '黑色'], ['002', '白色']]), input('length_code', '绳长 / 规格代码', true, ['0300', '0310', '0330', '0350', '0400', '0415', '0430', '0440', '0450', '0460', '0470', '0600']), select('diameter', '绳径 / 粗细规格', [['4.3', '4.3'], ['4.5', '4.5'], ['5.0', '5.0'], ['5.5', '5.5']]), select('head_code', '头部规格 / 头号', [['11', '11'], ['14', '14'], ['19', '19']])
  ] },
  { code: 'E', name: 'PE4股扭花提绳+提手柄', fields: [
    select('color_code', '颜色', [['001', '黑色'], ['002', '白色'], ['003', '红色'], ['004', '灰色'], ['005', '黑绳灰扣件']]), input('length_code', '绳长 / 规格代码', true, ['0430', '0440', '0460', '0470', '0600', '0700']), select('diameter', '绳径 / 粗细规格', [['5.0', '5.0'], ['5.5', '5.5']]), select('handle_code', '手柄款式', [['01', '手柄一'], ['02', '手柄二'], ['03', '手柄三-小柄'], ['04', '手柄三-大柄'], ['05', '手柄四']]), input('color_no', '色号 / 色粉号', false, ['15831', '87911', '88070'])
  ] },
  { code: 'F', name: 'PE锁扣', fields: [select('color_code', '颜色', [['001', '黑色'], ['002', '白色']]), input('size_code', '规格 / 尺寸代码', true, ['0370', '0410', '0460', '0470', '0500', '0600']), select('lock_spec', '锁扣规格', [['5.0', '5.0'], ['6.0', '6.0']])] },
  { code: 'G', name: 'PP提绳管', fields: [select('color_code', '颜色', [['001', '黑色'], ['002', '白色']]), fixed('item_code', '固定段', '001')] },
  { code: 'H', name: '塑料封底', fields: [fixed('color_code', '固定段', '001'), fixed('item_code', '固定段', '002'), select('pipe_spec', '管径规格', [['15', '15'], ['19', '19']])] },
  { code: 'I', name: '塑料护套', fields: [fixed('color_code', '固定段', '001'), fixed('item_code', '固定段', '003'), select('pipe_spec', '管径规格', [['15', '15'], ['19', '19']])] },
  { code: 'J', name: '滚轮', fields: [fixed('color_code', '固定段', '001'), fixed('item_code', '固定段', '004'), select('roller_spec', '滚轮规格', [['7.6', '7.6'], ['8.3', '8.3']])] },
  { code: 'K', name: '地插', fields: [fixed('color_code', '固定段', '001'), fixed('item_code', '固定段', '005')] },
  { code: 'L', name: '连体饵料球', fields: [select('color_code', '颜色', [['001', '黑色'], ['002', '白色']]), fixed('item_code', '固定段', '006')] },
  { code: 'M', name: '分离饵料球', fields: [select('color_code', '颜色', [['001', '黑色'], ['002', '白色']]), fixed('item_code', '固定段', '007')] },
  { code: 'N', name: '塑料桶', fields: [fixed('color_code', '固定段', '001'), fixed('item_code', '固定段', '008'), select('bucket_spec', '桶规格 / 容量型号', [['120', '120'], ['160', '160'], ['300', '300']])] },
  { code: 'P', name: '橡胶圈', fields: [fixed('color_code', '固定段', '001'), fixed('item_code', '固定段', '009')] },
  { code: 'Q', name: 'EVA垫板', fields: [select('color_code', '颜色', [['001', '黑色'], ['002', '白色']]), fixed('item_code', '固定段', '010'), input('model', '具体型号', true), sw('legacy_code_mode', '使用历史编码模式')] },
  { code: 'R', name: 'H型提手', fields: [input('product_code', '物料编号', true)] },
  { code: 'S', name: '松紧带子母扣', fields: [input('product_code', '物料编号', true)] },
  { code: 'T', name: '稀释剂', fields: [input('product_code', '物料编号', true)] },
  { code: 'U', name: '接近开关（创为包边机用）', fields: [input('product_code', '物料编号', true)] },
  { code: 'V', name: '托盘', fields: [input('product_code', '物料编号', true)] },
  { code: 'Y', name: '原材料', fields: [input('product_code', '物料编号', true)] }
]

export function categoryByCode(code?: string) {
  return productCategories.find(category => category.code === code)
}

function select(key: string, label: string, entries: string[][], showWhen?: ProductTagField['showWhen']): ProductTagField {
  return { key, label, type: 'select', required: true, options: entries.map(([value, text]) => ({ value, label: text })), showWhen }
}

function input(key: string, label: string, required = true, suggestions: string[] = []): ProductTagField {
  return { key, label, type: 'input', required, suggestions }
}

function sw(key: string, label: string): ProductTagField {
  return { key, label, type: 'switch' }
}

function fixed(key: string, label: string, fixedValue: string | boolean): ProductTagField {
  return { key, label, type: 'fixed', fixedValue }
}
