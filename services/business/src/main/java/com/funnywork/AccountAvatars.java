package com.funnywork;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Account pictures remain private and are returned only with authenticated profile reads. */
@Service
public class AccountAvatars {
 private final JdbcTemplate db;private final TenantLocks locks;
 public AccountAvatars(JdbcTemplate db,TenantLocks locks){this.db=db;this.locks=locks;}
 public String read(Identity actor){
  if(!"USER".equals(actor.kind()))throw ApiException.forbidden();
  var rows=db.queryForList("select image from account_avatars where tenant_id=? and principal_id=?",actor.tenant(),actor.id());
  return rows.isEmpty()?"":(String)rows.get(0).get("image");
 }
 @org.springframework.transaction.annotation.Transactional
 public void save(Identity actor,String image){
  if(!"USER".equals(actor.kind()))throw ApiException.forbidden();
  try {
   if(image==null || image.length()>350000 || !image.matches("data:image/(png|jpeg);base64,[A-Za-z0-9+/=]+"))throw new IllegalArgumentException();
   byte[] bytes=Base64.getDecoder().decode(image.substring(image.indexOf(',')+1));
   if(bytes.length>256000)throw new IllegalArgumentException();
   try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
    var readers=ImageIO.getImageReaders(input);
    if(!readers.hasNext())throw new IllegalArgumentException();
    var reader=readers.next();
    try {
     reader.setInput(input);
     if(reader.getWidth(0)>512 || reader.getHeight(0)>512 || reader.getWidth(0)<1 || reader.getHeight(0)<1)throw new IllegalArgumentException();
     String format=reader.getFormatName();
     if(!(format.equalsIgnoreCase("PNG") || format.equalsIgnoreCase("JPEG")) || reader.read(0)==null)throw new IllegalArgumentException();
    }finally{reader.dispose();}
   }
  }catch(Exception error){throw new ApiException(400,"INVALID_AVATAR","Choose a PNG or JPEG picture up to 512 pixels and 256 KB");}
  locks.lock(actor.tenant());
  if(db.update("update account_avatars set image=? where tenant_id=? and principal_id=?",image,actor.tenant(),actor.id())==0)db.update("insert into account_avatars(tenant_id,principal_id,image) values(?,?,?)",actor.tenant(),actor.id(),image);
 }
}
