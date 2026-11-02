/** Bundled Python authoring and native/WASM conversion of explicit template fonts. */
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execa } from 'execa'
import { createConverter, type Converter } from '@deepseek-ai/libreoffice-kit'
import { expect, it } from 'vitest'

const python = process.env.DSH_OFFICE_TEST_PYTHON
const helper = fileURLToPath(new URL('../assets/scripts/new_docx.py', import.meta.url))

it.skipIf(python === undefined || process.platform !== 'darwin')('creates new bilingual documents with installed system fonts and refuses overwrite', async () => {
  const root = await mkdtemp(join(tmpdir(), 'office-fonts-'))
  let converter: Converter | undefined
  try {
    converter = await createConverter({ fontDirectories: ['/System/Library/Fonts', '/Library/Fonts'], fontMetadataCacheDirectory: false })
    const path = join(root, 'report.docx')
    const args = [helper, '--output', path, '--latin', 'Arial', '--east-asian', 'Songti SC', '--monospace', 'Courier New']
    await execa(python!, args)
    await execa(python!, ['-c', 'from docx import Document; import sys; d=Document(sys.argv[1]); d.add_heading("经营复核 / Revenue review"); d.add_paragraph("March revenue: 535; Q1: 1330. 月度营收与季度趋势。"); d.save(sys.argv[1])', path])
    const original = await readFile(path)
    const overwritten = await execa(python!, args, { reject: false })
    expect(overwritten.exitCode).not.toBe(0)
    expect(await readFile(path)).toEqual(original)
    const result = await converter.render({ inputPath: path, outputPath: join(root, 'preview.pdf') })
    expect(result.missingFonts).toEqual([])
    expect((await readFile(join(root, 'preview.pdf'))).subarray(0, 5).toString()).toBe('%PDF-')
  } finally {
    try { await converter?.dispose() }
    finally { await rm(root, { recursive: true, force: true }) }
  }
}, 120_000)
