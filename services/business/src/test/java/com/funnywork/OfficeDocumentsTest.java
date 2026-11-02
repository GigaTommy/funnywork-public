package com.funnywork;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
class OfficeDocumentsTest {
 final OfficeDocuments office=new OfficeDocuments(new ObjectMapper());
 @Test void wordHeadingsTablesAndEditsKeepOriginalAndParagraphFormatting()throws Exception{
  byte[] original=office.create("docx","{\"title\":\"办公能力核验\",\"paragraphs\":[{\"text\":\"部门工作计划\",\"style\":\"Heading1\"},\"原始正文\"],\"tables\":[{\"rows\":[[\"项目\",\"状态\"],[\"文稿\",\"待审阅\"]]}]}");
  var page=office.inspect("docx",original,0,20,0);assertEquals(7,page.get("totalItems"));assertTrue(page.toString().contains("原始正文"));
  byte[] edited=office.edit("docx",original,"{\"operations\":[{\"type\":\"replaceParagraph\",\"paragraph\":1,\"text\":\"修改后的计划\"},{\"type\":\"setTableCell\",\"table\":0,\"row\":1,\"column\":1,\"text\":\"已核验\"}]}");
  try(var doc=new XWPFDocument(new ByteArrayInputStream(edited))){assertEquals("Heading1",doc.getParagraphs().get(1).getStyle());assertTrue(doc.getParagraphs().get(1).getRuns().get(0).isBold());assertEquals("已核验",doc.getTables().get(0).getRow(1).getCell(1).getText());}
  assertTrue(office.inspect("docx",original,0,20,0).toString().contains("部门工作计划"));
 }
 @Test void slidesAndSpeakerNotesRemainEditable()throws Exception{
  byte[] original=office.create("pptx","{\"slides\":[{\"title\":\"季度汇报\",\"bullets\":[\"业务进展\",\"下一步安排\"],\"notes\":\"讲稿：确认项目状态\",\"table\":[[\"指标\",\"数值\"],[\"任务\",\"12\"]]}]}");
  var page=office.inspect("pptx",original,0,20,0);assertTrue(page.toString().contains("确认项目状态"));
  byte[] edited=office.edit("pptx",original,"{\"operations\":[{\"type\":\"replaceShapeText\",\"slide\":0,\"shapeId\":2,\"paragraph\":0,\"text\":\"年度汇报\"},{\"type\":\"appendSlide\",\"newSlide\":{\"title\":\"后续计划\",\"bullets\":[\"复核成果\"]}}]}");
  try(var deck=new XMLSlideShow(new ByteArrayInputStream(edited))){assertEquals(2,deck.getSlides().size());assertEquals(960,deck.getPageSize().width);assertTrue(deck.getSlides().get(0).getShapes().stream().anyMatch(s->s instanceof XSLFTextShape t && t.getText().contains("年度汇报")));assertNotNull(deck.getSlides().get(0).getNotes());}
 }
 @Test void excelFormulaCacheNumberFormatChartAndRecalculation()throws Exception{
  String spec="{\"sheets\":[{\"name\":\"统计\",\"rows\":[[\"项目\",\"数量\"],[\"甲\",3],[\"乙\",4],[\"合计\",{\"formula\":\"SUM(B2:B3)\",\"numberFormat\":\"#,##0.00\"}],[\"公式样文本\",\"=HYPERLINK(\\\"https://example.org\\\")\"]],\"chart\":{\"title\":\"项目数量\",\"categoryColumn\":0,\"valueColumn\":1,\"firstRow\":1,\"lastRow\":2}}]}";
  byte[] original=office.create("xlsx",spec);try(var wb=new XSSFWorkbook(new ByteArrayInputStream(original))){assertEquals(7,wb.getSheetAt(0).getRow(3).getCell(1).getNumericCellValue());assertEquals("#,##0.00",wb.getSheetAt(0).getRow(3).getCell(1).getCellStyle().getDataFormatString());assertEquals(1,wb.getSheetAt(0).getDrawingPatriarch().getCharts().size());assertEquals(org.apache.poi.ss.usermodel.CellType.STRING,wb.getSheetAt(0).getRow(4).getCell(1).getCellType());}
  byte[] edited=office.edit("xlsx",original,"{\"operations\":[{\"type\":\"setCell\",\"sheet\":\"统计\",\"cell\":\"B2\",\"value\":10}]}");try(var wb=new XSSFWorkbook(new ByteArrayInputStream(edited))){assertEquals(14,wb.getSheetAt(0).getRow(3).getCell(1).getNumericCellValue());assertEquals("10.0",wb.getSheetAt(0).getDrawingPatriarch().getCharts().get(0).getCTChart().getPlotArea().getBarChartArray(0).getSerArray(0).getVal().getNumRef().getNumCache().getPtArray(0).getV());assertEquals(1,wb.getSheetAt(0).getDrawingPatriarch().getCharts().size());}
 }
 @Test void rejectFormulasThatAccessExternalServicesOrReturnErrors(){for(String formula:List.of("WEBSERVICE(\"https://example.org\")","HYPERLINK(\"file:///etc/passwd\")","SUM(A:A)","SUM(A1:A1001)")){String json="{\"sheets\":[{\"name\":\"Sheet\",\"rows\":[[{\"formula\":"+new ObjectMapper().valueToTree(formula)+"}]]}]}";assertThrows(ApiException.class,()->office.create("xlsx",json));}assertEquals(422,assertThrows(ApiException.class,()->office.create("xlsx","{\"sheets\":[{\"name\":\"Sheet\",\"rows\":[[{\"formula\":\"1/0\"}]]}]}" )).status);}
 @Test void validateZipPackageAndBoundPreviewText()throws Exception{
  byte[] word=office.create("docx","{\"paragraphs\":[\""+"字".repeat(3000)+"\"]}");var first=office.inspect("docx",word,0,1,0);var next=office.inspect("docx",word,0,1,2000);assertTrue(first.toString().contains("hasMoreText=true"));assertTrue(next.toString().contains("textOffset=2000"));
  assertThrows(ApiException.class,()->office.inspect("xlsx",word,0,10,0));assertThrows(ApiException.class,()->office.format("report.docm"));
  var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){for(String name:List.of("[Content_Types].xml","word/document.xml","word/vbaProject.bin")){zip.putNextEntry(new ZipEntry(name));zip.write("<root/>".getBytes());zip.closeEntry();}}
  assertThrows(ApiException.class,()->office.inspect("docx",out.toByteArray(),0,10,0));
 }
 @Test void scalarReplacementRemovesFormulaButKeepsFormattingAndRecalculatesDependents()throws Exception{
  byte[] original=office.create("xlsx","{\"sheets\":[{\"name\":\"Sheet\",\"rows\":[[\"值\"],[{\"formula\":\"1+2\",\"numberFormat\":\"0.00\"}],[{\"formula\":\"SUM(A2)\"}]]}]}");
  for(String literal:List.of("99","\"固定文字\"","true","null","{\"value\":99}")){
   byte[] edited=office.edit("xlsx",original,"{\"operations\":[{\"type\":\"setCell\",\"sheet\":\"Sheet\",\"cell\":\"A2\",\"value\":"+literal+"}]}");
   try(var wb=new XSSFWorkbook(new ByteArrayInputStream(edited))){var cell=wb.getSheetAt(0).getRow(1).getCell(0);assertNotEquals(org.apache.poi.ss.usermodel.CellType.FORMULA,cell.getCellType());assertEquals("0.00",cell.getCellStyle().getDataFormatString());if(literal.contains("99")){assertEquals(99,cell.getNumericCellValue());assertEquals(99,wb.getSheetAt(0).getRow(2).getCell(0).getNumericCellValue());}else if(literal.equals("true"))assertTrue(cell.getBooleanCellValue());else if(literal.equals("null"))assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK,cell.getCellType());else assertEquals("固定文字",cell.getStringCellValue());}
  }
  try(var wb=new XSSFWorkbook(new ByteArrayInputStream(original))){assertEquals("1+2",wb.getSheetAt(0).getRow(1).getCell(0).getCellFormula());}
 }
 private byte[] relationships(byte[] source,String xml)throws Exception{var out=new ByteArrayOutputStream();try(var zip=new ZipInputStream(new ByteArrayInputStream(source));var dest=new ZipOutputStream(out)){for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){dest.putNextEntry(new ZipEntry(entry.getName()));dest.write(entry.getName().equals("word/_rels/document.xml.rels")?xml.getBytes(java.nio.charset.StandardCharsets.UTF_8):zip.readAllBytes());dest.closeEntry();}}return out.toByteArray();}
 @Test void prefixedRelationshipsCannotBypassExternalTargetValidation()throws Exception{
  byte[] source=office.create("docx","{\"paragraphs\":[\"内容\"]}");String namespace="http://schemas.openxmlformats.org/package/2006/relationships";
  for(String prefix:List.of("","r:")){String opening="<"+prefix+"Relationships "+(prefix.isEmpty()?"xmlns":"xmlns:r")+"=\""+namespace+"\">",closing="</"+prefix+"Relationships>";
   for(String type:List.of("attachedTemplate","image")){byte[] unsafe=relationships(source,opening+"<"+prefix+"Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/"+type+"\" TargetMode=\"External\" Target=\"https://example.org/template\"/>"+closing);assertThrows(ApiException.class,()->office.inspect("docx",unsafe,0,20,0));}
   byte[] safe=relationships(source,opening+"<"+prefix+"Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" TargetMode=\"External\" Target=\"https://example.org/\"/>"+closing);assertDoesNotThrow(()->office.inspect("docx",safe,0,20,0));
  }
  byte[] foreign=relationships(source,"<Relationships xmlns=\""+namespace+"\" xmlns:x=\"urn:foreign\"><x:Relationship Id=\"x\" TargetMode=\"External\" Target=\"file:///tmp/x\"/></Relationships>");assertThrows(ApiException.class,()->office.validatePackage("docx",foreign));
 }

}
