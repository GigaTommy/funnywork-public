window.FW_CASES=[
  {
    "id": "bank",
    "cn": "银行产品评审",
    "en": "Bank product review",
    "title": "内部产品方案，怎么用 AI 做评审准备？",
    "titleEn": "Prepare an internal product review with AI.",
    "context": "产品经理准备调整企业现金管理产品。需要同时核对产品说明、内部风险制度、历史审批意见和本次需求。",
    "contextEn": "A product manager is updating a corporate cash-management product and needs to compare product specifications, internal risk rules, prior approvals, and the new requirements.",
    "prompt": "对照现行制度检查这份方案，列出缺少的审批环节、待补充材料和需风险部门确认的问题。",
    "promptEn": "Check this proposal against the current internal rules. List missing approval steps, required materials, and questions for the risk team.",
    "result": "评审表：问题 → 内部制度条款 → 影响 → 待确认事项。演示问题：方案允许线上调整额度，但未说明复核人和复核记录。",
    "resultEn": "Review table: issue → internal rule → impact → open question. Example issue: the proposal allows online limit adjustments but does not identify the reviewer or the review record.",
    "safety": "私有化部署支持企业使用自己的模型与账号访问范围，为内部产品资料提供专属的 AI 工作空间。",
    "safetyEn": "Private deployment supports your own models and account access scope, creating a dedicated AI workspace for internal materials.",
    "knowledge": "产品经理修订并确认评审表，保存为评审版本，供后续审批、方案修改和项目复盘使用。",
    "knowledgeEn": "The product manager revises and approves the table, then saves a review version for later approval, proposal revisions, and project retrospectives.",
    "docs": [
      "产品方案 v2",
      "风险制度（演示）",
      "历史审批意见",
      "本次需求清单"
    ],
    "docsEn": [
      "Proposal v2",
      "Demo risk policy",
      "Prior review notes",
      "New requirements"
    ],
    "steps": [
      "加入脱敏材料",
      "提出评审问题",
      "核对条款与结论",
      "审核并保存评审表"
    ],
    "stepsEn": [
      "Add de-identified materials",
      "Request a review",
      "Check rules and conclusions",
      "Review and save the assessment"
    ],
    "outputs": [
      "内部制度要求：额度变更须经复核。",
      "方案未写明复核负责人和留痕方式。",
      "请产品与风险部门确认后补充方案。"
    ],
    "outputsEn": [
      "Internal rule: limit changes require review.",
      "The proposal omits the reviewer and review record.",
      "Confirm with product and risk teams before revising."
    ]
  },
  {
    "id": "government",
    "cn": "政务办事指南",
    "en": "Public-service guidance",
    "title": "政策更新后，把办事指南和窗口问答一起更新。",
    "titleEn": "Update service guidance and frontline answers together.",
    "context": "工作人员收到补充通知，需要更新上一版办事指南，并准备窗口人员使用的常见问答。",
    "contextEn": "A staff member receives an updated notice and needs to revise the previous service guide and prepare common answers for frontline colleagues.",
    "prompt": "对照新通知更新指南，列出需要改动的条件，并整理十个窗口常见问答。",
    "promptEn": "Update the guide against the new notice, list changed requirements, and prepare ten common questions and answers.",
    "result": "变更对照表、新版指南、窗口问答。演示变更：材料清单由三项改为两项，旧版指南仍保留已取消的材料。",
    "resultEn": "A change table, revised guide, and frontline Q&A. Example change: the material list now has two items instead of three, but the old guide still includes the removed item.",
    "safety": "私有化部署支持企业使用自己的模型与账号访问范围，为内部业务资料提供专属的 AI 工作空间。",
    "safetyEn": "Private deployment supports your own models and account access scope, creating a dedicated AI workspace for internal materials.",
    "knowledge": "确认后的指南和问答保存到部门指定资料位置，让后续工作人员使用同一份审核版本。",
    "knowledgeEn": "Save approved guidance and answers to the department’s designated location so colleagues use the same reviewed version.",
    "docs": [
      "旧版办事指南",
      "补充通知（演示）",
      "材料清单",
      "脱敏咨询记录"
    ],
    "docsEn": [
      "Previous service guide",
      "Demo update notice",
      "Required materials",
      "De-identified inquiries"
    ],
    "steps": [
      "整理不同版本材料",
      "核对新通知日期",
      "标出变更并写问答",
      "审核并发布部门版本"
    ],
    "stepsEn": [
      "Collect document versions",
      "Check the notice date",
      "Highlight changes and draft Q&A",
      "Review the departmental version"
    ],
    "outputs": [
      "演示新通知：取消第三项材料。",
      "旧版指南：第三项仍列为必交。",
      "确认有效日期后，更新指南与问答。"
    ],
    "outputsEn": [
      "Demo notice: the third item is no longer required.",
      "Old guide: the third item remains mandatory.",
      "Confirm the effective date, then revise guidance and Q&A."
    ]
  },
  {
    "id": "handover",
    "cn": "项目交接与知识沉淀",
    "en": "Project handover & retained knowledge",
    "title": "人员交接时，留下决策过程和待办事项。",
    "titleEn": "Keep decisions and open actions when a project changes hands.",
    "context": "产品负责人准备交接项目。需求文档、会议纪要、方案版本和测试记录分散在十二份资料中。",
    "contextEn": "A product owner is handing over a project, with requirements, meeting notes, proposal versions, and test records scattered across twelve documents.",
    "prompt": "整理项目决策时间线，说明方案为什么调整，列出未完成事项和相关资料。",
    "promptEn": "Build a timeline of project decisions, explain why the proposal changed, and list open actions and related documents.",
    "result": "交接文档、决策时间线、待办清单与资料索引。演示决策：从实时提醒改为每日汇总，原因是试点反馈中过多提醒影响使用。",
    "resultEn": "A handover document, decision timeline, action list, and material index. Example decision: replace real-time alerts with a daily summary because pilot feedback showed too many interruptions.",
    "safety": "私有化部署支持企业使用自己的模型与账号访问范围，为内部项目资料提供专属的 AI 工作空间。",
    "safetyEn": "Private deployment supports your own models and account access scope, creating a dedicated AI workspace for internal materials.",
    "knowledge": "保留负责人对摘要的修订、决策日期和材料出处。接手人可以依据交接文档继续工作，而不是重新阅读全部资料。",
    "knowledgeEn": "Keep the owner’s corrections, decision dates, and document references so the next owner can continue without rereading every file.",
    "docs": [
      "需求文档 v1–v3",
      "三次会议纪要",
      "试点反馈",
      "测试记录"
    ],
    "docsEn": [
      "Requirements v1–v3",
      "Three meeting records",
      "Pilot feedback",
      "Test records"
    ],
    "steps": [
      "加入项目资料",
      "整理决策时间线",
      "负责人纠正与补充",
      "保存交接文档供查阅"
    ],
    "stepsEn": [
      "Add project materials",
      "Build the decision timeline",
      "Owner corrects and adds context",
      "Save the handover for reuse"
    ],
    "outputs": [
      "原方案：实时推送每一条提醒。",
      "试点反馈：提醒过多，干扰日常工作。",
      "确认决策：每日汇总；异常另行提醒。"
    ],
    "outputsEn": [
      "Original plan: send each alert in real time.",
      "Pilot feedback: too many alerts interrupt work.",
      "Approved decision: daily summary, separate exception alerts."
    ]
  }
];
