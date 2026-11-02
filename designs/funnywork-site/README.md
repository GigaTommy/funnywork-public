# FunnyWork 官网维护

**简体中文** | [English](README.en.md)

供前端开发、内容编辑和发布人员使用。本站为纯白背景的中英文静态官网，使用暖橙主操作、深墨正文与少量辅助蓝。对外品牌为 FunnyWork，服务域名和客户端标识保持稳定。

客户操作见 [使用指南](../../manuals/USER_GUIDE.md)；网站说明长期产品用途，单个版本交付的变化另按 [发布说明模板](../../deploy/RELEASE_NOTES_TEMPLATE.md) 编写。发布前按目标环境核对资源清单和版本。

## 内容与素材

页面围绕数据整理、图表汇报、多端协同和企业内部署说明客户可完成的工作。中文与英文分别润色并表达相同事实；客服中文为珠珠，英文为 Gigi。案例正文减少姓名重复，不把采集、测试或接管过程写入宣传文案。

公开素材来自获授权的 Gigi 正常工作场景；业务数据为示例数据，不能作为真实客户业绩宣传。截图与录屏保留原应用画面，不使用管理员、其他账号历史、接管工具画面或合成界面。新采集先使用隔离环境和专用账号验证；涉及生产账号或数据时单独确认授权及脱敏范围。

当前 `site.js` 选用以下四组媒体，中英文分别采集和生成，英文增加 `-en` 后缀：

| `media/product/` 文件 | 用途 |
| --- | --- |
| `revenue-trend.png` | 季度营收趋势图 |
| `target-completion.png` | 区域目标完成率图 |
| `iphone-chart-reading.png` | 手机成果预览与视频封面 |
| `iphone-chart-preview.mp4` | 手机图表查阅录屏 |

`iphone-desktop-task.png` 及英文版保留既有路径兼容，不作为当前首屏任务看板宣传。采集原件、凭证、日志及退役素材留在已忽略目录，不随官网发布。更换媒体时同步检查语言映射、尺寸、替代文本、原图链接、封面与版本缓存参数。

## 页面交互与客服

语言切换更新正文、替代文本、图表、截图、视频及封面，并保留当前图表选择。图片支持页面内预览、Escape 关闭和原图入口；视频由用户播放、支持手机内联，失败后提供重试及直接打开。

咨询入口定位本页客服区。表单仅在设备上生成邮件草稿预览，由用户打开邮件应用并确认发送，不向官网服务器提交或保存需求。收件地址读取 `site.js` 的客服配置；复制失败保留手动复制入口，长内容提示复制后粘贴。语言选择和视频进度保存在浏览器本地，处理方式须与隐私页一致。

## 授权与平台说明

首页、下载页和使用指南均链接到 `privacy.html#licensing` 的软件许可与商业授权说明。中英文内容与根目录 `LICENSE`、`COMMERCIAL-LICENSE.txt` 和 `THIRD_PARTY_NOTICES.md` 一致：许可范围内的非商业使用免费，企业内部使用与其他商业用途需先取得书面商业授权；第三方部分遵循独立许可。发布构建从仓库原文生成带内容摘要的公开许可文件，官网访客无需仓库访问权限即可阅读完整条款。

iOS 功能介绍区分独立聊天与电脑协作，使用指南链接到 GitHub `main` 的 iOS 开发文档，并注明源码仓库访问条件。最低系统要求为 iOS 17；安装邀请由签名下载清单提供，避免在页面中复制固定邀请、版本或审核状态。中英文 canonical、hreflang、结构化数据和网站地图统一使用官网域名。

## 本地预览与检查

从仓库根目录运行本地静态预览：

```sh
python3 -m http.server 4313 --bind 127.0.0.1 --directory designs/funnywork-site
```

安装桌面工作区依赖后，按变更范围执行：

```sh
node scripts/checks/check-site-interactions.cjs
node scripts/checks/check-site-downloads.cjs
node scripts/checks/check-site-release.mjs
python3 scripts/checks/check-repository.py
```

人工检查桌面与手机的完整浏览、长标题换行、中英文往返切换、图表原图、视频播放及失败恢复、客服草稿与复制回退。浏览器模拟用于自动检查；目标浏览器、微信和品牌设备分别完成实际安装验收。

## 下载与签名

`download.html` 通过 `/download/` 提供入口，`downloads.js` 校验签名下载清单后显示已开放平台。平台可用性以通过签名、有效期及发布资格检查的清单为准，不在 README 固定抄写安装包版本或 TestFlight 审核状态。

清单保留 Ed25519 和绑定相同载荷的 ECDSA P-256 兼容签名；算法不支持时使用兼容路径，验签失败不降级。安卓 UA 只调整卡片顺序，不隐藏其他可用平台。微信安卓版提供在系统浏览器打开的提示及当前站点、当前语言链接。缺少可用验签能力时提示升级浏览器，不提供未经验证的下载。

签名、密钥保管、安装升级、更新通道及 TestFlight 条件见 [客户端发布与升级](../../deploy/CLIENT_RELEASES.md)。网站验签核对下载清单；客户端安装与升级继续执行系统发布者、平台签名及应用更新校验。网站脚本和公钥的完整性由站点发布与访问防护维护。

## 部署与回退

官网地址为 `https://funnywork.funnypixelsapp.com`。部分区域通过当地云服务跳转接入。发布清单包含首页、隐私与安全页、`site.js`、`styles.css`，下载页的 HTML／CSS／JS 与签名清单，以及被页面引用的品牌和中英文媒体。不要复制整个设计目录或将本目录 Markdown 运维资料公开。

发布前记录现网文件摘要、Nginx 配置、后台及客户端接口基线并备份。安装包采用不可覆盖的版本路径，先上传并校验远端摘要，再切换已验签的清单和页面。新增公开路径时使用精确白名单，配置检查通过后才重载；官网静态发布无需重启业务服务。

相关模板：[区域接入](../../deploy/nginx/FUNNYWORK_CN.md)、[本地化媒体](../../deploy/nginx/funnywork-site-localized-media.conf.example)、[客服头像](../../deploy/nginx/funnywork-site-avatar.conf.example)、[下载路径](../../deploy/nginx/funnywork-site-downloads.conf.example)。保留管理后台、客户端 API、鉴权和流式连接配置。

发布后核对官网中英文、媒体摘要、视频与安装包 Range、客服流程及既有业务路由。独立下载检查见 `scripts/checks/check-public-downloads.mjs`，国产浏览器样例请求检查见 `scripts/checks/check-mobile-download-endpoints.mjs`。发布验收同时检查已登录账号的代表性业务流程。

回退先核对该次备份与当前版本，再恢复静态文件、清单及必要路由，保留不可变安装包和新产生的业务数据。历史备份目录仅作为追溯线索，不作为可直接执行的当前回退指令。
