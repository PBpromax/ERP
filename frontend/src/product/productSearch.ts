export function productAliases(product: any): string[] {
  if (Array.isArray(product?.aliases)) {
    return product.aliases.map((value: any) => String(value || '').trim()).filter(Boolean)
  }
  return []
}

export function productMatches(product: any, query: string): boolean {
  const key = normalize(query)
  if (!key) return true
  return [product?.code, product?.name, product?.spec, ...productAliases(product)]
    .some(value => normalize(value).includes(key))
}

export function productCodeMatches(product: any, query: string): boolean {
  const key = normalize(query)
  return Boolean(key) && normalize(product?.code).includes(key)
}

export function productNameMatches(product: any, query: string): boolean {
  const key = normalize(query)
  if (!key) return false
  return normalize(product?.name).includes(key)
    || productAliases(product).some(alias => normalize(alias).includes(key))
}

export function matchingProductAliases(product: any, query: string): string[] {
  const key = normalize(query)
  if (!key) return []
  return productAliases(product).filter(alias => normalize(alias).includes(key))
}

export function compareProductCodeMatches(left: any, right: any, query: string): number {
  const key = normalize(query)
  const rank = (product: any) => textMatchRank(normalize(product?.code), key)
  return rank(left) - rank(right) || compareText(left?.code, right?.code)
}

export function compareProductNameMatches(left: any, right: any, query: string): number {
  const key = normalize(query)
  const rank = (product: any) => {
    const aliasRanks = productAliases(product)
      .map(alias => textMatchRank(normalize(alias), key))
      .filter(value => value < 3)
    // Every alias match is listed before every official-name match.
    if (aliasRanks.length) return Math.min(...aliasRanks)
    const nameRank = textMatchRank(normalize(product?.name), key)
    return nameRank < 3 ? 3 + nameRank : 6
  }
  return rank(left) - rank(right) || compareText(left?.name, right?.name) || compareText(left?.code, right?.code)
}

export function productAliasText(product: any): string {
  return productAliases(product).join('、')
}

export function productSuggestionSub(product: any): string {
  return [
    product?.code ? `编号：${product.code}` : '',
    product?.name ? `名称：${product.name}` : '',
    product?.spec ? `规格：${product.spec}` : '',
    productAliases(product).length ? `别称：${productAliasText(product)}` : ''
  ].filter(Boolean).join('　')
}

export function productCodeSuggestionSub(product: any): string {
  return [
    product?.name ? `名称：${product.name}` : '',
    product?.spec ? `规格：${product.spec}` : ''
  ].filter(Boolean).join('　')
}

export function productNameSuggestionSub(product: any, query: string): string {
  const aliases = matchingProductAliases(product, query)
  return [
    product?.code ? `编号：${product.code}` : '编号：暂未编号',
    product?.spec ? `规格：${product.spec}` : '',
    aliases.length ? `匹配别称：${aliases.join('、')}` : ''
  ].filter(Boolean).join('　')
}

export function findExactProduct(products: any[], codeValue: any, nameValue: any): any | undefined {
  const code = normalize(codeValue)
  const name = normalize(nameValue)
  if (!code && !name) return undefined
  const codeMatches = code ? products.filter(product => normalize(product?.code) === code) : products
  const nameMatches = name
    ? products.filter(product => normalize(product?.name) === name
      || productAliases(product).some(alias => normalize(alias) === name))
    : products
  const matches = codeMatches.filter(product => nameMatches.includes(product))
  return matches.length === 1 ? matches[0] : undefined
}

function normalize(value: any): string {
  return String(value || '').trim().toLocaleLowerCase()
}

function textMatchRank(value: string, query: string): number {
  if (value === query) return 0
  if (value.startsWith(query)) return 1
  return value.includes(query) ? 2 : 3
}

function compareText(left: any, right: any): number {
  return String(left || '').localeCompare(String(right || ''), 'zh-Hans-CN', { numeric: true, sensitivity: 'base' })
}
