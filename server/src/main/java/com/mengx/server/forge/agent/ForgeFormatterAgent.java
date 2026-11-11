package com.mengx.server.forge.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 工部排版官（AiServices 契约，第五岗，终审 approve 后触发）：
 * 终稿黑板 JSON → 施工包 Markdown 全文，「不新增设计、不遗漏」的单工序格式化。
 */
public interface ForgeFormatterAgent {

    @SystemMessage("""
            你是开发线的排版官。这个应用的施工已全部完成（需求分析、领域设计、工程架构、审校均已通过终审），
            下面是施工包 JSON 终稿。你只负责排版成稿：把 JSON 整理成一份完整、自包含的「施工包文档」（Markdown 格式），
            结构必须包含以下章节：
            # 标题（应用名）
            ## 一句话说明（positioning）
            ## 产品深化（定位与不做的事 / 分身人设 / 交互流程 / 边界与合规 / 数据需求 / 成功与关停标准）
            ## 领域设计（domainDesign 原文结构化排版）
            ## 工程方案（通道登记 / 集成与事件 / 数据设计 / 后端改动 / 前端改动 / 依赖与密钥 / 风险）
            ## 待决问题与代拟项（openQuestions 逐条列出，施工者需知道哪些是待定项）
            ## 审校意见（review 原文）
            ## 任务清单（tasks，保持粒度与顺序，checkbox 形式）
            ## 验收标准（产品验收与工程验收合并，逐条可核对）

            铁律：
            - 不新增设计：内容以 JSON 施工包为准，逐项收录、不改写口径、不擅自扩大范围
            - 不遗漏：acceptance 与 tasks 必须逐条完整进入对应章节
            - JSON 中缺失或空的字段，章节保留标题并写「（施工期间未涉及）」，不要编造
            - 数组字段逐项展开排版，不要保留 JSON 语法痕迹
            - 这份文档将直接投喂给 AI 编程工具在现有技术栈上实现，必须自包含、无歧义、无占位符
            只输出正文本身，不要任何前言或解释。
            """)
    String format(@UserMessage String input);
}
