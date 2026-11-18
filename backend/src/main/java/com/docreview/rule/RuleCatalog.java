package com.docreview.rule;

import com.docreview.domain.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RuleCatalog {

    public static final String LEGAL = "LEGAL";
    public static final String FINANCE = "FINANCE";
    public static final String COMPLIANCE = "COMPLIANCE";

    public static final List<String> DOMAINS = List.of(LEGAL, FINANCE, COMPLIANCE);

    private final List<ReviewRule> rules = List.of(
            new ReviewRule("LEGAL-01", LEGAL, "单方解除权且免除责任", RiskLevel.HIGH,
                    List.of("单方面解除", "单方解除", "无需承担任何责任"),
                    "条款赋予一方无条件单方解除权并免除其责任，权利义务显著失衡。",
                    "增加提前书面通知期限，并约定解除时的补偿责任。"),
            new ReviewRule("LEGAL-02", LEGAL, "违约责任与违约金约定", RiskLevel.MEDIUM,
                    List.of("违约金", "违约方"),
                    "违约金比例或计算方式需要与法定上限及实际损失相匹配。",
                    "明确违约金计算基数与上限，避免约定过高被认定无效。"),
            new ReviewRule("LEGAL-03", LEGAL, "争议解决与管辖", RiskLevel.LOW,
                    List.of("仲裁委员会", "管辖法院", "诉讼"),
                    "争议解决方式需明确且唯一，避免管辖约定不明。",
                    "明确约定唯一的仲裁机构或管辖法院。"),
            new ReviewRule("FIN-01", FINANCE, "预付款比例", RiskLevel.MEDIUM,
                    List.of("预付款", "预付"),
                    "预付款比例过高会放大资金风险，需与履约进度匹配。",
                    "将预付款比例控制在合同金额的 30% 以内，并要求提供履约担保。"),
            new ReviewRule("FIN-02", FINANCE, "验收与尾款支付", RiskLevel.MEDIUM,
                    List.of("尾款", "验收合格后"),
                    "尾款支付节点需与验收标准绑定，避免验收标准缺失导致争议。",
                    "在合同中明确验收标准、验收期限及逾期未验收的视为通过规则。"),
            new ReviewRule("FIN-03", FINANCE, "发票与税务条款", RiskLevel.LOW,
                    List.of("发票", "税率"),
                    "需明确发票类型、税率及开具时间，避免税务合规风险。",
                    "约定发票类型与开具时限，并明确税率不一致时的处理方式。"),
            new ReviewRule("COMP-01", COMPLIANCE, "个人信息处理", RiskLevel.HIGH,
                    List.of("个人信息", "身份证", "手机号"),
                    "涉及个人信息收集与处理，需满足告知同意与最小必要原则。",
                    "补充个人信息处理的目的、范围、保存期限，并取得单独同意。"),
            new ReviewRule("COMP-02", COMPLIANCE, "保密义务与期限", RiskLevel.MEDIUM,
                    List.of("保密"),
                    "保密范围与保密期限需明确，否则难以主张违约责任。",
                    "明确保密信息范围、保密期限及违约责任。"),
            new ReviewRule("COMP-03", COMPLIANCE, "数据出境合规", RiskLevel.HIGH,
                    List.of("数据出境", "境外", "跨境传输"),
                    "涉及数据出境，需完成安全评估、标准合同或认证等合规路径。",
                    "补充数据出境合规路径，并约定双方的数据安全责任。")
    );

    public List<ReviewRule> all() {
        return rules;
    }

    public List<ReviewRule> byDomain(String domain) {
        return rules.stream().filter(r -> r.domain().equals(domain)).toList();
    }

    public String domainName(String domain) {
        return switch (domain) {
            case LEGAL -> "法务";
            case FINANCE -> "财务";
            case COMPLIANCE -> "合规";
            default -> domain;
        };
    }
}
