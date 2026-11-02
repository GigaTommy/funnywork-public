package com.funnywork;

import java.util.*;

/** Versioned read-only contracts implemented by the model node, never arbitrary scripts. */
final class BuiltinToolTemplates {
    record Template(String id,String title,ToolContract contract) {}
    private static ToolContract contract(String name,String strings,String integers) {
        Map<String,ToolContract.Field> fields=new TreeMap<>();
        for(String field:strings.split(","))if(!field.isEmpty())fields.put(field,new ToolContract.Field(ToolContract.Kind.STRING,true,field.endsWith("Id")||field.equals("afterId")?64:256));
        for(String field:integers.split(","))if(!field.isEmpty())fields.put(field,new ToolContract.Field(ToolContract.Kind.INTEGER,true,64));
        return new ToolContract(name,ToolContract.Effect.READ_ONLY,fields,"vault://platform/lease",name.equals("artifact.list.v1")?"artifact.metadata.v1":"artifact.sha256.v1");
    }
    private static ToolContract knowledge(boolean read) {
        Map<String,ToolContract.Field> fields=new TreeMap<>();
        if(read) {
            fields.put("sourceId",new ToolContract.Field(ToolContract.Kind.STRING,true,120));
            fields.put("sha256",new ToolContract.Field(ToolContract.Kind.STRING,true,64));
            fields.put("offset",new ToolContract.Field(ToolContract.Kind.INTEGER,true,64));
        } else fields.put("query",new ToolContract.Field(ToolContract.Kind.STRING,true,128));
        fields.put("limit",new ToolContract.Field(ToolContract.Kind.INTEGER,true,64));
        return new ToolContract(read?"knowledge.read.v1":"knowledge.literal.v1",ToolContract.Effect.READ_ONLY,fields,"vault://sources/execution-principal","knowledge.pinned.v1");
    }
    private static ToolContract office(String action) {
        Map<String,ToolContract.Field> fields=new TreeMap<>();
        if(!action.equals("create")){fields.put("artifactId",new ToolContract.Field(ToolContract.Kind.STRING,true,64));fields.put("version",new ToolContract.Field(ToolContract.Kind.INTEGER,true,32));}
        if(action.equals("read")){fields.put("textOffset",new ToolContract.Field(ToolContract.Kind.INTEGER,false,64));fields.put("offset",new ToolContract.Field(ToolContract.Kind.INTEGER,true,64));fields.put("limit",new ToolContract.Field(ToolContract.Kind.INTEGER,true,64));}
        else {fields.put("documentJson",new ToolContract.Field(ToolContract.Kind.STRING,true,10000));if(action.equals("create")){fields.put("name",new ToolContract.Field(ToolContract.Kind.STRING,true,120));fields.put("format",new ToolContract.Field(ToolContract.Kind.STRING,true,4));}}
        return new ToolContract("office."+action+".v1",action.equals("read")?ToolContract.Effect.READ_ONLY:ToolContract.Effect.ARTIFACT_WRITE,fields,"vault://platform/lease","office.ooxml.v1");
    }
    static boolean isOfficeTool(ToolContract contract){return contract.equals(office("read")) || isOfficeWrite(contract);}
    static boolean isOfficeWrite(ToolContract contract){return (contract.equals(office("create")) || contract.equals(office("edit")));}
    static final List<Template> ALL=List.of(
        new Template("office-read","读取 Word/WPS、PowerPoint/演示、Excel/表格",office("read")),
        new Template("office-create","生成可编辑办公文档（任务成果）",office("create")),
        new Template("office-edit","生成办公文档修订副本（保留原件）",office("edit")),
        new Template("attachment-list","查找任务附件",contract("artifact.list.v1","afterId","afterVersion,limit")),
        new Template("text-read","读取文本附件",contract("artifact.read_text.v1","artifactId","version,offset,limit")),
        new Template("csv-read","读取CSV表格",contract("artifact.read_csv.v1","artifactId","version,offset,limit")),
        new Template("csv-summary","汇总CSV数值列",contract("artifact.summarize_csv.v1","artifactId,column","version")),
        new Template("csv-compare","比对两份CSV",contract("artifact.compare_csv.v1","leftId,rightId,leftKey,rightKey,leftColumn,rightColumn","leftVersion,rightVersion,offset,limit")),
        new Template("knowledge-search","检索授权知识集合",knowledge(false)),
        new Template("knowledge-read","读取知识引用原文",knowledge(true))
    );
    static Template get(String id) {return ALL.stream().filter(item->item.id().equals(id)).findFirst().orElseThrow(ApiException::missing);}
}
