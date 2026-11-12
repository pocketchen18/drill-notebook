import fs from 'node:fs';
import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { assertNoLinks, writeAtomicJson } from './program-manifest';

/** Portable encryption protects unsent text/attachments without machine-bound DPAPI or browser storage limits. */
export class AssistantDraftStore {
  private readonly file: string;
  private readonly keyFile: string;
  constructor(private readonly root: string) {
    this.file = assertNoLinks(root, 'config/assistant-drafts.json');
    this.keyFile = assertNoLinks(root, 'config/assistant-drafts-key.bin');
  }
  private key(): Buffer {
    assertNoLinks(this.root, 'config/assistant-drafts-key.bin');
    if (!fs.existsSync(this.keyFile)) fs.writeFileSync(this.keyFile, randomBytes(32), { flag: 'wx', mode: 0o600 });
    const key = fs.readFileSync(this.keyFile);
    if (key.length !== 32) throw new Error('助手草稿加密材料无效，请先保存当前输入再更新。');
    return key;
  }
  read(): unknown {
    assertNoLinks(this.root, 'config/assistant-drafts.json');
    if (!fs.existsSync(this.file)) return null;
    if (fs.statSync(this.file).size > 96 * 1024 ** 2) throw new Error('助手草稿文件超过安全大小。');
    const raw = JSON.parse(fs.readFileSync(this.file, 'utf8')) as { format?: number; nonce: string; tag: string; value: string };
    if (raw.format !== 1) throw new Error('助手草稿格式无效。');
    const cipher = createDecipheriv('aes-256-gcm', this.key(), Buffer.from(raw.nonce, 'base64'));
    cipher.setAuthTag(Buffer.from(raw.tag, 'base64'));
    return JSON.parse(Buffer.concat([cipher.update(Buffer.from(raw.value, 'base64')), cipher.final()]).toString('utf8'));
  }
  save(value: unknown): void {
    if (!value || typeof value !== 'object' || (value as { format?: unknown }).format !== 1
      || !Array.isArray((value as { drafts?: unknown }).drafts)) throw new Error('助手草稿格式无效。');
    const source = Buffer.from(JSON.stringify(value), 'utf8');
    if (source.length > 64 * 1024 ** 2) throw new Error('助手草稿总量超过 64 MiB，请先保存部分附件再更新。');
    assertNoLinks(this.root, 'config/assistant-drafts.json');
    assertNoLinks(this.root, 'config/assistant-drafts-key.bin');
    const nonce = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.key(), nonce);
    const encrypted = Buffer.concat([cipher.update(source), cipher.final()]);
    writeAtomicJson(this.file, { format: 1, nonce: nonce.toString('base64'), tag: cipher.getAuthTag().toString('base64'), value: encrypted.toString('base64') });
  }
}
