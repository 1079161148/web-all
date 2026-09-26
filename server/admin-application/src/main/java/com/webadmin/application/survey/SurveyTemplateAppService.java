package com.webadmin.application.survey;

import com.webadmin.application.survey.command.SurveyTemplateCommand;
import com.webadmin.application.survey.dto.SurveyTemplateDTO;
import com.webadmin.application.survey.port.SurveyTemplatePort;
import com.webadmin.common.error.BizException;
import com.webadmin.domain.survey.SurveyErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 调研模板应用服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyTemplateAppService {

    private final SurveyTemplatePort templatePort;

    @Transactional
    public Long create(SurveyTemplateCommand command) {
        assertCodeAvailable(command.templateCode(), null);
        Long id = templatePort.insert(command);
        log.info("创建调研模板 id={} code={}", id, command.templateCode());
        return id;
    }

    @Transactional
    public void update(long id, SurveyTemplateCommand command) {
        templatePort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.TEMPLATE_NOT_FOUND, "模板不存在"));
        assertCodeAvailable(command.templateCode(), id);
        templatePort.update(id, command);
    }

    @Transactional
    public void delete(long id) {
        templatePort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.TEMPLATE_NOT_FOUND, "模板不存在"));
        templatePort.delete(id);
    }

    public SurveyTemplateDTO detail(long id) {
        return templatePort.findById(id).orElseThrow(() -> new BizException(
                SurveyErrorCode.TEMPLATE_NOT_FOUND, "模板不存在"));
    }

    /**
     * 套用模板：引用次数 +1 并返回模板全文（含富文本正文）。
     *
     * <p>为什么 +1 放在这里而不是列表点击时：只有真正取走正文才算"被引用"，
     * 列表浏览不该累计使用次数 —— 否则这个数字会变成"被点开过几次"，
     * 失去"哪些模板真的在用"的参考价值。
     */
    @Transactional
    public SurveyTemplateDTO apply(long id) {
        SurveyTemplateDTO template = detail(id);
        templatePort.increaseUsage(id);
        log.info("套用调研模板 id={} name={}", id, template.templateName());
        return template;
    }

    @Transactional
    public int batchCreate(List<SurveyTemplateCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < commands.size(); i++) {
            try {
                create(commands.get(i));
            } catch (BizException e) {
                throw new BizException(SurveyErrorCode.TEMPLATE_CODE_DUPLICATED,
                        "第 " + (i + 1) + " 行：" + e.getMessage());
            }
        }
        return commands.size();
    }

    private void assertCodeAvailable(String templateCode, Long excludeId) {
        if (templatePort.codeExists(templateCode, excludeId)) {
            throw new BizException(SurveyErrorCode.TEMPLATE_CODE_DUPLICATED,
                    "模板编码「" + templateCode + "」已存在");
        }
    }
}
