export interface ProductSeedItem {
  category_code: string
  product_name: string
  product_code: string
  legacy_code_mode?: boolean
}

// Enterprise product records are configured privately.
export const productSeedData: ProductSeedItem[] = []
