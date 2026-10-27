package com.mengx.server.routing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 通道注册表：集中登记各领域能力元数据，供能力名册（共创提示词）等遍历使用。
 * 旧分层路由（L1 信号词短路 / L2 名册提示词）已随 Host function calling 架构下线，
 * 路由决策现由 HostWorker 的工具列表承担；新领域接入实现 DomainTool 即可。
 */
@Component
public class ChannelRegistry {

    /** 默认通道：Host 直答（日常陪聊与接待分发） */
    public static final String DEFAULT_CHANNEL = "host";

    private final Map<String, ChannelSpec> channels = new LinkedHashMap<>();

    public ChannelRegistry() {
        register(new ChannelSpec(
                "law", "法学助手",
                "把消息当作法律问题来问——日常法律咨询（纠纷、维权、合同等）、法条或法律概念的含义、"
                        + "法律案例分析、法考备考、法律责任等，期待法律专业的解答",
                List.of(
                        "法条", "法学", "法考", "法律", "法院", "法规", "司法", "立法", "普法", "执法", "犯法", "学法",
                        "刑法", "民法", "宪法", "诉讼", "仲裁", "律师", "律所", "检察院", "公安", "拘留", "传唤", "自首",
                        "辩护", "公诉", "原告", "被告", "判决", "裁定", "量刑", "定罪", "罪", "犯罪", "违法", "侵权",
                        "缓刑", "拘役", "立案", "起诉", "上诉", "申诉", "证据", "案卷", "案件", "案例", "合同", "纠纷",
                        "官司", "维权", "权益", "备考", "判例", "条文", "条例", "治安", "离婚", "遗嘱", "继承", "债务",
                        "债权", "诈骗", "防卫", "真题", "试题", "学情", "学得怎么样", "正确率",
                        // 劳动就业维权（口语咨询高频，缺词会被 L1 短路在默认通道、走不到 L2 细判）
                        "劳动法", "劳动合同", "裁员", "辞退", "开除", "离职", "试用期", "竞业", "工资", "欠薪",
                        "拖欠", "加班费", "社保", "五险一金", "工伤", "赔偿", "补偿", "经济补偿", "劳动监察",
                        // 民事债务/消费/租房
                        "借钱", "欠钱", "欠款", "欠债", "借条", "欠条", "还钱", "还债", "押金", "定金", "订金",
                        "退款", "退货", "假货", "欺诈", "房东", "租房", "租客", "退租",
                        // 婚姻家庭/事故/其他维权
                        "家暴", "出轨", "抚养", "赡养", "扶养", "彩礼", "交通事故", "肇事", "酒驾",
                        "医疗事故", "公证", "举报"),
                List.of(),
                List.of(
                        new ChannelSpec.Example("刑法第13条说的情节显著轻微是什么意思？", "law"),
                        new ChannelSpec.Example("遗嘱都有哪几种形式？", "law"),
                        new ChannelSpec.Example("想看2024年真题", "law"),
                        new ChannelSpec.Example("给我出几道题练练", "law"),
                        new ChannelSpec.Example("考我几道刑法题", "law"),
                        new ChannelSpec.Example("交卷：1B 2AD 3C", "law"),
                        new ChannelSpec.Example("交卷", "law"),
                        new ChannelSpec.Example("下一题", "law"),
                        new ChannelSpec.Example("租的房子房东不退押金该怎么办？", "law"),
                        new ChannelSpec.Example("我被裁员了，公司拒绝赔偿怎么办", "law"),
                        new ChannelSpec.Example("老板拖欠两个月工资不给怎么维权", "law")),
                // Worker 执行元数据：流式作答 + 法条/真题 RAG 检索，60s 超时
                WorkerType.SYNC_STREAM, 60_000, true, null, null));
        register(new ChannelSpec(
                "cocreation", "需求共创",
                "把一个想法或模糊需求聊成清晰、可落地的功能方案——需求澄清、方案推演、规格快照、功能卡产出；"
                        + "想给本产品设计新功能/新应用（即使点子内容本身涉及法律，比如想做一个法律咨询助手）也走这里",
                List.of(),
                List.of(),
                List.of(
                        new ChannelSpec.Example("我想做个记账的小东西", "cocreation"),
                        new ChannelSpec.Example("帮我设计一个提醒喝水的功能", "cocreation"),
                        new ChannelSpec.Example("我想做一个法律咨询助手，专门答合同问题", "cocreation"),
                        new ChannelSpec.Example("继续聊刚才那个点子", "cocreation")),
                // Worker 执行元数据：流式作答（推演 + 顾问访谈），120s 超时，无需 RAG
                WorkerType.SYNC_STREAM, 120_000, false, null, null));
        register(new ChannelSpec(
                DEFAULT_CHANNEL, "主对话",
                "日常闲聊、答疑陪聊，以及其余一切话题的接待与分发",
                List.of(),
                List.of(),
                List.of(new ChannelSpec.Example("这个点子能落地吗，你觉得怎么样", "host")),
                // Worker 执行元数据：流式作答（闲聊/答疑），60s 超时，无需 RAG
                WorkerType.SYNC_STREAM, 60_000, false, null, null));
    }

    private void register(ChannelSpec spec) {
        channels.put(spec.id(), spec);
    }

    /** 全部通道（能力名册遍历用） */
    public java.util.Collection<ChannelSpec> all() {
        return channels.values();
    }
}
