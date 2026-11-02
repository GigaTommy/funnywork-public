// Synthetic UA fixtures exercise page routing and capability profiles; they do not certify physical devices.
const android='Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/99.0.4844.88 Mobile Safari/537.36';
module.exports=[
 {name:'Huawei',userAgent:android+' HuaweiBrowser/13.0.2.301',android:true,wechat:false},
 {name:'Xiaomi',userAgent:android+' MiuiBrowser/17.0.0',android:true,wechat:false},
 {name:'OPPO',userAgent:android+' HeyTapBrowser/40.10.0.1',android:true,wechat:false},
 {name:'vivo',userAgent:android+' VivoBrowser/18.0.0.0',android:true,wechat:false},
 {name:'UC',userAgent:android+' UCBrowser/15.0.0.0 UWS/2.13.0.0',android:true,wechat:false},
 {name:'QQ Browser',userAgent:android+' MQQBrowser/14.0 TBS/046000',android:true,wechat:false},
 {name:'WeChat Android X5',userAgent:android+' MQQBrowser/6.2 TBS/046000 MicroMessenger/8.0.40 NetType/WIFI',android:true,wechat:true},
 {name:'WeChat Android XWeb',userAgent:android+' MicroMessenger/8.0.63 XWEB/1900397 NetType/WIFI',android:true,wechat:true},
 {name:'WeChat iOS',userAgent:'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 MicroMessenger/8.0.49',android:false,wechat:false},
 {name:'WeChat desktop',userAgent:'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36 MicroMessenger/3.8.0',android:false,wechat:false},
];
