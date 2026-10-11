package com.aia.answer.service;

import com.aia.answer.common.Json;
import com.aia.answer.domain.PaperDtos;
import com.aia.answer.domain.QuestionDtos;
import com.aia.answer.domain.SystemDtos;
import com.aia.answer.repository.MetaRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 演示数据（幂等）。
 *
 * <p>首次安装后如果题库是空的，用户会面对一个无法验证任何流程的空应用。
 * 这里写入 6 种题型的样例与一张已发布试卷，让答题闭环可以立刻跑通；
 * 通过 {@code app_meta.demo_seeded} 标记保证只写一次，也不会覆盖用户数据。
 */
@Service
public class DemoDataService {

    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);
    private static final String FLAG = "demo_seeded";

    private final QuestionService questionService;
    private final PaperService paperService;
    private final MetaRepository metaRepository;
    private final Json json;

    public DemoDataService(QuestionService questionService, PaperService paperService,
                           MetaRepository metaRepository, Json json) {
        this.questionService = questionService;
        this.paperService = paperService;
        this.metaRepository = metaRepository;
        this.json = json;
    }

    @Transactional
    public SystemDtos.SeedDemoResponse seed() {
        if (metaRepository.get(FLAG).isPresent()) {
            return new SystemDtos.SeedDemoResponse(0, 0, true);
        }
        List<String> ids = new ArrayList<>();
        ids.add(create(singleChoice()));
        ids.add(create(multipleChoice()));
        ids.add(create(trueFalse()));
        ids.add(create(fillBlank()));
        ids.add(create(shortAnswer()));
        ids.add(create(essay()));

        List<PaperDtos.QuestionRef> refs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            refs.add(new PaperDtos.QuestionRef(ids.get(i), null, 5.0));
        }
        PaperDtos.PaperView paper = paperService.create(new PaperDtos.SaveRequest(
                "演示试卷 · 客观题基础", "由系统演示数据生成，可直接进入答题验证判分与错题闭环。", refs));
        paperService.publish(paper.id());

        metaRepository.put(FLAG, "1", nowUtc());
        metaRepository.audit("system.demo_seeded", "system", "demo",
                json.write(Map.of("questions", ids.size(), "paperId", paper.id())), nowUtc());
        log.info("演示数据写入完成：{} 道题 + 1 张已发布试卷", ids.size());
        return new SystemDtos.SeedDemoResponse(ids.size(), 1, false);
    }

    private String create(QuestionDtos.SaveRequest request) {
        QuestionDtos.QuestionView view = questionService.create(request);
        questionService.publish(view.id());
        return view.id();
    }

    private QuestionDtos.SaveRequest singleChoice() {
        List<QuestionDtos.OptionView> options = List.of(
                new QuestionDtos.OptionView("A", "1 次", false),
                new QuestionDtos.OptionView("B", "2 次", false),
                new QuestionDtos.OptionView("C", "3 次", true),
                new QuestionDtos.OptionView("D", "4 次", false));
        ObjectNode answer = json.object();
        answer.put("type", "single_choice");
        answer.put("value", "C");
        return new QuestionDtos.SaveRequest("TCP 建立连接需要几次握手", "single_choice", 2,
                "TCP 建立连接需要几次握手？", options, answer,
                "三次握手用于同步双方的初始序号并确认收发能力：SYN → SYN+ACK → ACK。",
                null, List.of("网络", "TCP"), "演示数据", null);
    }

    private QuestionDtos.SaveRequest multipleChoice() {
        List<QuestionDtos.OptionView> options = List.of(
                new QuestionDtos.OptionView("A", "GET", true),
                new QuestionDtos.OptionView("B", "HEAD", true),
                new QuestionDtos.OptionView("C", "POST", false),
                new QuestionDtos.OptionView("D", "PUT", true));
        ObjectNode answer = json.object();
        answer.put("type", "multiple_choice");
        answer.putArray("value").add("A").add("B").add("D");
        answer.put("partialCredit", false);
        return new QuestionDtos.SaveRequest("HTTP 中属于幂等方法的有哪些", "multiple_choice", 3,
                "在 HTTP/1.1 中，下列哪些方法是幂等的？", options, answer,
                "GET/HEAD/PUT/DELETE 是幂等的；POST 通常不幂等。本题按全对给分。",
                null, List.of("网络", "HTTP"), "演示数据", null);
    }

    private QuestionDtos.SaveRequest trueFalse() {
        ObjectNode answer = json.object();
        answer.put("type", "true_false");
        answer.put("value", false);
        return new QuestionDtos.SaveRequest("数据库表必须先满足第三范式才能创建", "true_false", 2,
                "判断：关系型数据库中的表必须先满足第三范式（3NF）才能创建。", List.of(), answer,
                "范式是设计指导而非强制约束，反范式在只读与报表场景中很常见。",
                null, List.of("数据库"), "演示数据", null);
    }

    private QuestionDtos.SaveRequest fillBlank() {
        ObjectNode answer = json.object();
        answer.put("type", "fill_blank");
        var blanks = answer.putArray("blanks");
        ObjectNode first = blanks.addObject();
        first.put("index", 1);
        first.putArray("accepted").add("foreign_keys").add("FOREIGN KEYS");
        ObjectNode second = blanks.addObject();
        second.put("index", 2);
        second.putArray("accepted").add("busy_timeout").add("busy timeout");
        return new QuestionDtos.SaveRequest("SQLite 外键与锁等待的 PRAGMA 配置", "fill_blank", 3,
                "请填写：开启外键约束使用 PRAGMA ______ = ON；设置锁等待超时使用 PRAGMA ______ = 5000。",
                List.of(), answer,
                "每个连接都要单独开启 foreign_keys；busy_timeout 决定遇到写锁时的等待毫秒数。",
                null, List.of("数据库", "SQLite"), "演示数据", null);
    }

    private QuestionDtos.SaveRequest shortAnswer() {
        ObjectNode rubric = json.object();
        var criteria = rubric.putArray("criteria");
        criteria.addObject().put("id", "c1").put("desc", "指出先落库可避免评分失败导致作答丢失").put("weight", 60);
        criteria.addObject().put("id", "c2").put("desc", "指出评分可重试、可追溯且不重复生成作答").put("weight", 40);
        rubric.put("maxScore", 10);
        ObjectNode answer = json.object();
        answer.put("type", "short_answer");
        answer.put("referenceAnswer", "先持久化作答可以保证即使评分环节失败或进程崩溃，用户答案也不会丢失；"
                + "评分随后可基于已落库的作答与幂等键重试，既不会重复生成作答记录，也让每次评分都有据可查。");
        answer.set("rubric", rubric);
        return new QuestionDtos.SaveRequest("为什么必须「先持久化作答，再判分」", "short_answer", 3,
                "请简述「先持久化作答，再判分」这条规则解决了什么问题（不少于两点）。",
                List.of(), answer, "评分依赖外部模型，属于不可靠环节；作答属于用户资产，必须先落库。",
                rubric, List.of("工程实践"), "演示数据", null);
    }

    private QuestionDtos.SaveRequest essay() {
        ObjectNode rubric = json.object();
        var criteria = rubric.putArray("criteria");
        criteria.addObject().put("id", "c1").put("desc", "说明历史作答必须可重现").put("weight", 50);
        criteria.addObject().put("id", "c2").put("desc", "说明题目改版不能污染历史结果").put("weight", 50);
        rubric.put("maxScore", 15);
        ObjectNode answer = json.object();
        answer.put("type", "essay");
        answer.put("referenceAnswer", "已发布试卷写入不可变快照后，历史作答永远按当时的题干、选项、"
                + "标准答案与分值重算，保证结果可重现；题目后续修订只会产生新版本，不会反向改变任何已产生的分数与错题统计。");
        answer.set("rubric", rubric);
        return new QuestionDtos.SaveRequest("论述：已发布试卷为什么必须写入不可变快照", "essay", 4,
                "请论述已发布试卷写入不可变快照的必要性，并说明它如何影响历史作答与错题统计。",
                List.of(), answer, "快照是历史可重现性的唯一保证。",
                rubric, List.of("工程实践", "数据一致性"), "演示数据", null);
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
