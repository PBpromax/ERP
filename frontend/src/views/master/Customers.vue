<template>
  <CrudPage
    ref="pageRef"
    title="客户档案"
    description="查看客户名称、联系人、电话和收货地址，点击详情查看完整资料。"
    edit-label="详情"
    endpoint="/master/customers"
    :columns="columns"
    :fields="fields"
    :load-detail-on-edit="true"
    :prepare-payload="prepareCustomerPayload"
    save-success-message="客户及联系人已保存"
    label-width="150px"
  >
    <template #dialogExtra="{ form }">
      <div class="contact-section">
        <div class="contact-head">
          <div>
            <div class="license-title">公司联系人</div>
            <div class="license-sub">同一公司可维护多个联系人，默认联系人用于新建订单时自动带出。</div>
          </div>
          <el-button type="primary" plain @click="addContact(form)">新增联系人</el-button>
        </div>
        <div v-if="ensureContacts(form).length" class="contact-list">
          <div v-for="(contact, index) in ensureContacts(form)" :key="index" class="contact-row">
            <div class="contact-row-head">
              <el-radio :model-value="primaryContactIndex(form)" :value="index" @change="setPrimaryContact(form, index)">默认联系人</el-radio>
              <el-button link type="danger" @click="removeContact(form, index)">删除此联系人</el-button>
            </div>
            <div class="contact-fields">
              <div class="contact-field">
                <span class="contact-label">姓名</span>
                <el-input v-model="contact.name" placeholder="请输入联系人姓名" />
              </div>
              <div class="contact-field">
                <span class="contact-label">电话</span>
                <el-input v-model="contact.phone" placeholder="请输入联系电话" />
              </div>
              <div class="contact-field">
                <span class="contact-label">邮箱</span>
                <el-input v-model="contact.email" placeholder="请输入邮箱" />
              </div>
              <div class="contact-field">
                <span class="contact-label">职务</span>
                <el-input v-model="contact.position" placeholder="请输入职务" />
              </div>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无联系人" :image-size="48" />
      </div>
      <div class="license-card">
        <div>
          <div class="license-title">营业执照 / 客户资料识别</div>
          <div class="license-sub">支持照片、截图、扫描图片、PDF 和 Word，识别后仍可手动修改。</div>
        </div>
        <el-upload
          :show-file-list="false"
          :auto-upload="false"
          accept="image/*,.pdf,.doc,.docx,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
          :on-change="file => recognizeBusinessLicense(file, form)"
        >
          <el-button type="primary" plain :loading="recognizing">上传并识别</el-button>
        </el-upload>
      </div>
      <div v-if="form.invoiceRequired === true" class="invoice-reader">
        <div class="invoice-reader-head">
          <div>
            <div class="license-title">开票信息识别</div>
            <div class="license-sub">粘贴名称、税号、地址电话、开户行账号、联系人等文字后自动回填。</div>
          </div>
          <el-button type="primary" plain :loading="invoiceRecognizing" @click="recognizeInvoiceInfo(form)">识别开票信息</el-button>
        </div>
        <el-input
          v-model="invoiceText"
          type="textarea"
          :rows="3"
          placeholder="粘贴开票信息文本"
        />
      </div>
      <div v-if="licenseFileName || recognizing" class="license-status">
        <span>{{ recognizing ? '正在识别，请稍候...' : `已上传：${licenseFileName}` }}</span>
      </div>
      <el-alert
        v-if="licenseRawText"
        class="license-alert"
        type="warning"
        :closable="false"
        show-icon
        title="AI 返回内容未能完整解析，请核对后手动补充。"
      >
        <template #default>
          <div class="license-raw">{{ licenseRawText }}</div>
        </template>
      </el-alert>
    </template>
  </CrudPage>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import CrudPage from '@/components/CrudPage.vue'
import http from '@/api/http'


const pageRef = ref()
const recognizing = ref(false)
const invoiceRecognizing = ref(false)
const licenseRawText = ref('')
const licenseFileName = ref('')
const invoiceText = ref('')
const columns = [
  { prop: 'name', label: '客户名称', width: 240 },
  { prop: 'contactNames', label: '联系人', width: 140 },
  { prop: 'phone', label: '电话', width: 160 },
  { prop: 'address', label: '地址', width: 260 }
]
const fields = [
  {
    "prop": "name",
    "label": "客户名称",
    "required": true
  },
  {
    "prop": "legalRepresentative",
    "label": "法人名称"
  },
  {
    prop: 'invoiceRequired',
    label: '是否开票',
    type: 'boolean',
    defaultValue: false
  },
  {
    prop: 'unifiedSocialCreditCode',
    label: '统一社会信用代码',
    required: true,
    visibleWhen: (form: any) => form.invoiceRequired === true
  },
  {
    "prop": "phone",
    "label": "电话"
  },
  {
    "prop": "email",
    "label": "邮箱"
  },
  {
    "prop": "address",
    "label": "地址",
    "span": 24
  },
  {
    "prop": "invoiceTitle",
    visibleWhen: (form: any) => form.invoiceRequired === true,
    "label": "开票名称"
  },
  {
    "prop": "taxNo",
    visibleWhen: (form: any) => form.invoiceRequired === true,
    "label": "纳税人识别号"
  },
  {
    "prop": "publicBankName",
    visibleWhen: (form: any) => form.invoiceRequired === true,
    "label": "开户行",
    "span": 24
  },
  {
    "prop": "publicBankAccount",
    visibleWhen: (form: any) => form.invoiceRequired === true,
    "label": "账号"
  },
  {
    "prop": "publicBankNo",
    visibleWhen: (form: any) => form.invoiceRequired === true,
    "label": "开票电话"
  }
]

function ensureContacts(form: any) {
  if (!Array.isArray(form.contacts)) {
    form.contacts = form.contactPerson
      ? [{ name: form.contactPerson, phone: form.phone || '', email: form.email || '', position: '', primaryContact: true, enabled: true }]
      : []
  }
  return form.contacts
}

function prepareCustomerPayload(payload: Record<string, any>, form: any) {
  const sourceContacts = ensureContacts(form)
  const hasPrimary = sourceContacts.some((contact: any) => Boolean(contact?.primaryContact))
  const contacts = sourceContacts
    .filter((contact: any) => String(contact?.name || '').trim())
    .map((contact: any, index: number) => ({
      name: String(contact.name).trim(),
      phone: String(contact.phone || '').trim(),
      email: String(contact.email || '').trim(),
      position: String(contact.position || '').trim(),
      primaryContact: Boolean(contact.primaryContact) || (!hasPrimary && index === 0),
      enabled: true
    }))
  return { ...payload, contacts }
}

function addContact(form: any) {
  const contacts = ensureContacts(form)
  contacts.push({ name: '', phone: '', email: '', position: '', primaryContact: contacts.length === 0, enabled: true })
}

function removeContact(form: any, index: number) {
  const contacts = ensureContacts(form)
  const removedPrimary = Boolean(contacts[index]?.primaryContact)
  contacts.splice(index, 1)
  if (removedPrimary && contacts.length) contacts[0].primaryContact = true
}

function primaryContactIndex(form: any) {
  const index = ensureContacts(form).findIndex((contact: any) => Boolean(contact.primaryContact))
  return index < 0 ? 0 : index
}

function setPrimaryContact(form: any, index: number) {
  ensureContacts(form).forEach((contact: any, contactIndex: number) => {
    contact.primaryContact = contactIndex === index
  })
}

async function recognizeBusinessLicense(file: any, form: any) {
  const rawFile = file?.raw
  if (!rawFile) return
  licenseFileName.value = rawFile.name || ''
  const body = new FormData()
  body.append('file', rawFile)
  recognizing.value = true
  licenseRawText.value = ''
  try {
    const result = await http.post<any, any>('/master/customers/recognize-business-license', body, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 60000
    })
    const hasRecognized = Boolean(result.customerName || result.legalRepresentative || result.address || result.unifiedSocialCreditCode)
    if (result.customerName) form.name = result.customerName
    if (result.legalRepresentative) form.legalRepresentative = result.legalRepresentative
    if (result.address) form.address = result.address
    if (result.unifiedSocialCreditCode) form.unifiedSocialCreditCode = result.unifiedSocialCreditCode
    licenseRawText.value = result.rawText || ''
    if (hasRecognized) {
      ElMessage.success('营业执照识别完成')
    } else {
      ElMessage.warning('识别完成，但未提取到可回填字段，请查看返回内容或换一张更清晰的文件')
    }
  } finally {
    recognizing.value = false
  }
}

async function recognizeInvoiceInfo(form: any) {
  if (!invoiceText.value.trim()) {
    ElMessage.warning('请先输入开票信息')
    return
  }
  invoiceRecognizing.value = true
  licenseRawText.value = ''
  try {
    const result = await http.post<any, any>('/master/customers/recognize-invoice-info', { text: invoiceText.value }, {
      timeout: 60000
    })
    const hasRecognized = Boolean(result.invoiceTitle || result.taxNo || result.invoiceAddress || result.invoicePhone || result.publicBankName || result.publicBankAccount || result.contactPerson || result.contactPhone)
    if (result.invoiceTitle) {
      form.invoiceTitle = result.invoiceTitle
      if (!form.name) form.name = result.invoiceTitle
    }
    if (result.taxNo) {
      form.taxNo = result.taxNo
      if (!form.unifiedSocialCreditCode) form.unifiedSocialCreditCode = result.taxNo
    }
    if (result.invoiceAddress && !form.address) form.address = result.invoiceAddress
    if (result.invoicePhone) form.publicBankNo = result.invoicePhone
    if (result.publicBankName) form.publicBankName = result.publicBankName
    if (result.publicBankAccount) form.publicBankAccount = result.publicBankAccount
    if (result.contactPerson) {
      const contacts = ensureContacts(form)
      if (!contacts.some((contact: any) => contact.name === result.contactPerson)) {
        contacts.push({ name: result.contactPerson, phone: result.contactPhone || '', email: '', position: '', primaryContact: contacts.length === 0, enabled: true })
      }
    }
    if (result.contactPhone && !form.phone) form.phone = result.contactPhone
    licenseRawText.value = result.rawText || ''
    if (hasRecognized) {
      ElMessage.success('开票信息识别完成')
    } else {
      ElMessage.warning('识别完成，但未提取到可回填字段')
    }
  } finally {
    invoiceRecognizing.value = false
  }
}
</script>

<style scoped>
.contact-section {
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  margin-bottom: 16px;
  padding: 12px 14px;
}

.contact-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
}

.contact-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.contact-row {
  background: #f8fbff;
  border: 1px solid var(--oms-border);
  border-radius: 6px;
  padding: 10px 12px 12px;
}

.contact-row-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
  min-height: 24px;
  margin-bottom: 8px;
}

.contact-fields {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.contact-field {
  min-width: 0;
}

.contact-label {
  color: var(--oms-sub);
  display: block;
  font-size: 12px;
  line-height: 18px;
  margin-bottom: 4px;
}

.license-card {
  align-items: center;
  background: #f8fbff;
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
  padding: 12px 14px;
}

.license-title {
  color: var(--oms-heading);
  font-size: 13px;
  font-weight: 760;
  line-height: 20px;
}

.license-sub {
  color: var(--oms-sub);
  font-size: 12px;
  line-height: 18px;
  margin-top: 2px;
}

.license-alert {
  margin-bottom: 14px;
}

.license-status {
  color: var(--oms-sub);
  font-size: 12px;
  margin: -8px 0 12px;
}

.invoice-reader {
  background: #fff;
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  margin-bottom: 14px;
  padding: 12px 14px;
}

.invoice-reader-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
}

.license-raw {
  max-height: 86px;
  overflow: auto;
  white-space: pre-wrap;
}

@media (max-width: 900px) {
  .contact-fields { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .contact-head { align-items: stretch; flex-direction: column; }
}

@media (max-width: 560px) {
  .contact-fields { grid-template-columns: 1fr; }
  .contact-row-head { align-items: flex-start; flex-direction: column; gap: 4px; }
}
</style>
