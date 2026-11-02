import ExcelJS from 'exceljs'
import { expect, it } from 'vitest'
import { zipSync, unzipSync } from 'fflate'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { workbookPreview } from '../src/workbook.ts'

it('exports saved formula results, visible sheets and sparse windows without modifying the workbook',async()=>{
  const book=new ExcelJS.Workbook(),sheet=book.addWorksheet('月报');sheet.getCell('A1').value='标题';sheet.mergeCells('A1:B1')
  sheet.getCell('A2').value={ formula:'1+1',result:2 };sheet.getCell('A2').numFmt='0.00';sheet.getCell('A1').font={ bold:true };sheet.getCell('A3').value={ formula:'2+2' };sheet.getCell('B3').value={ sharedFormula:'A3' };sheet.getCell('B2').value=new Date('2026-10-12T00:00:00Z');sheet.getCell('B2').numFmt='yyyy-mm-dd';book.addWorksheet('隐藏').state='hidden'
  const data=new Uint8Array(await book.xlsx.writeBuffer()),before=data.slice()
  const result=await workbookPreview(data,1000,new AbortController().signal)
  const index=JSON.parse(new TextDecoder().decode(result.files[0]!.data)) as { sheets:Array<{ merges:string[] }>;missingResults:number }
  const tile=JSON.parse(new TextDecoder().decode(result.files[1]!.data)) as { cells:Array<{ row:number;text:string }> }
  expect(tile.cells[0]).toMatchObject({ bold:true,mergeRows:1,mergeColumns:2 });expect(index.sheets).toHaveLength(1);expect(index.sheets[0]?.merges).toEqual(['A1:B1']);expect(index.missingResults).toBe(2)
  expect(tile.cells.some(cell=>cell.text==='2026-10-12')).toBe(true)
  expect(tile.cells.find((cell:{ row:number })=>cell.row===1)).toMatchObject({ text:'2.00',formula:'1+1' })
  expect(tile.cells.find((cell:{ row:number })=>cell.row===2)).toMatchObject({ text:'',missingResult:true });expect(data).toEqual(before)
  await expect(workbookPreview(data,2,new AbortController().signal)).rejects.toThrow('capacity')
})


it('contains parser memory exhaustion, preserves source bytes and allows the next preview', async () => {
  const book = new ExcelJS.Workbook(); book.addWorksheet('Rows').getCell('A1').value = 1
  const entries = unzipSync(new Uint8Array(await book.xlsx.writeBuffer()))
  entries['xl/worksheets/sheet1.xml'] = new TextEncoder().encode(
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
    + Array.from({ length: 50000 }, (_, index) => `<row r="${index + 1}"><c r="A${index + 1}"><v>1</v></c></row>`).join('')
    + '</sheetData></worksheet>',
  )
  const data = zipSync(entries), before = data.slice()
  await expect(workbookPreview(data, 250000, new AbortController().signal, { timeoutMs: 10000, heapMiB: 32 }))
    .rejects.toThrow(/memory capacity|exited before completion/)
  expect(data).toEqual(before)
  const small = new Uint8Array(await book.xlsx.writeBuffer())
  expect((await workbookPreview(small, 100, new AbortController().signal)).files[0]?.role).toBe('workbook')
})
it('terminates timeout and cancellation without leaving the parser running', async () => {
  const book = new ExcelJS.Workbook(); book.addWorksheet('One').getCell('A1').value = 1
  const data = new Uint8Array(await book.xlsx.writeBuffer())
  await expect(workbookPreview(data, 100, new AbortController().signal, { timeoutMs: 1, heapMiB: 512 }))
    .rejects.toThrow('timed out')
  const controller = new AbortController(), preview = workbookPreview(data, 100, controller.signal)
  controller.abort(new Error('Parser cancelled'))
  await expect(preview).rejects.toThrow('Parser cancelled')
  expect((await workbookPreview(data, 100, new AbortController().signal)).files).toHaveLength(2)
})


it('rejects excess output in the Worker before transferring buffers to the host', async () => {
  const book = new ExcelJS.Workbook(); book.addWorksheet('One').getCell('A1').value = 'saved value'
  const data = new Uint8Array(await book.xlsx.writeBuffer())
  await expect(workbookPreview(data, 100, new AbortController().signal, { timeoutMs: 30000, heapMiB: 512, maxOutputBytes: 8 }))
    .rejects.toThrow('output capacity')
  expect((await workbookPreview(data, 100, new AbortController().signal)).files).toHaveLength(2)
})


it('fails closed when host V8 options override the Worker memory budget', async () => {
  const book = new ExcelJS.Workbook(); book.addWorksheet('One').getCell('A1').value = 1
  const base64 = Buffer.from(await book.xlsx.writeBuffer()).toString('base64')
  const url = new URL('../src/workbook.ts', import.meta.url).href
  const program = `
    import { workbookPreview } from ${JSON.stringify(url)};
    try {
      await workbookPreview(new Uint8Array(Buffer.from(${JSON.stringify(base64)}, 'base64')), 100, new AbortController().signal,
        {timeoutMs:30000,heapMiB:64});
      throw new Error('Worker parsed without enforcing its budget');
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes('memory capacity cannot be enforced')) throw error;
      console.log('Host override contained');
    }
  `
  const result = await promisify(execFile)(process.execPath,
    ['--max-old-space-size=1024', '--import', 'tsx/esm', '--input-type=module', '-e', program], { timeout: 30000 })
  expect(result.stdout).toContain('Host override contained')
})
