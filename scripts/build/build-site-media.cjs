// Build lossless, responsive website images without changing source screenshots.
const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {execFileSync} = require('node:child_process');
module.exports = function buildMedia(site, output) {
  const directory = path.join(output, 'static');
  fs.mkdirSync(directory, {recursive:true});
  const media = {};
  function publish(file, stem, extension) {
    const bytes=fs.readFileSync(file),hash=createHash('sha256').update(bytes).digest('hex').slice(0,16);
    const name=stem+'.'+hash+'.'+extension;
    fs.writeFileSync(path.join(directory,name),bytes);
    return '/static/'+name;
  }
  for(const name of fs.readdirSync(path.join(site,'assets'))) {
    if(!/\.(png|jpg)$/.test(name))continue;
    media['assets/'+name]={src:publish(path.join(site,'assets',name),path.parse(name).name,path.extname(name).slice(1))};
  }
  for(const family of ['revenue-trend','target-completion','iphone-chart-reading']) for(const locale of ['','-en']) {
    const stem=family+locale,source=path.join(site,'media/product',stem+'.png');
    const width=fs.readFileSync(source).readUInt32BE(16),widths=family==='iphone-chart-reading'?[320,640]:[640,960,1920];
    const variants=[];
    for(const size of widths.filter(x=>x<=width)) {
      const temporary=path.join(directory,stem+'-'+size+'.work.webp');
      execFileSync(process.env.CWEBP_BIN||'cwebp',['-quiet','-lossless','-exact','-resize',String(size),'0',source,'-o',temporary],{stdio:'pipe'});
      variants.push({width:size,url:publish(temporary,stem+'-'+size,'webp')});fs.unlinkSync(temporary);
    }
    const original=publish(source,stem,'png');
    media['media/product/'+stem+'.png']={src:family==='iphone-chart-reading'?variants.at(-1).url:original,original,srcset:variants.map(v=>v.url+' '+v.width+'w').join(', ')};
  }
  for(const locale of ['','-en']) {
    const name='iphone-chart-preview'+locale+'.mp4';
    media['media/product/'+name]={src:publish(path.join(site,'media/product',name),'iphone-chart-preview'+locale,'mp4')};
  }
  return media;
};
