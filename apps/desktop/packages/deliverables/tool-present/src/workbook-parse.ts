/** Sparse saved-value workbook previews; formulas and external links are never evaluated. */
import ExcelJS from 'exceljs'
import { update as formatNumber } from '@fortune-sheet/core'
import { unzipSync } from 'fflate'

interface Cell {
  row:number
  column:number
  text:string
  formula?:string
  missingResult?:boolean
  bold?:boolean
  italic?:boolean
  align?:string
  mergeRows?:number
  mergeColumns?:number
}
interface Part { role:'workbook'|'sheet'; key:string; name:string; data:Uint8Array }
/** Encode visible worksheets as independently downloadable sparse row windows.
 * @param data - fixed original XLSX bytes.
 * @param maxCells - maximum visited cells and rectangular display area.
 * @param signal - preview lifetime.
 * @param maxOutputBytes - combined JSON budget before transferring bytes to the host.
 * @returns workbook index, sheet windows and omission warnings.
 */
export async function parseWorkbook(
  data:Uint8Array,maxCells:number,signal:AbortSignal,maxOutputBytes=64*1024*1024,
):Promise<{ files:Part[];warnings:string[] }>{
  signal.throwIfAborted()
  let expanded=0,entries=0
  const parts=unzipSync(data,{ filter:(entry)=>{
    expanded+=entry.originalSize;entries++
    if(entries>10000||expanded>64*1024*1024)throw new Error('Workbook archive exceeds preview capacity')
    return false
  } })
  void parts
  const workbook=new ExcelJS.Workbook()
  await workbook.xlsx.load(new Uint8Array(data).buffer)
  signal.throwIfAborted()
  const files:Part[]=[],sheets:Array<{ id:string;name:string;rows:number;columns:number;tiles:string[];merges:string[];widths:number[] }>=[]
  let count=0,area=0,missing=0,outputBytes=0
  const encode=(value:unknown):Uint8Array=>new TextEncoder().encode(JSON.stringify(value))
  for(const sheet of workbook.worksheets){
    if(sheet.state!=='visible')continue
    if(sheets.length>=100)throw new Error('Workbook visible sheet count exceeds preview capacity')
    const rows=sheet.rowCount,columns=sheet.columnCount;area+=rows*columns
    if(!Number.isSafeInteger(area)||area>maxCells)throw new Error('Workbook display area exceeds preview capacity')
    const tiles=new Map<number,Cell[]>(),id=`sheet-${sheet.id}`
    const merges=new Map<string,{ rows:number;columns:number }>()
    const columnIndex=(name:string):number=>name.split('').reduce((value,char)=>value*26+char.charCodeAt(0)-64,0)
    for(const range of sheet.model.merges){
      const match=/^([A-Z]+)(\d+):([A-Z]+)(\d+)$/.exec(range)
      if(match===null)continue
      const [,left,top,right,bottom]=match
      if(left===undefined||top===undefined||right===undefined||bottom===undefined)continue
      merges.set(`${left}${top}`,{ rows:Number(bottom)-Number(top)+1,columns:columnIndex(right)-columnIndex(left)+1 })
    }
    sheet.eachRow((row)=>{ row.eachCell((cell)=>{
      signal.throwIfAborted();if(++count>maxCells)throw new Error('Workbook cell count exceeds preview capacity')
      if(cell.isMerged&&cell.address!==cell.master.address)return
      const value=cell.value,entry:Cell={ row:row.number-1,column:Number(cell.col)-1,text:cell.text }
      if(value!==null&&typeof value==='object'&&('formula' in value||'sharedFormula' in value)){
        const formula:unknown=Reflect.get(cell,'formula')
        if(typeof formula==='string')entry.formula=formula
        if(value.result===undefined){entry.text='';entry.missingResult=true;missing++}
        else entry.text=typeof value.result==='object' ? value.result instanceof Date ? value.result.toISOString() : value.result.error : String(value.result)
      }
      // ExcelJS runtime getters can be absent despite their non-optional declarations.
      const cached:unknown=Reflect.get(cell,'result')
      const saved=cached??cell.value
      const font=cell.font as Partial<ExcelJS.Font>|undefined
      const alignment=cell.alignment as Partial<ExcelJS.Alignment>|undefined
      if(typeof saved==='number')entry.text=String(formatNumber(cell.numFmt||'General',saved)??saved)
      else if(saved instanceof Date){
        const serial=saved.getTime()/86400000+(workbook.properties.date1904?24107:25569)
        entry.text=String(formatNumber(cell.numFmt||'yyyy-mm-dd',serial)??saved.toISOString())
      }
      if(font?.bold)entry.bold=true
      if(font?.italic)entry.italic=true
      if(alignment?.horizontal!==undefined)entry.align=alignment.horizontal
      const merge=merges.get(cell.address)
      if(merge!==undefined){entry.mergeRows=merge.rows;entry.mergeColumns=merge.columns}
      const tile=Math.floor(entry.row/100),cells=tiles.get(tile)??[];cells.push(entry);tiles.set(tile,cells)
    }) })
    const keys:string[]=[]
    for(const [tile,cells] of tiles){
      const key=`${id}-${tile}`,encoded=encode({ schema:1,sheetId:id,firstRow:tile*100,cells })
      if(encoded.byteLength>2*1024*1024)throw new Error('Workbook row window exceeds preview capacity')
      outputBytes+=encoded.byteLength
      if(outputBytes>maxOutputBytes)throw new Error('Workbook combined preview exceeds parser output capacity')
      keys.push(key);files.push({ role:'sheet',key,name:`${key}.json`,data:encoded })
    }
    const layoutColumns=sheet.columns as ExcelJS.Column[]|undefined
    sheets.push({ id,name:sheet.name,rows,columns,tiles:keys,merges:sheet.model.merges,
      widths:(layoutColumns??[]).map(column=>column.width??10) })
  }
  if(sheets.length===0||files.length>250)throw new Error('Workbook has no visible sheet or too many preview windows')
  const warnings=['spreadsheet-drawings-omitted',...missing>0?['formula-result-missing']:[]]
  const index=encode({ schema:1,sheets,missingResults:missing,warnings })
  if(index.byteLength>2*1024*1024||outputBytes+index.byteLength>maxOutputBytes) {
    throw new Error('Workbook combined preview exceeds parser output capacity')
  }
  files.unshift({ role:'workbook',key:'workbook',name:'workbook.json',data:index })
  return{ files,warnings }
}
