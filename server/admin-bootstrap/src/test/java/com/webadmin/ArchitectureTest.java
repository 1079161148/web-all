package com.webadmin;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 架构约束测试。
 *
 * <h3>为什么这个测试比任何文档都重要</h3>
 * 设计文档 §4.3 规定的依赖方向、§1.2 的「领域层零框架依赖」等红线，
 * 如果只写在文档里，三个月后一定会被某次「赶进度」的提交破坏，而且没人发现。
 * <b>把它们变成构建期断言，规则才真正存在。</b>
 *
 * <p>这些规则在 CI 中执行，破坏即构建失败（设计文档 §13.2 架构测试层）。
 *
 * <h3>为什么用 ArchUnit 而不是 Spring Modulith 的 ApplicationModules.verify()</h3>
 * Modulith 的模块识别基于「包是应用根包的直接子包」，而本项目的限界上下文
 * （iam / organization / platform ...）被按<b>层</b>拆分到了不同的 Maven 模块中
 * （domain / application / infrastructure / interfaces），一个上下文横跨多个模块。
 * 这种「层优先」的多模块布局无法用 Modulith 的包模型表达，因此：
 * <ul>
 *   <li><b>依赖方向与污染校验</b> —— 用 ArchUnit（本类）</li>
 *   <li><b>事务性事件 Outbox</b> —— 仍使用 Modulith 的 events-jdbc 模块，它不依赖包布局</li>
 * </ul>
 * 若后续改为「上下文优先」包布局（如 {@code com.webadmin.iam.internal}），
 * 则可以追加 {@code ApplicationModules.verify()}。
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        // ⚠️ 这里刻意**不使用** ImportOption.Predefined.DO_NOT_INCLUDE_JARS。
        //
        // 原因：本测试位于 admin-bootstrap（唯一能同时看到全部模块的模块），
        // 而 admin-common / admin-domain / admin-application / ... 对它来说都是
        // **JAR 形式的依赖**。加上 DO_NOT_INCLUDE_JARS 会把我们自己的全部业务类过滤掉，
        // 结果是每条规则都「failed to check any classes」而失败 ——
        // 看起来像架构违规，实际是导入范围错了（已实测踩过）。
        //
        // 范围收敛靠 importPackages("com.webadmin") 完成：
        // 第三方库即使被导入，也不会匹配 ..domain.. / ..application.. 这类包名谓词。
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.webadmin");
    }

    // ------------------------------------------------------------------
    // 一、领域层与通用内核：零框架依赖（设计文档 §4.3 最高优先级红线）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("领域层不得依赖任何框架：Spring / MyBatis-Plus / Jakarta / Jackson")
    void domain_should_not_depend_on_framework() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "com.baomidou..",
                        "jakarta.persistence..",
                        "jakarta.servlet..",
                        "io.swagger..",
                        // Jackson 2 与 3 都要拦：Spring Boot 4 已切到 Jackson 3（tools.jackson），
                        // 但项目中仍可能残留 Jackson 2 依赖，两条都禁止才不会漏
                        "com.fasterxml.jackson..",
                        "tools.jackson..")
                .because("领域层必须能在没有框架的情况下被测试和复用（设计文档 §4.3）");
        rule.check(classes);
    }

    @Test
    @DisplayName("通用内核 admin-common 不得依赖 Web / 持久化 / 文档框架")
    void common_should_stay_framework_free() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.webadmin.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "com.baomidou..",
                        "jakarta.servlet..",
                        "io.swagger..")
                .because("admin-domain 依赖 admin-common，若 common 引入框架，领域层的纯净性会被间接破坏");
        rule.check(classes);
    }

    // ------------------------------------------------------------------
    // 二、分层依赖方向（设计文档 §4.3）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("领域层不得依赖应用层 / 基础设施层 / 接口层")
    void domain_should_be_isolated() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.webadmin.application..",
                        "com.webadmin.infrastructure..",
                        "com.webadmin.interfaces..")
                .because("依赖必须指向领域层（依赖倒置），而不是从领域层指向外层");
        rule.check(classes);
    }

    @Test
    @DisplayName("应用层不得依赖基础设施层（这是 DDD 分层最常被破坏的一条）")
    void application_should_not_depend_on_infrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                .because("应用层只能依赖领域层定义的接口（端口），实现由基础设施层提供");
        rule.check(classes);
    }

    @Test
    @DisplayName("领域层不得依赖应用层与接口层")
    void application_should_not_depend_on_interfaces() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("..interfaces..")
                .because("应用层是被接口层调用的，不能反向依赖");
        rule.check(classes);
    }

    // ------------------------------------------------------------------
    // 三、聚合根纯洁性（设计文档 §4.8：三层对象分离）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("领域模型不得携带持久化或框架注解（@TableName / @Entity / @Service 等）")
    void domain_models_should_not_carry_framework_annotations() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..model..")
                .should().beAnnotatedWith("com.baomidou.mybatisplus.annotation.TableName")
                .orShould().beAnnotatedWith("jakarta.persistence.Entity")
                .orShould().beAnnotatedWith("org.springframework.stereotype.Service")
                .orShould().beAnnotatedWith("org.springframework.stereotype.Component")
                .because("聚合根应保持充血且零框架注解，持久化由 infrastructure 层的 PO 承担（设计文档 §4.8）");
        rule.check(classes);
    }

    // ------------------------------------------------------------------
    // 四、命名与结构规范
    // ------------------------------------------------------------------

    @Test
    @DisplayName("领域事件应集中在 event 包")
    void domain_events_should_reside_in_event_package() {
        ArchRule rule = classes()
                .that().areAssignableTo("com.webadmin.domain.shared.DomainEvent")
                .and().areNotInterfaces()
                .should().resideInAPackage("..event..")
                .because("事件集中放置便于订阅方查找与覆盖性测试");
        rule.check(classes);
    }

    @Test
    @DisplayName("仓储实现只在基础设施层，且必须实现领域层定义的接口")
    void repository_implementations_should_live_in_infrastructure() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("RepositoryImpl")
                .should().resideInAPackage("..infrastructure..")
                .because("仓储接口在领域层，实现必须在基础设施层（依赖倒置）");
        rule.check(classes);
    }

    @Test
    @DisplayName("Controller 只允许出现在接口层")
    void controllers_should_reside_in_interfaces_layer() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("..interfaces..")
                .because("防止有人把 HTTP 概念写进应用层或领域层");
        rule.check(classes);
    }

    @Test
    @DisplayName("持久化对象（PO）只允许出现在基础设施层")
    void persistent_objects_should_reside_in_infrastructure_layer() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("PO")
                .should().resideInAPackage("..infrastructure..")
                .because("PO 是持久化实现细节，不得泄漏到领域层与应用层");
        rule.check(classes);
    }
}
